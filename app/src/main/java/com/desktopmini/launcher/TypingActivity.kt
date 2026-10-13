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
 * Màn hình riêng cũ của "Gõ văn bản" (Desktop Mini giờ mở app này trong cửa sổ, xem WordHost + MainActivity).
 * Giữ lại để mở toàn màn hình khi cần; dùng chung phần lõi WordHost.
 */
class TypingActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var host: WordHost
    private val REQ_FILE = 1001

    private fun backToDesktop() {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        } catch (_: Exception) {}
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF2B579A.toInt()      // xanh Word
        window.navigationBarColor = Color.WHITE
        web = WebView(this)
        val root = FrameLayout(this).apply { fitsSystemWindows = true }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        host = WordHost(this, web, REQ_FILE) { backToDesktop() }
        host.setup()
        host.load()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!host.handleResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    /** Rời app (về Desktop, mở app khác, tắt màn hình): lưu ngay nội dung đang gõ. */
    override fun onPause() {
        super.onPause()
        host.save()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { host.back() }

    override fun onDestroy() {
        host.destroy()
        try { (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }
}
