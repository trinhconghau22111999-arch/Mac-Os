package vn.omem

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Kết quả trả về cho giao diện HTML (một chuỗi JSON). */
fun interface OmemCb { fun done(json: String) }

/**
 * Cầu nối giữa giao diện HTML của app con "Ổ mềm" (trong Desktop Mini) và phần logic Kotlin
 * (server, Google Drive, sao lưu nền). Mọi lệnh chạy ở luồng nền rồi trả JSON.
 */
object OmemApi {
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var lastLib: List<Group> = emptyList()
    @Volatile private var lastKick = 0L
    @Volatile private var appCtx: Context? = null
    private fun ctx0(): Context = appCtx!!

    @JvmStatic fun call(ctx: Context, op: String, arg: String, cb: OmemCb) {
        appCtx = ctx.applicationContext
        pool.execute {
            val out = try { handle(ctx, op, JSONObject(if (arg.isBlank()) "{}" else arg)) }
            catch (e: Exception) { JSONObject().put("ok", false).put("err", e.message ?: e.javaClass.simpleName) }
            cb.done(out.toString())
        }
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun handle(ctx: Context, op: String, a: JSONObject): JSONObject = when (op) {
        "init" -> { migrateThumbs(ctx); Scheduler.apply(ctx); ok() }
        "state" -> try { state(ctx) } catch (e: Exception) {
            // Server lỗi (ví dụ 500) hoặc mất mạng: vẫn hiện bản đã lưu trong máy thay vì báo lỗi trắng màn hình
            val f = libCacheFile(ctx)
            if (f.exists() && f.length() > 0) JSONObject(f.readText()).put("stale", true).put("serr", e.message ?: "lỗi")
            else throw e
        }
        // Trạng thái lần trước lưu trong máy: trả ngay để lưới ảnh hiện tức thì, không chờ mạng
        "cstate" -> {
            migrateThumbs(ctx)
            val f = libCacheFile(ctx)
            if (f.exists() && f.length() > 0) JSONObject(f.readText()) else JSONObject().put("ok", false).put("err", "no cache")
        }
        "thumb" -> remoteThumb(ctx, a.getString("id"), a.optBoolean("video"))
        "lthumb" -> {
            val b = Compat.thumbnail(ctx, a.getString("id").toLong(), a.optBoolean("video"), 240)
            img(b?.let { toB64(it, 78) }, "image/jpeg")
        }
        "view" -> {
            val uri = if (a.getString("kind") == "r") Uri.fromFile(Restore.toCache(ctx, group(a.getString("key"))))
                      else Compat.fileUri(a.getString("key").toLong())
            val b = Compat.decodeImage(ctx, uri, 1800) ?: throw Exception("Không mở được ảnh này")
            img(toB64(b, 88), "image/jpeg")
        }
        "video" -> { openVideo(ctx, a); ok() }
        // Mở trong app: trả đường dẫn tệp (file://) để trình xem trong Ổ mềm tự phát/hiển thị
        "media" -> {
            val f: java.io.File; val mime: String
            if (a.getString("kind") == "r") { val g = group(a.getString("key")); f = Restore.toCache(ctx, g); mime = g.head.mime }
            else { f = localFile(ctx, a.getString("key").toLong(), a.optBoolean("video")); mime = "" }
            ok().put("url", Uri.fromFile(f).toString()).put("mime", mime).put("size", f.length())
        }
        "text" -> {
            val f = Restore.toCache(ctx, group(a.getString("key")))
            val cap = 512 * 1024
            val buf = ByteArray(cap)
            val n = f.inputStream().use { it.read(buf) }.coerceAtLeast(0)
            ok().put("text", String(buf, 0, n, Charsets.UTF_8)).put("cut", f.length() > cap)
        }
        "dl" -> {
            val gs = a.getJSONArray("grps"); var n = 0; var err = ""
            for (i in 0 until gs.length()) {
                try { Restore.run(ctx, group(gs.getString(i))); n++ } catch (e: Exception) { err = e.message ?: "lỗi" }
            }
            ok().put("done", n).put("total", gs.length()).put("err", err)
        }
        "del" -> {
            val gs = a.getJSONArray("grps"); var n = 0; var err = ""
            for (i in 0 until gs.length()) {
                try { Server.deleteGroup(ctx, gs.getString(i)); purgeCache(ctx, gs.getString(i)); n++ } catch (e: Exception) { err = e.message ?: "lỗi" }
            }
            ok().put("done", n).put("total", gs.length()).put("err", err)
        }
        "now" -> { Scheduler.now(ctx); ok() }
        "warm" -> { startWarm(ctx); ok() }
        "warmstop" -> { warmRun = false; ok() }
        "thclear" -> { thumbDir(ctx).listFiles()?.forEach { it.delete() }; ok() }
        "set" -> {
            val v = a.getBoolean("v")
            when (a.getString("k")) {
                "auto" -> Store.setAuto(ctx, v)
                "lock" -> { val old = thumbDir(ctx); Store.setLockCache(ctx, v); moveThumbs(old, thumbDir(ctx)) }
                else -> Store.setWifiOnly(ctx, v)
            }
            Scheduler.apply(ctx, true); ok()
        }
        "addacc" -> {
            start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(Store.serverUrl(ctx).trimEnd('/') + "/api/auth/google/start")))
            ok()
        }
        "battery" -> {
            start(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))); ok()
        }
        "allfiles" -> {
            if (Build.VERSION.SDK_INT >= 30)
                start(ctx, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            ok()
        }
        else -> throw Exception("Lệnh không hợp lệ: $op")
    }

    /** Thư mục ảnh xem trước: khi “khoá” thì nằm trong bộ nhớ riêng của app (bền), ngược lại nằm trong bộ nhớ đệm (hệ thống có thể dọn). */
    private fun thumbDir(ctx: Context): java.io.File =
        java.io.File(ctx.filesDir, "omem_thumbs").apply { mkdirs() }   // luôn lưu bền, hệ thống không dọn

    private fun thumbFile(ctx: Context, key: String): java.io.File =
        java.io.File(thumbDir(ctx), key.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    /** Chuyển ảnh xem trước cũ (nằm trong bộ nhớ đệm hệ thống) sang thư mục bền. */
    private fun migrateThumbs(ctx: Context) {
        runCatching { moveThumbs(java.io.File(ctx.cacheDir, "thumbs"), thumbDir(ctx)) }
    }

    private fun libCacheFile(ctx: Context) = java.io.File(ctx.filesDir, "omem_state.json")

    private fun moveThumbs(from: java.io.File, to: java.io.File) {
        if (from.absolutePath == to.absolutePath) return
        to.mkdirs()
        from.listFiles()?.forEach { f ->
            runCatching { val t = java.io.File(to, f.name); if (!f.renameTo(t)) { f.copyTo(t, true); f.delete() } }
        }
    }

    /** Xoá mọi bộ nhớ đệm của một mục đã xoá: ảnh xem trước và tệp gốc tải tạm để xem. */
    private fun purgeCache(ctx: Context, grp: String) {
        runCatching { java.io.File(thumbDir(ctx), grp.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg").delete() }
        runCatching {
            val pre = grp.replace('/', '_') + "_"
            java.io.File(ctx.cacheDir, "view").listFiles()?.filter { it.name.startsWith(pre) }?.forEach { it.delete() }
        }
        lastLib = lastLib.filter { it.head.grp != grp }
    }

    @Volatile private var warmRun = false
    @Volatile private var warmDone = 0
    @Volatile private var warmTotal = 0
    /** Tạo sẵn ảnh xem trước cho mọi ảnh đã sao lưu để cuộn thư viện luôn nhanh. */
    private fun startWarm(ctx: Context) {
        if (warmRun) return
        // Chỉ lấy những ảnh CHƯA có ảnh xem trước; tấm nào tải rồi thì bỏ qua
        val gs = lastLib.filter { it.head.mime.startsWith("image/") || (it.head.mime.startsWith("video/") && it.head.hasThumb) }
            .filter { g -> thumbFile(ctx, g.head.grp).let { !(it.exists() && it.length() > 0) } }
        if (gs.isEmpty()) return
        warmRun = true; warmDone = 0; warmTotal = gs.size
        Thread {
            try {
                var fails = 0
                for (g in gs) {
                    if (!warmRun) break
                    val f = thumbFile(ctx, g.head.grp)
                    if (!(f.exists() && f.length() > 0)) {
                        val r = runCatching { remoteThumb(ctx, g.head.grp, g.head.mime.startsWith("video/"), true) }.getOrNull()
                        if (r != null && r.optBoolean("ok")) fails = 0 else fails++
                        if (fails >= 5) break   // server liên tục lỗi: dừng, lần mở sau sẽ thử lại
                    }
                    warmDone++
                }
            } finally { warmRun = false }
        }.apply { priority = Thread.MIN_PRIORITY; start() }
    }

    /** Bản xem trước của mục trên Drive: lấy từ bộ nhớ đệm, rồi từ máy chủ; nếu không có thì tự tạo từ ảnh gốc (chỉ với ảnh). */
    private fun remoteThumb(ctx: Context, key: String, video: Boolean, tmp: Boolean = false): JSONObject {
        val cf = thumbFile(ctx, key)
        if (cf.exists() && cf.length() > 0) {
            val b = cf.readBytes()
            return img(Base64.encodeToString(b, Base64.NO_WRAP), sniff(b))
        }
        var raw: ByteArray? = Server.thumbnail(ctx, key)
        if (raw == null || raw.isEmpty()) {
            raw = null
            if (!video) runCatching {
                val f = Restore.toCache(ctx, group(key))
                val bm = Compat.decodeImage(ctx, Uri.fromFile(f), 320)
                if (bm != null) { val o = ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 78, o); raw = o.toByteArray() }
                if (tmp) f.delete()
            }
        }
        val r = raw ?: return img(null, "image/jpeg")
        runCatching { cf.writeBytes(r) }
        return img(Base64.encodeToString(r, Base64.NO_WRAP), sniff(r))
    }

    /** Tệp cục bộ của một mục trong thư viện: dùng đường dẫn thật nếu đọc được, nếu không thì chép vào bộ nhớ đệm. */
    private fun localFile(ctx: Context, id: Long, video: Boolean): java.io.File {
        val uri = Compat.fileUri(id)
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val p = c.getString(0)
                    if (!p.isNullOrEmpty()) { val f = java.io.File(p); if (f.canRead()) return f }
                }
            }
        }
        val dir = java.io.File(ctx.cacheDir, "view").apply { mkdirs() }
        val f = java.io.File(dir, "local_$id" + if (video) ".mp4" else "")
        if (!f.exists()) {
            val tmp = java.io.File(dir, f.name + ".tmp")
            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(f)
        }
        return f
    }

    private fun start(ctx: Context, i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }

    private fun group(grp: String): Group =
        lastLib.firstOrNull { it.head.grp == grp } ?: Server.library(ctx0()).also { lastLib = it }.first { it.head.grp == grp }

    private fun toB64(b: Bitmap, q: Int): String {
        val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, q, o)
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
    }
    private fun sniff(b: ByteArray): String = when {
        b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 12 && b[0] == 'R'.code.toByte() && b[8] == 'W'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }
    private fun img(b64: String?, mime: String): JSONObject =
        if (b64 == null) JSONObject().put("ok", false).put("err", "no thumb") else ok().put("img", "data:$mime;base64,$b64")

    private fun openVideo(ctx: Context, a: JSONObject) {
        val mime: String; val uri: Uri
        if (a.getString("kind") == "r") {
            val g = group(a.getString("key"))
            val f = Restore.toCache(ctx, g)
            uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            mime = g.head.mime
        } else {
            uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, a.getString("key").toLong())
            mime = "video/*"
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(ctx, Intent.createChooser(view, "Mở video bằng").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun state(ctx: Context): JSONObject {
        val accs = Server.accountsAutoEnabled(ctx)
        val lib = Server.library(ctx); lastLib = lib
        val local = runCatching { MediaScan.notBackedUp(ctx, lib) }.getOrDefault(emptyList())
        val up = SyncState.uploading.value
        // Có tệp chờ mà chưa chạy sao lưu: bắt đầu luôn (tối đa 1 lần/phút)
        val now = System.currentTimeMillis()
        if (local.isNotEmpty() && up.isEmpty() && Store.auto(ctx) && now - lastKick > 60_000) { lastKick = now; Scheduler.now(ctx) }

        val en = accs.filter { it.enabled && it.ok }
        val pm = ctx.getSystemService(PowerManager::class.java)
        // Luôn tự tải sẵn ảnh xem trước còn thiếu (không cần bấm)
        startWarm(ctx)
        val res = ok()
            .put("accounts", JSONArray().apply { accs.forEach { put(JSONObject().put("email", it.email).put("en", it.enabled).put("q", it.ok).put("limit", it.limit).put("usage", it.usage)) } })
            .put("total", en.sumOf { it.limit }).put("used", en.sumOf { it.usage })
            .put("lib", JSONArray().apply {
                lib.forEach { g -> put(JSONObject().put("g", g.head.grp).put("id", g.head.id).put("name", g.head.name)
                    .put("mime", g.head.mime).put("size", g.head.size).put("parts", g.parts.size).put("th", g.head.hasThumb)) }
            })
            .put("local", JSONArray().apply {
                local.take(500).forEach { put(JSONObject().put("id", it.id.toString()).put("name", it.name).put("video", it.isVideo).put("up", it.id in up)) }
            })
            .put("nlocal", local.size).put("up", up.size)
            .put("auto", Store.auto(ctx)).put("wifi", Store.wifiOnly(ctx)).put("last", Store.lastRun(ctx))
            .put("lock", Store.lockCache(ctx))
            .put("thc", thumbDir(ctx).listFiles()?.size ?: 0).put("thb", thumbDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L)
            .put("warm", warmRun).put("wd", warmDone).put("wt", warmTotal)
            .put("battery", pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true)
            .put("needAll", Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
        runCatching { libCacheFile(ctx).writeText(res.toString()) }
        return res
    }
}
