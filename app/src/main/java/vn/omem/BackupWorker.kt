package vn.omem

import android.content.ContentUris
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NoSpace : Exception("Hết dung lượng ở tất cả tài khoản")

/** Chỉ cho một lượt sao lưu chạy tại một thời điểm (lịch định kỳ, theo dõi ảnh mới, bấm tay) để không tải trùng. */
private val runLock = Mutex()

/** Số tệp tải lên cùng lúc. */
private const val PARALLEL = 3

class BackupWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {

    override suspend fun doWork(): Result {
        // Lượt chạy do ảnh/video mới xuất hiện: xong thì đặt lại bộ theo dõi cho lần sau
        val viaTrigger = inputData.getBoolean("trigger", false)
        try {
            val r = runLock.withLock { backupAll() }
            // Lượt theo dõi không retry (retry sẽ chờ thay đổi mới); việc chạy lại do lịch định kỳ lo
            return if (viaTrigger) Result.success() else r
        } finally {
            if (viaTrigger) {
                Scheduler.armTrigger(applicationContext)
                // Ảnh vừa chụp có thể chưa ghi xong lúc quét: quét lại sau 1 phút cho chắc
                Scheduler.followUp(applicationContext)
            }
        }
    }

    private suspend fun backupAll(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val done = DoneDb(ctx)
        val todo = MediaScan.scan(ctx).filter { !done.isDone(it.key) }
        if (todo.isEmpty()) { Store.setLastRun(ctx, "không có tệp mới"); return@withContext Result.success() }

        val free = mutableMapOf<String, Long>()
        try {
            // Tài khoản nào chưa bật (kể cả tài khoản mới) thì tự bật luôn
            Server.accountsAutoEnabled(ctx).filter { it.enabled && it.ok }.forEach { free[it.email] = it.free }
        } catch (e: Exception) { Log.w("OMem", "accounts", e); Store.setLastRun(ctx, "lỗi đọc tài khoản: ${e.message}"); return@withContext Result.retry() }
        if (free.isEmpty()) { Store.setLastRun(ctx, "chưa có tài khoản nào bật/đọc được dung lượng (${todo.size} tệp đang chờ)"); return@withContext Result.success() }

        // Bỏ qua tệp đã có trong thư viện Ổ mềm trên server (chống trùng theo tên + dung lượng)
        val onServer = try { Server.existing(ctx, todo.map { it.dedupe }) } catch (e: Exception) { Store.setLastRun(ctx, "lỗi kiểm tra trùng: ${e.message}"); return@withContext Result.retry() }
        val pending = todo.filter { f ->
            if (f.dedupe in onServer) { done.markDone(f.key); false } else true
        }
        if (pending.isEmpty()) { Store.setLastRun(ctx, "không có tệp mới (đã có trên Ổ mềm)"); return@withContext Result.success() }

        // Hiện thông báo nền (từ nền Android 12+ có thể bị từ chối, khi đó vẫn chạy bình thường)
        val showNotif = AtomicBoolean(true)
        runCatching { setForeground(foreground(ctx, "0/${pending.size}")) }.onFailure { showNotif.set(false) }

        val failed = AtomicBoolean(false)
        val finished = AtomicInteger(0)
        val gate = Semaphore(PARALLEL)
        coroutineScope {
            pending.map { f ->
                async {
                    gate.withPermit {
                        if (!isStopped) {
                            try {
                                backupOne(ctx, free, f)
                                done.markDone(f.key)
                            } catch (e: NoSpace) {
                                Log.w("OMem", "no space for ${f.name}")
                            } catch (e: Exception) {
                                Log.w("OMem", "fail ${f.name}", e); failed.set(true)
                            }
                            val n = finished.incrementAndGet()
                            if (showNotif.get() && n % 5 == 0) {
                                runCatching { setForeground(foreground(ctx, "$n/${pending.size}")) }
                            }
                        }
                        Unit
                    }
                }
            }.awaitAll()
        }
        val total = finished.get()
        Store.setLastRun(ctx, "đã xử lý $total/${pending.size} tệp" + (if (failed.get()) ", có tệp lỗi sẽ thử lại" else ""))
        if (isStopped || failed.get()) Result.retry() else Result.success()
    }

