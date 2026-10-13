package vn.omem

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Một tệp trên máy (từ MediaStore). */
data class Item(val id: Long, val name: String, val size: Long, val mime: String, val modified: Long) {
    val key get() = "$id:$modified"
    val dedupe get() = "${name}__$size"
    val isVideo get() = mime.startsWith("video/")
    val isImage get() = mime.startsWith("image/")
}

/** Trạng thái sao lưu dùng chung giữa worker (ghi) và giao diện (đọc). */
object SyncState {
    /** Các tệp (theo MediaStore id) đang được tải lên ngay lúc này. */
    val uploading = MutableStateFlow<Set<Long>>(emptySet())
    fun start(id: Long) = uploading.update { it + id }
    fun end(id: Long) = uploading.update { it - id }
}

object MediaScan {
    /** Quét tệp trên máy (bỏ tệp đang ghi dở). mediaOnly = chỉ ảnh và video. Mới nhất trước. */
    fun scan(ctx: Context, mediaOnly: Boolean = false): List<Item> {
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE
        )
        // IS_PENDING=0: bỏ qua tệp mà ứng dụng (vd. Camera) còn đang ghi dở, lần quét sau sẽ lấy
        var sel = "${MediaStore.Files.FileColumns.SIZE}>0 AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL"
        if (Build.VERSION.SDK_INT >= 29) sel += " AND ${MediaStore.MediaColumns.IS_PENDING}=0"
        if (mediaOnly) sel += " AND (${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'image/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'video/%')"
        val out = mutableListOf<Item>()
        ctx.contentResolver.query(uri, proj, sel, null, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                out += Item(id, it.getString(1) ?: "file_$id", it.getLong(2), it.getString(4) ?: "application/octet-stream", it.getLong(3))
            }
        }
        return out
    }

    /** Ảnh/video trên máy chưa có trong thư viện Ổ mềm (kể cả đang tải lên). */
    fun notBackedUp(ctx: Context, library: List<Group>): List<Item> {
        val onServer = library.mapTo(HashSet()) { "${it.head.name}__${it.head.size}" }
        val done = DoneDb(ctx).all()
        return scan(ctx, mediaOnly = true).filter { it.key !in done && it.dedupe !in onServer }
    }
}
