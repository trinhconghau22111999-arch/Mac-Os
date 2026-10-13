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
        "init" -> { Scheduler.apply(ctx); ok() }
        "state" -> state(ctx)
        "thumb" -> {
            val raw = Server.thumbnail(ctx, a.getString("id"))
            img(raw?.let { Base64.encodeToString(it, Base64.NO_WRAP) }, sniff(raw ?: ByteArray(0)))
        }
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
                try { Server.deleteGroup(ctx, gs.getString(i)); n++ } catch (e: Exception) { err = e.message ?: "lỗi" }
            }
            ok().put("done", n).put("total", gs.length()).put("err", err)
        }
        "now" -> { Scheduler.now(ctx); ok() }
        "set" -> {
            val v = a.getBoolean("v")
            if (a.getString("k") == "auto") Store.setAuto(ctx, v) else Store.setWifiOnly(ctx, v)
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
        return ok()
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
            .put("battery", pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true)
            .put("needAll", Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
    }
}
