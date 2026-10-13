package vn.omem

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi

object Restore {
    /** Tải các phần từ Drive, ghép lại, lưu vào Download/OMem. */
    fun run(ctx: Context, g: Group) {
        if (Build.VERSION.SDK_INT >= 29) runQ(ctx, g) else runLegacy(ctx, g)
    }

    /** Android 7–9: ghi thẳng vào thư mục Download/OMem (cần quyền ghi bộ nhớ). */
    private fun runLegacy(ctx: Context, g: Group) {
        val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OMem")
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("Không tạo được thư mục Download/OMem (chưa cấp quyền bộ nhớ?)")
        val name = g.head.name.replace('/', '_')
        var f = java.io.File(dir, name)
        if (f.exists()) {
            val dot = name.lastIndexOf('.')
            val base = if (dot > 0) name.substring(0, dot) else name
            val ext = if (dot > 0) name.substring(dot) else ""
            var i = 1
            while (f.exists()) { f = java.io.File(dir, "$base ($i)$ext"); i++ }
        }
        try {
            f.outputStream().use { out -> g.parts.forEach { DriveHelper.download(ctx, it.acct, it.driveId, out) } }
        } catch (ex: Exception) {
            f.delete(); throw ex
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(g.head.mime), null)
    }

    @RequiresApi(29)
    private fun runQ(ctx: Context, g: Group) {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, g.head.name)
            put(MediaStore.Downloads.MIME_TYPE, g.head.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/OMem")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        try {
            ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                g.parts.forEach { DriveHelper.download(ctx, it.acct, it.driveId, out) }
            }
            cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
            ctx.contentResolver.update(uri, cv, null, null)
        } catch (ex: Exception) {
            ctx.contentResolver.delete(uri, null, null); throw ex
        }
    }

    /** Tải tệp từ Drive về bộ nhớ đệm để xem trong app (dùng lại nếu đã tải). */
    fun toCache(ctx: Context, g: Group): java.io.File {
        val dir = java.io.File(ctx.cacheDir, "view").apply { mkdirs() }
        val f = java.io.File(dir, "${g.head.grp}_${g.head.name}".replace('/', '_'))
        if (f.exists() && f.length() == g.head.size) return f
        val tmp = java.io.File(dir, f.name + ".tmp")
        try {
            tmp.outputStream().use { out -> g.parts.forEach { DriveHelper.download(ctx, it.acct, it.driveId, out) } }
            f.delete(); tmp.renameTo(f)
        } catch (e: Exception) { tmp.delete(); throw e }
        // Giữ bộ nhớ đệm dưới ~400 MB: xóa các tệp cũ nhất
        var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
        dir.listFiles()?.filter { it != f }?.sortedBy { it.lastModified() }?.forEach {
            if (total > 400L * 1024 * 1024) { total -= it.length(); it.delete() }
        }
        return f
    }
}
