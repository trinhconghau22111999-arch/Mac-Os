package com.desktopmini.launcher

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.webkit.ScriptHandler

// Cac lop du lieu nho cua trinh duyet (tach tu BrowserHost.kt).

internal class Tab(var webView: WebView, val button: TextView, val holder: View = button) {
    var title: String = "Tab mới"
    var url: String = ""
    var desktop: Boolean = false
    var loadedAt: Long = 0L
    var paused: Boolean = false      // WebView dang bi tam dung (tab nen khong phat media)
    var playing: Boolean = false     // dang phat nhac/video
    var discarded: Boolean = false   // da giai phong WebView, chi con nho URL
    var lastUsed: Long = System.currentTimeMillis()
    var state: Bundle? = null        // lich su Back/Forward luu lai khi tab bi giai phong
}

// Giu 2 script chay luc trang bat dau tai (mo link 1 cham + chan quang cao YouTube) de goi lai khi doi cai dat.
internal class PageScripts {
    var oneTap: ScriptHandler? = null
    var yt: ScriptHandler? = null
}

internal class DlRow(val bar: ProgressBar, val status: TextView, val folder: TextView)