    /** Ảnh thu nhỏ do chính máy tạo (cả ảnh lẫn video) để server hiển thị trong thư viện. */
    private fun thumb(ctx: Context, id: Long, mime: String): String? {
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
        return try {
            val bmp = Compat.thumbnail(ctx, id, mime.startsWith("video/"), 320) ?: return null
            var q = 70
            val out = ByteArrayOutputStream()
            do { out.reset(); bmp.compress(Bitmap.CompressFormat.JPEG, q, out); q -= 15 } while (out.size() > 100_000 && q > 10)
            if (out.size() > 100_000) null else "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    /** Giữ chỗ dung lượng ngay lúc lập kế hoạch để các tệp tải song song không giành cùng một chỗ trống. */
    private fun reserve(free: MutableMap<String, Long>, size: Long): List<Pair<String, Long>> = synchronized(free) {
        val reserve = 20L * 1024 * 1024
        val tmp = free.toMutableMap()
        val plan = mutableListOf<Pair<String, Long>>()
        var rem = size
        while (rem > 0) {
            val best = tmp.maxByOrNull { it.value }
            val avail = (best?.value ?: 0L) - reserve
            if (best == null || avail <= 0) throw NoSpace()
            val take = minOf(rem, avail)
            plan += best.key to take
            tmp[best.key] = best.value - take
            rem -= take
        }
        free.putAll(tmp)
        plan
    }

    private fun backupOne(ctx: Context, free: MutableMap<String, Long>, f: Item) {
        val plan = reserve(free, f.size)
        SyncState.start(f.id)

        val grp = UUID.randomUUID().toString()
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), f.id)
        try {
            val thumbData = thumb(ctx, f.id, f.mime)
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                plan.forEachIndexed { i, (email, take) ->
                    val dName = if (plan.size == 1) f.name else "${f.name}.part${"%03d".format(i)}"
                    val id = DriveHelper.upload(
                        ctx, email, dName, f.mime, take, LimitedInputStream(input, take),
                        mapOf("source" to "omem", "grp" to grp, "orig" to f.name, "part" to "$i", "parts" to "${plan.size}")
                    )
                    val rec = JSONObject()
                        .put("dedupeKey", f.dedupe).put("grp", grp).put("part", i).put("parts", plan.size)
                        .put("name", f.name).put("mimeType", f.mime).put("size", f.size)
                        .put("accountEmail", email).put("driveFileId", id)
                    if (i == 0 && thumbData != null) rec.put("clientThumbnail", thumbData)
                    Server.record(ctx, rec)
                }
            }
        } catch (e: Exception) {
            // Trả lại chỗ đã giữ rồi dọn các phần dở dang (Drive + thư viện)
            synchronized(free) { plan.forEach { (email, take) -> free[email] = (free[email] ?: 0L) + take } }
            runCatching { Server.deleteGroup(ctx, grp) }
            throw e
        } finally {
            SyncState.end(f.id)
        }
    }

    private fun foreground(ctx: Context, text: String): ForegroundInfo {
        // Kênh thông báo chỉ có từ Android 8; trên Android 7 thư viện tự bỏ qua
        NotificationManagerCompat.from(ctx).createNotificationChannel(
            NotificationChannelCompat.Builder("backup", NotificationManagerCompat.IMPORTANCE_LOW).setName("Sao lưu").build()
        )
        val n = NotificationCompat.Builder(ctx, "backup")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle("Ổ mềm đang sao lưu").setContentText(text).setOngoing(true).build()
        // Loại dịch vụ nền (dataSync) chỉ có từ Android 10
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(1, n)
    }
}

object Scheduler {
    private fun network(ctx: Context) = if (Store.wifiOnly(ctx)) NetworkType.UNMETERED else NetworkType.CONNECTED

    private fun constraints(ctx: Context) = Constraints.Builder().setRequiredNetworkType(network(ctx)).build()

    /** Bật/tắt tự sao lưu theo cài đặt. reset = true khi vừa đổi cài đặt (Wi-Fi/tự sao lưu). */
    fun apply(ctx: Context, reset: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        if (!Store.auto(ctx)) { wm.cancelUniqueWork("periodic"); wm.cancelUniqueWork("media"); return }
        wm.enqueueUniquePeriodicWork(
            "periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES).setConstraints(constraints(ctx)).build()
        )
        armTrigger(ctx, if (reset) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    /** Có ảnh/video mới thì chạy sao lưu ngay (không phải chờ chu kỳ 15 phút). */
    fun armTrigger(ctx: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        if (!Store.auto(ctx)) return
        val c = Constraints.Builder()
            .setRequiredNetworkType(network(ctx))
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(15, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
            .build()
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(c)
            .setInputData(workDataOf("trigger" to true))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("media", policy, req)
    }

    /** Quét lại sau 1 phút (bắt ảnh ghi xong muộn hoặc chụp trong lúc đang sao lưu). */
    fun followUp(ctx: Context) {
        if (!Store.auto(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "followup", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(ctx)).setInitialDelay(60, TimeUnit.SECONDS).build()
        )
    }

    fun now(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(ctx)).build()
        )
    }
}
