package com.desktopmini.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.print.PdfPrint
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * App con "Gõ văn bản": trình soạn thảo kiểu Word (chép từ repo Word), chạy offline.
 * So với bản gốc đã tối ưu:
 *  - nạp trang qua WebViewAssetLoader (https://appassets...) thay cho file:// -> tắt allowFileAccess, lưu trữ tách biệt, an toàn hơn;
 *  - link chỉ mở ra ngoài khi là http/https/mailto/tel (không mở scheme lạ như intent:);
 *  - giải mã base64, ghi tệp, đọc PDF chạy ở luồng nền, không làm đơ giao diện;
 *  - tự lưu nội dung khi rời app (onPause);
 *  - nút ✕ / Back quay về màn hình Desktop Mini.
 */
class TypingActivity : Activity() {
    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val REQ_FILE = 1001

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private fun safeName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    inner class Bridge {
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String) {
            saveBytes(name, mime, Base64.decode(base64, Base64.DEFAULT))   // đã ở luồng nền
        }

        @JavascriptInterface
        fun savePdf(name: String, size: String, orientation: String) {
            runOnUiThread { exportPdf(name, size, orientation) }
        }

        @JavascriptInterface
        fun shareFile(name: String, mime: String, base64: String, target: String) {
            doShare(name, mime, Base64.decode(base64, Base64.DEFAULT), target)   // giải mã + ghi tệp ở luồng nền
        }

        @JavascriptInterface
        fun setClipboard(text: String, html: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = if (html.isNotEmpty()) ClipData.newHtmlText("Gõ văn bản", text, html) else ClipData.newPlainText("Gõ văn bản", text)
                cm.setPrimaryClip(clip)
            }
        }

        @JavascriptInterface
        fun getClipboard(): String {
            val out = AtomicReference("{}")
            val latch = CountDownLatch(1)
            runOnUiThread {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val item = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                    if (item != null) {
                        val o = JSONObject()
                        o.put("text", item.coerceToText(this@TypingActivity)?.toString() ?: "")
                        o.put("html", item.htmlText ?: "")
                        out.set(o.toString())
                    }
                } catch (_: Exception) {}
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
            return out.get()
        }

        @JavascriptInterface
        fun speak(text: String, lang: String) { runOnUiThread { startSpeaking(text, lang) } }

        @JavascriptInterface
        fun stopSpeak() { runOnUiThread { tts?.stop() } }

        /** Nút ✕ trong trang: về màn hình Desktop Mini (trang đã tự lưu trước đó). */
        @JavascriptInterface
        fun goHome() { runOnUiThread { backToDesktop() } }

        @JavascriptInterface
        fun print() {
            runOnUiThread {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                pm.print("Document", web.createPrintDocumentAdapter("Document"), PrintAttributes.Builder().build())
            }
        }

        @JavascriptInterface
        fun openExternal(url: String) { runOnUiThread { openLinkOutside(Uri.parse(url)) } }
    }

    private fun backToDesktop() {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        } catch (_: Exception) {}
        finish()
    }

    /** Chỉ mở link web / thư / điện thoại; scheme lạ (intent:, file:, ...) bị từ chối. */
    private fun openLinkOutside(u: Uri) {
        if (u.scheme in listOf("http", "https", "mailto", "tel")) {
            try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
        } else {
            Toast.makeText(this, "Không mở được liên kết này", Toast.LENGTH_SHORT).show()
        }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private fun notifyTtsDone() {
        runOnUiThread { web.evaluateJavascript("window.onTtsDone && window.onTtsDone()", null) }
    }

    private fun startSpeaking(text: String, lang: String) {
        val doSpeak = {
            val engine = tts!!
            val loc = Locale.forLanguageTag(lang)
            val r = engine.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Thiết bị chưa có giọng đọc cho ngôn ngữ này, dùng giọng mặc định", Toast.LENGTH_LONG).show()
            }
            val max = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
            val chunks = ArrayList<String>()
            var cur = StringBuilder()
            for (piece in text.split(SPLIT_SENTENCE)) {
                if (cur.length + piece.length > max && cur.isNotEmpty()) { chunks.add(cur.toString()); cur = StringBuilder() }
                if (piece.length > max) piece.chunked(max).forEach { chunks.add(it) } else cur.append(piece)
            }
            if (cur.isNotBlank()) chunks.add(cur.toString())
            if (chunks.isEmpty()) notifyTtsDone()
            engine.stop()
            chunks.forEachIndexed { i, c ->
                engine.speak(c, TextToSpeech.QUEUE_ADD, null, if (i == chunks.size - 1) "last" else "part$i")
            }
        }
        if (tts != null && ttsReady) { doSpeak(); return }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == "last") notifyTtsDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { notifyTtsDone() }
                    override fun onStop(id: String?, interrupted: Boolean) { notifyTtsDone() }
                })
                runOnUiThread { doSpeak() }
            } else {
                Toast.makeText(this, "Không khởi tạo được giọng đọc", Toast.LENGTH_LONG).show()
                notifyTtsDone()
            }
        }
    }

    private fun toast(msg: String, long: Boolean = true) {
        runOnUiThread { Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() }
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray) {
        try {
            val safe = safeName(name)
            if (Build.VERSION.SDK_INT >= 29) {
                val rel = Environment.DIRECTORY_DOWNLOADS + "/GoVanBan/"
                val col = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                // Lưu đè nếu tệp cùng tên (do app tạo) đã có, tránh sinh "Document1 (1).docx"
                var existing: Uri? = null
                contentResolver.query(
                    col, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(safe, rel), null
                )?.use { c -> if (c.moveToFirst()) existing = ContentUris.withAppendedId(col, c.getLong(0)) }
                var done = false
                existing?.let { u ->
                    try {
                        contentResolver.openOutputStream(u, "wt")!!.use { it.write(bytes) }
                        done = true
                    } catch (_: Exception) {}
                }
                if (!done) {
                    val v = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safe)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GoVanBan")
                    }
                    val uri = contentResolver.insert(col, v)!!
                    contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                }
            } else {
                val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "GoVanBan").also { it.mkdirs() }
                File(dir, safe).writeBytes(bytes)
            }
            val where = if (Build.VERSION.SDK_INT >= 29) "Download/GoVanBan" else "Android/data/$packageName/files/Download/GoVanBan"
            toast("Đã lưu $safe vào $where")
        } catch (e: Exception) {
            toast("Lỗi lưu file: ${e.message}")
        }
    }

    private fun exportPdf(name: String, size: String, orientation: String) {
        try {
            var media = when (size) {
                "Letter" -> PrintAttributes.MediaSize.NA_LETTER
                "Legal" -> PrintAttributes.MediaSize.NA_LEGAL
                else -> PrintAttributes.MediaSize.ISO_A4
            }
            media = if (orientation == "landscape") media.asLandscape() else media.asPortrait()
            val attrs = PrintAttributes.Builder()
                .setMediaSize(media)
                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build()
            val tmp = File(cacheDir, "export.pdf")
            val adapter = web.createPrintDocumentAdapter("Document")
            PdfPrint(attrs).print(adapter, tmp) { ok, err ->
                if (ok) {
                    // đọc tệp PDF (có thể vài chục MB) và ghi ra Tải xuống ở luồng nền
                    Thread {
                        try { saveBytes(name, "application/pdf", tmp.readBytes()) } finally { tmp.delete() }
                    }.start()
                } else {
                    toast("Không tạo được PDF: ${err ?: "lỗi không rõ"}")
                }
            }
        } catch (e: Throwable) {
            toast("Không tạo được PDF: ${e.message}")
        }
    }

    /** Chạy ở luồng nền: ghi tệp tạm; chỉ phần mở app chia sẻ chạy trên luồng giao diện. */
    private fun doShare(name: String, mime: String, bytes: ByteArray, target: String) {
        try {
            val safe = safeName(name)
            val dir = File(cacheDir, "share").also { it.mkdirs() }
            val f = File(dir, safe)
            f.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val (pkg, label) = when (target) {
                "gmail" -> "com.google.android.gm" to "Gmail"
                "drive" -> "com.google.android.apps.docs" to "Google Drive"
                else -> "com.zing.zalo" to "Zalo"
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, safe)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread {
                try {
                    startActivity(Intent(intent).setPackage(pkg))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "Chưa cài $label — chọn ứng dụng khác để chia sẻ", Toast.LENGTH_LONG).show()
                    startActivity(Intent.createChooser(intent, "Chia sẻ qua"))
                }
            }
        } catch (e: Exception) {
            toast("Lỗi chia sẻ: ${e.message}")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF2B579A.toInt()      // xanh Word
        window.navigationBarColor = Color.WHITE
        web = WebView(this)
        val root = FrameLayout(this).apply { fitsSystemWindows = true }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false                      // đã dùng AssetLoader nên không cần file://
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                openLinkOutside(u)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@TypingActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.confirm() }.show()
                return true
            }
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@TypingActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Hủy") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                val input = EditText(this@TypingActivity).apply { setText(defaultValue ?: "") }
                AlertDialog.Builder(this@TypingActivity).setMessage(message).setView(input)
                    .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("Hủy") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = cb
                val accept = params.acceptTypes.joinToString(",")
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (accept.contains("image/") && !accept.contains(".")) "image/*" else "*/*"
                }
                return try {
                    startActivityForResult(Intent.createChooser(intent, "Chọn file"), REQ_FILE); true
                } catch (e: Exception) {
                    filePathCallback = null; false
                }
            }
        }
        web.loadUrl("https://appassets.androidplatform.net/assets/word/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            filePathCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            filePathCallback = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    /** Rời app (về Desktop, mở app khác, tắt màn hình): lưu ngay nội dung đang gõ. */
    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("try{saveNow()}catch(e){}", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){var g=document.getElementById('genericDialog');if(g){g.remove();return 1;}" +
            "var b=document.getElementById('backstage');if(b&&!b.hidden){closeBackstage();return 1;}return 0;})()"
        ) { r -> if (r != "1") finish() }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private val SPLIT_SENTENCE = Regex("(?<=[.!?\\n])")
    }
}
