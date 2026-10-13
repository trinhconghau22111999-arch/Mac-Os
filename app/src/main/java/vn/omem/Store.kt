package vn.omem

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

const val DEFAULT_SERVER = "https://iosluutruanhvideo.vercel.app"
const val DEFAULT_KEY = "528b8c32c1ab8887065ce736e3ff323c1100fd85e9af5018"
const val MAX_ACCOUNTS = 10

/** Cài đặt cục bộ. Danh sách tài khoản và thư viện nằm trên server. */
object Store {
    private fun p(c: Context) = c.getSharedPreferences("omem", Context.MODE_PRIVATE)

    // Địa chỉ server và khóa được điền sẵn trong code, không còn nhập trong app.
    @Suppress("UNUSED_PARAMETER") fun serverUrl(c: Context) = DEFAULT_SERVER
    @Suppress("UNUSED_PARAMETER") fun apiKey(c: Context) = DEFAULT_KEY

    fun wifiOnly(c: Context) = p(c).getBoolean("wifi", false)
    fun setWifiOnly(c: Context, v: Boolean) = p(c).edit().putBoolean("wifi", v).apply()
    fun auto(c: Context) = p(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()
    /** Khoá bộ nhớ đệm ảnh xem trước: lưu ở bộ nhớ riêng của app (không bị hệ thống/“Xóa bộ nhớ đệm” dọn mất) */
    fun lockCache(c: Context) = p(c).getBoolean("lockcache", true)
    fun setLockCache(c: Context, v: Boolean) = p(c).edit().putBoolean("lockcache", v).apply()
    fun lastRun(c: Context): String = p(c).getString("last_run", "") ?: ""
    fun setLastRun(c: Context, text: String) {
        val t = java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.getDefault()).format(java.util.Date())
        p(c).edit().putString("last_run", "$t – $text").apply()
    }
    fun folder(c: Context, email: String): String? = p(c).getString("folder_$email", null)
    fun setFolder(c: Context, email: String, id: String) = p(c).edit().putString("folder_$email", id).apply()
}

/** Nhớ tệp nào trên máy đã sao lưu xong để lần quét sau bỏ qua nhanh. */
class DoneDb(c: Context) : SQLiteOpenHelper(c.applicationContext, "done.db", null, 1) {
    override fun onCreate(d: SQLiteDatabase) { d.execSQL("CREATE TABLE done(k TEXT PRIMARY KEY)") }
    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}
    fun isDone(k: String) = readableDatabase.rawQuery("SELECT 1 FROM done WHERE k=?", arrayOf(k)).use { it.moveToFirst() }
    fun all(): Set<String> = readableDatabase.rawQuery("SELECT k FROM done", null).use { c -> val r = HashSet<String>(); while (c.moveToNext()) r += c.getString(0); r }
    fun markDone(k: String) { writableDatabase.execSQL("INSERT OR IGNORE INTO done VALUES(?)", arrayOf(k)) }
}
