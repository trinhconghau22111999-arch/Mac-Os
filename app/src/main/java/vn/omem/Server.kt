package vn.omem

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Acc(val email: String, val name: String, val enabled: Boolean, val limit: Long, val usage: Long, val ok: Boolean) {
    val free get() = (limit - usage).coerceAtLeast(0)
}

data class Entry(
    val id: String, val grp: String, val part: Int, val parts: Int, val name: String, val mime: String,
    val size: Long, val acct: String, val driveId: String, val hasThumb: Boolean, val syncedAt: String
)

/** Một tệp (có thể gồm nhiều phần). */
data class Group(val head: Entry, val parts: List<Entry>)

// Nói chuyện với server (các đường dẫn /api/omem/..., có khóa x-omem-key).
object Server {
    private fun call(c: Context, method: String, path: String, body: JSONObject? = null): ByteArray {
        val conn = URL(Store.serverUrl(c).trim().trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20000; conn.readTimeout = 90000
        conn.setRequestProperty("x-omem-key", Store.apiKey(c))
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = conn.responseCode
        val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw Exception("Server $code: ${String(bytes).take(200)}")
        return bytes
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun accounts(c: Context): List<Acc> {
        val arr = JSONObject(String(call(c, "GET", "/api/omem/accounts"))).getJSONArray("accounts")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val q = o.optJSONObject("quota")
            Acc(
                o.getString("email"), o.optString("name", o.getString("email")), o.optBoolean("enabled"),
                if (q == null) 0L else if (q.isNull("limit")) Long.MAX_VALUE / 16 else q.getLong("limit"),
                q?.optLong("usage", 0L) ?: 0L, q != null
            )
        }
    }

    /** Lấy danh sách tài khoản; tài khoản nào chưa bật (kể cả tài khoản mới thêm bên server) thì tự bật luôn, tối đa MAX_ACCOUNTS. */
    fun accountsAutoEnabled(c: Context): List<Acc> {
        val list = accounts(c)
        var room = MAX_ACCOUNTS - list.count { it.enabled }
        var changed = false
        for (a in list) {
            if (room <= 0) break
            if (!a.enabled) {
                if (runCatching { setEnabled(c, a.email, true) }.isSuccess) { room--; changed = true }
            }
        }
        return if (changed) accounts(c) else list
    }

    fun setEnabled(c: Context, email: String, on: Boolean) {
        if (on) call(c, "POST", "/api/omem/accounts", JSONObject().put("email", email))
        else call(c, "DELETE", "/api/omem/accounts?email=${enc(email)}")
    }

    /** Access token ngắn hạn: (token, số giây còn hiệu lực). */
    fun token(c: Context, email: String): Pair<String, Long> {
        val j = JSONObject(String(call(c, "GET", "/api/omem/token?email=${enc(email)}")))
        return j.getString("accessToken") to j.optLong("expiresIn", 2700)
    }

    fun existing(c: Context, keys: List<String>): Set<String> {
        val out = mutableSetOf<String>()
        keys.chunked(100).forEach { chunk ->
            val arr = JSONObject(String(call(c, "POST", "/api/omem/check", JSONObject().put("keys", JSONArray(chunk))))).getJSONArray("existing")
            for (i in 0 until arr.length()) out += arr.getString(i)
        }
        return out
    }

    fun record(c: Context, o: JSONObject) { call(c, "POST", "/api/omem/files", o) }

    fun deleteGroup(c: Context, grp: String) { call(c, "DELETE", "/api/omem/files?grp=${enc(grp)}") }

    fun library(c: Context): List<Group> {
        val arr = JSONObject(String(call(c, "GET", "/api/omem/files"))).getJSONArray("files")
        val all = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getString("id"), o.getString("grp"), o.optInt("part"), o.optInt("parts", 1), o.optString("name"),
                o.optString("mimeType", "application/octet-stream"), o.optLong("size"), o.getString("accountEmail"),
                o.getString("driveFileId"), o.optBoolean("hasThumb"), o.optString("syncedAt"))
        }
        return all.groupBy { it.grp }.values.mapNotNull { g ->
            val sorted = g.sortedBy { it.part }
            sorted.firstOrNull { it.part == 0 }?.let { Group(it, sorted) }
        }.sortedByDescending { it.head.syncedAt }
    }

    fun thumbnail(c: Context, id: String): ByteArray? = try { call(c, "GET", "/api/omem/thumbnail?id=${enc(id)}") } catch (e: Exception) { null }
}
