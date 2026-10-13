package vn.omem

import android.content.Context
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import com.google.api.services.drive.model.File as DriveFile

/** Chỉ dùng quyền drive.file: app chỉ thấy tệp do chính app tạo ra. */
object DriveHelper {
    /** Mỗi request lấy access token mới nhất từ server (tự làm mới khi sắp hết hạn). */
    fun service(c: Context, email: String): Drive {
        val init = HttpRequestInitializer { req -> req.headers.authorization = "Bearer " + Tokens.get(c, email) }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), init)
            .setApplicationName("OMem").build()
    }

    @Synchronized
    private fun folder(c: Context, email: String): String {   // đồng bộ để tải song song không tạo trùng thư mục OMem
        Store.folder(c, email)?.let { return it }
        val svc = service(c, email)
        val found = svc.files().list()
            .setQ("name='OMem' and mimeType='application/vnd.google-apps.folder' and trashed=false")
            .setFields("files(id)").execute().files
        val id = if (found.isNotEmpty()) found[0].id else svc.files().create(
            DriveFile().apply { name = "OMem"; mimeType = "application/vnd.google-apps.folder" }
        ).setFields("id").execute().id
        Store.setFolder(c, email, id)
        return id
    }

    fun upload(
        c: Context, email: String, driveName: String, mime: String, len: Long, data: InputStream,
        props: Map<String, String>
    ): String {
        val svc = service(c, email)
        val meta = DriveFile().apply {
            name = driveName
            parents = listOf(folder(c, email))
            appProperties = props   // giúp dựng lại chỉ mục từ Drive nếu mất database
        }
        val content = InputStreamContent(mime, data).setLength(len)
        val req = svc.files().create(meta, content).setFields("id")
        // Tệp nhỏ (ảnh…) gửi một lần cho nhanh; tệp lớn mới chia khối 8 MB
        if (len <= 8L * 1024 * 1024) req.mediaHttpUploader.isDirectUploadEnabled = true
        else req.mediaHttpUploader.chunkSize = 8 * 1024 * 1024
        return req.execute().id
    }

    fun download(c: Context, email: String, id: String, out: OutputStream) {
        val safe = object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() { flush() }   // không đóng luồng giữa các phần
        }
        service(c, email).files().get(id).executeMediaAndDownloadTo(safe)
    }

    fun delete(c: Context, email: String, id: String) {
        runCatching { service(c, email).files().delete(id).execute() }
    }
}

/** Đọc tối đa n byte rồi báo hết, không đóng luồng gốc. */
class LimitedInputStream(private val s: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val r = s.read(); if (r >= 0) left--; return r
    }
    override fun read(b: ByteArray, o: Int, l: Int): Int {
        if (left <= 0) return -1
        val r = s.read(b, o, minOf(l.toLong(), left).toInt()); if (r > 0) left -= r; return r
    }
    override fun close() {}
}

object Tokens {
    private val cache = HashMap<String, Pair<String, Long>>()

    @Synchronized
    fun get(c: Context, email: String): String {
        val now = System.currentTimeMillis()
        cache[email]?.let { if (it.second - now > 300_000) return it.first }
        val (t, sec) = Server.token(c, email)
        cache[email] = t to (now + sec * 1000)
        return t
    }
}
