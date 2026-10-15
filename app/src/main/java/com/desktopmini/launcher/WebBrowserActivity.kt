package com.desktopmini.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Màn hình riêng của trình duyệt Webandr (dự phòng, ví dụ mở từ ngoài). Trong Desktop Mini, \"Duyệt web\" chạy nhúng
 * trong cửa sổ bằng cùng một lõi [BrowserHost], nên hai bên có chung thẻ, dấu sao, mật khẩu...
 */
class WebBrowserActivity : Activity() {
    private var host: BrowserHost? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val h = BrowserHost(this) { finish() }
        host = h
        h.start(intent?.dataString)
        setContentView(h.root)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.dataString?.let { host?.openUrl(it) }
    }

    override fun onPause() { super.onPause(); host?.onPause() }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); host?.onTrimMemory(level) }
    override fun onDestroy() { host?.destroy(); host = null; super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (host?.back() != true) super.onBackPressed() }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        host?.onConfigurationChanged(newConfig)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (host?.handleActivityResult(requestCode, resultCode, data) != true) super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        host?.handlePermissionsResult(requestCode, permissions, grantResults)
    }
}
