package com.desktopmini.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val HOME = "https://www.google.com"
private const val REQ_MEDIA = 2101
private const val REQ_STORAGE = 2102
private const val REQ_FILE = 2103
private const val REQ_ACCOUNT = 2104
private const val REQ_VOICE = 2105
private const val REQ_VOICE_PERM = 2106

private const val COLOR_BAR = 0xFF2B1B4A.toInt()
private const val COLOR_TAB_BG = 0xFFE9E3F5.toInt()
private const val COLOR_TAB_ON = 0xFFFFFFFF.toInt()
private const val COLOR_TAB_TEXT = 0xFF5A4A7A.toInt()

@Suppress("DEPRECATION")
/**
 * Trình duyệt Webandr đầy đủ (thẻ, dấu sao, dịch, chụp màn hình, tải xuống, tách đôi, mật khẩu...).
 * Chạy được theo hai kiểu: nhúng trong cửa sổ "Duyệt web" của Desktop Mini (MainActivity) hoặc trong màn hình riêng (WebBrowserActivity).
 * Mọi thứ cần quyền Activity (cửa sổ, xin quyền, mở màn hình chọn tệp...) đi qua [act].
 */
class BrowserHost(private val act: Activity, private val onClose: () -> Unit) : ContextWrapper(act) {

    private val window: android.view.Window get() = act.window
    private fun runOnUiThread(r: () -> Unit) { act.runOnUiThread { r() } }
    private fun finish() { onClose() }
    private fun startActivityForResult(i: Intent, code: Int) { act.startActivityForResult(i, code) }
    private fun requestPermissions(p: Array<String>, code: Int) { act.requestPermissions(p, code) }
    private val RESULT_OK = Activity.RESULT_OK

    /** Khung giao diện của trình duyệt (dùng sau khi gọi [start]). */
    val root: FrameLayout get() = rootFrame

    private class Tab(var webView: WebView, val button: TextView, val holder: View = button) {
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
    private class PageScripts {
        var oneTap: ScriptHandler? = null
        var yt: ScriptHandler? = null
    }

    private val tabs = mutableListOf<Tab>()
    private var active: Tab? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var rootFrame: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var urlBar: EditText
    private lateinit var bookmarkBtn: android.widget.ImageView
    private lateinit var progress: ProgressBar
    private lateinit var qrOverlay: FrameLayout
    private var qrWeb: WebView? = null
    private lateinit var camOverlay: FrameLayout
    private var camWeb: WebView? = null
    private lateinit var splitRoot: LinearLayout
    private lateinit var splitPaneHolder: FrameLayout

    private var oneTapOn = true      // true = chạm vào link là mở (mặc định, như trình duyệt thường)
    private var adSkipOn = true      // tự bấm "Bỏ qua" quảng cáo YouTube
    private var lastConfigChangeAt = 0L

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermRequest: PermissionRequest? = null

    private val desktopUa =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    // User-Agent that cua WebView tren may (giu nguyen "; wv", "Version/4.0"), khong gia, khong che.
    private val mobileUa: String by lazy { WebSettings.getDefaultUserAgent(this) }

    // Ngoai le: site chan WebView ("Truy cap tam thoi bi han che") -> CHI rieng host nay dung UA da cat
    // "; wv"/"Version/4.0" va khong gui X-Requested-With. Moi trang khac dung UA that.
    private val uaExemptHosts = listOf("github.com")
    private val headerOff = java.util.WeakHashMap<WebView, Boolean>()
    private val cleanedUa: String by lazy { mobileUa.replace("; wv)", ")").replace(" Version/4.0", "") }

    // Tra ve true neu vua DOI UA/header (noi goi phai tu tai lai URL de ap dung).
    private fun applyUaFor(wv: WebView, url: String?): Boolean {
        var changed = false
        val host = try { Uri.parse(url ?: "").host } catch (e: Exception) { null }
        val st = wv.settings
        if (st.userAgentString == desktopUa) return false
        val ex = host != null && uaExemptHosts.any { host == it || host.endsWith(".$it") }
        val want = if (ex) cleanedUa else mobileUa
        if (st.userAgentString != want) { st.userAgentString = want; changed = true }
        if (headerOff[wv] != ex) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(st, if (ex) emptySet() else setOf("*"))
                headerOff[wv] = ex
            } catch (e: Exception) { }
        }
        return changed
    }

    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
        "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Nen bo goc + hieu ung cham dung chung cho cac nut tren thanh dia chi.
    private fun rippleBg(fill: Int, radiusDp: Int): android.graphics.drawable.Drawable {
        val r = dp(radiusDp).toFloat()
        val shape = android.graphics.drawable.GradientDrawable().apply { setColor(fill); cornerRadius = r }
        val mask = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = r }
        return android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x44FFFFFF), shape, mask)
    }

    // ====================== Vong doi ======================

    fun start(startUrl: String?) {
        prefs = getSharedPreferences("webandr", Context.MODE_PRIVATE)
        oneTapOn = prefs.getBoolean("oneTap", true)
        adSkipOn = prefs.getBoolean("adSkip", true)
        buildUi()
        ui.postDelayed(sweep, SWEEP_MS)
        // Da tung dung Truyen tep -> mo ngam de tu ket noi lai.
        if (prefs.getBoolean("camUsed", false)) ensureCamWeb()

        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        }

        val saved = try { JSONArray(prefs.getString("tabs", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until saved.length()) newTab(saved.optString(i), false)
        if (startUrl != null) newTab(startUrl) else if (tabs.isEmpty()) newTab(HOME)
        else selectTab(tabs[prefs.getInt("activeIdx", 0).coerceIn(0, tabs.size - 1)])
    }

    /** Mở một địa chỉ trong thẻ mới. */
    fun openUrl(u: String) { newTab(u) }
    fun reload() { active?.webView?.reload() }
    fun forward() { active?.webView?.let { if (it.canGoForward()) it.goForward() } }
    fun currentUrl(): String = active?.let { displayUrl(it.url) } ?: ""

    fun onPause() {
        val arr = JSONArray()
        for (t in tabs) if (t.url.startsWith("http")) arr.put(displayUrl(t.url))
        prefs.edit().putString("tabs", arr.toString())
            .putInt("activeIdx", tabs.indexOf(active).coerceAtLeast(0)).apply()
        val ed = prefs.edit()
        for ((n, t) in splitTabs) if (n in KEEP_URL_SERVICES && t.url.startsWith("http")) ed.putString("split_$n", t.url)
        ed.apply()
        CookieManager.getInstance().flush()
    }

    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val now = System.currentTimeMillis()
            for (t in tabs.toList()) if (t !== active && !t.playing && now - t.lastUsed > 60_000L) discardTab(t)
        }
    }

    fun destroy() {
        ui.removeCallbacks(sweep)
        for (t in tabs) if (!t.discarded) t.webView.destroy()
        qrWeb?.destroy()
        camWeb?.destroy()
        for (t in splitTabs.values) t.webView.destroy()
        synchronized(recordings) {
            for (o in recordings.values) try { o.close() } catch (e: Exception) { }
            recordings.clear()
        }
    }

    /** Bấm Back: true = trình duyệt đã xử lý (đóng bảng, lùi trang, đóng thẻ...), false = hết việc để làm, nơi gọi nên đóng trình duyệt. */
    fun back(): Boolean {
        when {
            customView != null -> hideCustomView()
            dlScrim.visibility == View.VISIBLE -> closeDownloads()
            drawerScrim.visibility == View.VISIBLE -> closeDrawer()
            qrOverlay.visibility == View.VISIBLE -> qrOverlay.visibility = View.GONE
            camOverlay.visibility == View.VISIBLE -> {
                // Trang Truyen tep tu dong trinh sua anh truoc; neu khong co gi de dong thi dong ca overlay.
                val w = camWeb
                if (w == null) camOverlay.visibility = View.GONE
                else w.evaluateJavascript("(window.__camBack?window.__camBack():false)") { r ->
                    if (r != "true") camOverlay.visibility = View.GONE
                }
            }
            splitService != null && splitTab?.webView?.hasFocus() == true &&
                    splitTab?.webView?.canGoBack() == true -> splitTab?.webView?.goBack()
            active?.webView?.canGoBack() == true -> active?.webView?.goBack()
            tabs.size > 1 -> active?.let { closeTab(it) }
            else -> return false
        }
        return true
    }

    // ====================== Giao dien ======================

    private fun buildUi() {
        rootFrame = FrameLayout(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Thanh dia chi
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COLOR_BAR)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        // Nut icon vector tron, co hieu ung cham (ripple), dong bo mau.
        fun iconBtn(res: Int, desc: String, tint: Int = Color.WHITE, fill: Int = 0x00000000,
                    onClick: (View) -> Unit): android.widget.ImageView = android.widget.ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            contentDescription = desc
            background = rippleBg(fill, 20)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(2); rightMargin = dp(2) }
            setOnClickListener { onClick(it) }
        }
        bar.addView(iconBtn(R.drawable.ic_menu, "Menu") { showMenu(it) })
        bar.addView(iconBtn(R.drawable.ic_back, "Quay lại") { active?.webView?.let { w -> if (w.canGoBack()) w.goBack() } })
        bar.addView(iconBtn(R.drawable.ic_forward, "Tiến tới") { active?.webView?.let { w -> if (w.canGoForward()) w.goForward() } })
        bar.addView(iconBtn(R.drawable.ic_reload, "Tải lại") { active?.webView?.reload() })

        urlBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(0xFF1E1633.toInt())
            setHintTextColor(0xFF8A80A6.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF2EEFA.toInt())
                cornerRadius = dp(20).toFloat()
            }
            setPadding(dp(16), dp(6), dp(16), dp(6))
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            hint = "Tìm kiếm hoặc nhập địa chỉ"
            setOnEditorActionListener { _, actionId, ev ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadInput(text.toString())
                    hideKeyboard()
                    true
                } else false
            }
        }
        bar.addView(urlBar, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        bar.addView(iconBtn(R.drawable.ic_shot, "Chụp màn hình") { takeScreenshot() })
        bookmarkBtn = iconBtn(R.drawable.ic_star_off, "Dấu trang") { toggleBookmark() }
        bar.addView(bookmarkBtn)
        bar.addView(iconBtn(R.drawable.ic_download, "Tải xuống") { openDownloads() })
        bar.addView(iconBtn(R.drawable.ic_close, "Màn hình chính", tint = 0xFFFF9A9A.toInt(), fill = 0x33FF6B6B) { goHome() }.apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
        })
        col.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        col.addView(progress, LinearLayout.LayoutParams(MATCH, dp(3)))

        // Thanh tab
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_TAB_BG)
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val plus = TextView(this).apply {
            text = "＋"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(COLOR_BAR)
            setOnClickListener { newTab(HOME) }
        }
        // Nut + nam ngay ben phai tab cuoi cung (cuon cung cac tab).
        val tabsAndPlus = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabsAndPlus.addView(tabBar, LinearLayout.LayoutParams(WRAP, MATCH))
        tabsAndPlus.addView(plus, LinearLayout.LayoutParams(dp(44), MATCH))
        scroll.addView(tabsAndPlus, FrameLayout.LayoutParams(WRAP, MATCH))
        tabRow.addView(scroll, LinearLayout.LayoutParams(0, MATCH, 1f))
        val bmBtn = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_stars_nested)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(7), dp(9), dp(7))
            contentDescription = "Dấu trang"
            setOnClickListener { openDrawer() }
        }
        tabRow.addView(bmBtn, LinearLayout.LayoutParams(dp(44), MATCH))
        col.addView(tabRow, LinearLayout.LayoutParams(MATCH, dp(38)))

        // Noi dung
        content = FrameLayout(this)
        splitPaneHolder = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        splitRoot = LinearLayout(this)
        splitRoot.addView(splitPaneHolder, LinearLayout.LayoutParams(MATCH, 0, 3f))
        splitRoot.addView(content, LinearLayout.LayoutParams(MATCH, 0, 7f))
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sideScroll = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(COLOR_TAB_BG)
        }
        val sideCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((name, _) in splitUrls) {
            val spec = sideIconSpec[name]
            val b = android.widget.ImageView(this).apply {
                if (spec != null) {
                    setImageResource(spec.first)
                    setPadding(dp(spec.third), dp(spec.third), dp(spec.third), dp(spec.third))
                }
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                contentDescription = name
                setOnClickListener { toggleSplit(name); updateSideIcons() }
                setOnLongClickListener { Toast.makeText(this@BrowserHost, name, Toast.LENGTH_SHORT).show(); true }
            }
            sideIcons[name] = b
            sideCol.addView(b, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(6); bottomMargin = dp(6)
            })
        }
        // Nut dich (khong phai app con, khong chia doi): dich trang hien tai sang tieng Viet / tra ve goc.
        val tb = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_translate)
            setPadding(dp(5), dp(5), dp(5), dp(5))
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "Dịch sang tiếng Việt / về ngôn ngữ gốc"
            setOnClickListener { toggleTranslate() }
            setOnLongClickListener {
                Toast.makeText(this@BrowserHost, "Dịch sang tiếng Việt / về ngôn ngữ gốc", Toast.LENGTH_SHORT).show()
                true
            }
        }
        translateBtn = tb
        sideCol.addView(tb, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14); bottomMargin = dp(6)
        })
        updateTranslateBtn()
        updateSideIcons()
        sideScroll.addView(sideCol, FrameLayout.LayoutParams(WRAP, WRAP))
        body.addView(sideScroll, LinearLayout.LayoutParams(dp(40), MATCH))
        body.addView(splitRoot, LinearLayout.LayoutParams(0, MATCH, 1f))
        col.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        qrOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF1A1030.toInt())
            visibility = View.GONE
        }
        content.addView(qrOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        camOverlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF1A1030.toInt())
            visibility = View.GONE
        }
        content.addView(camOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        rootFrame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        buildDrawer()
        buildDownloads()
        applySplitLayout()
        /* Cửa sổ đổi cỡ (kéo cửa sổ, xoay màn hình): ngang thì chia trái-phải, dọc thì chia trên-dưới theo hình dạng khung */
        rootFrame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            val wide = (r - l) > (b - t); val wasWide = (orr - ol) > (ob - ot)
            if (wide != wasWide) applySplitLayout()
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        lastConfigChangeAt = System.currentTimeMillis()
        applySplitLayout()
    }

    // ====================== Dich trang ======================

    private var translateBtn: View? = null
    private val translateSuffix = ".translate.goog"

    // Thanh dia chi, dau trang, lich su luon dung dia chi goc, khong phai ban .translate.goog.
    private fun displayUrl(u: String): String = fromTranslateUrl(u) ?: u

    private fun updateTranslateBtn() {
        val b = translateBtn ?: return
        val on = active?.url?.contains(translateSuffix) == true
        b.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0xFF1A73E8.toInt())
            setStroke(if (on) dp(3) else dp(1), if (on) COLOR_BAR else 0x33000000)
        }
    }

    // https://a.b.com/x?y=1  ->  https://a-b-com.translate.goog/x?y=1&_x_tr_sl=auto&_x_tr_tl=vi&_x_tr_hl=vi
    private fun toTranslateUrl(u: String): String? {
        val uri = Uri.parse(u)
        val host = uri.host ?: return null
        val h = host.replace("-", "--").replace(".", "-") + translateSuffix
        val path = uri.encodedPath ?: "/"
        val q = uri.encodedQuery
        val tr = "_x_tr_sl=auto&_x_tr_tl=vi&_x_tr_hl=vi&_x_tr_pto=wapp"
        val frag = uri.encodedFragment?.let { "#" + it } ?: ""
        return "https://" + h + path + "?" + (if (!q.isNullOrEmpty()) "$q&" else "") + tr + frag
    }

    // Nguoc lai: tra ve dia chi trang goc; null neu trang khong phai ban dich.
    private fun fromTranslateUrl(u: String): String? {
        val uri = Uri.parse(u)
        val host = uri.host ?: return null
        if (!host.endsWith(translateSuffix)) return null
        val core = host.removeSuffix(translateSuffix)
        val orig = core.replace("--", "\u0000").replace("-", ".").replace("\u0000", "-")
        val q = uri.encodedQuery?.split("&")?.filter { !it.startsWith("_x_tr_") && it.isNotEmpty() }?.joinToString("&")
        return "https://" + orig + (uri.encodedPath ?: "/") + (if (!q.isNullOrEmpty()) "?$q" else "")
    }

    private fun toggleTranslate() {
        val t = active ?: return
        val u = t.url
        if (!u.startsWith("http")) {
            Toast.makeText(this, "Chưa có trang để dịch", Toast.LENGTH_SHORT).show()
            return
        }
        val orig = fromTranslateUrl(u)
        if (orig != null) {
            go(t.webView, orig)
            Toast.makeText(this, "Về ngôn ngữ gốc", Toast.LENGTH_SHORT).show()
            return
        }
        val host = Uri.parse(u).host
        if (host != null && isPhishingHost(host)) {
            Toast.makeText(this, "Trang này nằm trong danh sách chặn", Toast.LENGTH_SHORT).show()
            return
        }
        val tr = toTranslateUrl(u)
        if (tr == null) {
            Toast.makeText(this, "Không dịch được trang này", Toast.LENGTH_SHORT).show()
            return
        }
        go(t.webView, tr)
        Toast.makeText(this, "Đang dịch sang tiếng Việt…", Toast.LENGTH_SHORT).show()
    }

    // Ve man hinh chinh Android; app van chay nen, mo lai se giu nguyen cac tab.
    private fun goHome() {
        // Nhúng trong Desktop Mini: đóng cửa sổ Duyệt web; màn hình riêng: đóng màn hình, về Desktop Mini
        finish()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
    }

    // ====================== Tab ======================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(): WebView {
        val wv = WebView(this)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.allowFileAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        s.userAgentString = mobileUa
        wv.addJavascriptInterface(VoiceBridge(), "AndroidVoice")

        wv.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            try {
                val req = DownloadManager.Request(Uri.parse(dlUrl))
                val fileName = URLUtil.guessFileName(dlUrl, contentDisposition, mimeType)
                req.setMimeType(mimeType)
                req.addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(dlUrl)?.let { req.addRequestHeader("Cookie", it) }
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dlId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                addDownloadRecord(dlId, fileName)
                Toast.makeText(this, "Đang tải: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Không tải được: " + e.message, Toast.LENGTH_LONG).show()
            }
        }

        attachPasswordBridge(wv)
        attachAcctBridge(wv)
        installPageScripts(wv)

        // Khi TAT "mo link 1 cham": nhan giu 1 link de chon mo (thay cho chuot phai o ban may tinh).
        wv.setOnLongClickListener { v ->
            if (oneTapOn) return@setOnLongClickListener false
            val view = v as WebView
            val type = view.hitTestResult.type
            if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                val handler = Handler(Looper.getMainLooper()) { m ->
                    val u = m.data?.getString("url")
                    if (!u.isNullOrEmpty()) showLinkDialog(u)
                    true
                }
                view.requestFocusNodeHref(handler.obtainMessage())
                true
            } else false
        }
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?, select: Boolean = true): Tab {
        val wv = createConfiguredWebView()

        val btn = TextView(this).apply {
            text = "Tab mới"
            textSize = 13f
            maxLines = 1
            maxWidth = dp(140)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        val closeX = TextView(this).apply {
            text = "✕"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(COLOR_TAB_TEXT)
            setPadding(dp(8), 0, dp(10), 0)
        }
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btn, LinearLayout.LayoutParams(WRAP, MATCH))
            addView(closeX, LinearLayout.LayoutParams(WRAP, MATCH))
        }
        val tab = Tab(wv, btn, holder)
        btn.setOnClickListener { selectTab(tab) }
        closeX.setOnClickListener { closeTab(tab) }
        tabBar.addView(holder, LinearLayout.LayoutParams(WRAP, MATCH))

        wv.visibility = View.GONE
        content.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        tabs.add(tab)
        setupClients(tab)

        if (!url.isNullOrEmpty()) go(wv, url)
        if (select) selectTab(tab)
        return tab
    }

    private fun selectTab(tab: Tab) {
        val prev = active
        if (tab.discarded) reviveTab(tab)
        active = tab
        val now = System.currentTimeMillis()
        if (prev != null && prev !== tab) prev.lastUsed = now
        tab.lastUsed = now
        if (tab.paused) { tab.webView.onResume(); tab.paused = false }
        for (t in tabs) {
            val on = t === tab
            if (!t.discarded) t.webView.visibility = if (on) View.VISIBLE else View.GONE
            t.holder.setBackgroundColor(if (on) COLOR_TAB_ON else Color.TRANSPARENT)
            t.button.setTextColor(if (on) COLOR_BAR else COLOR_TAB_TEXT)
        }
        urlBar.setText(displayUrl(tab.url))
        progress.visibility = View.GONE
        updateBookmarkBtn()
        updateTranslateBtn()
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        tabBar.removeView(tab.holder)
        if (!tab.discarded) {
            val w = tab.webView
            content.removeView(w)
            w.post { w.destroy() }
        }
        if (tabs.isEmpty()) {
            newTab(HOME)
        } else if (active === tab) {
            selectTab(tabs[idx.coerceAtMost(tabs.size - 1)])
        }
    }

    private val blockedAppSchemes = setOf(
        "tg", "telegram", "zalo", "zaloapp", "zalopay", "fb", "fb-messenger", "fb-messenger-share",
        "fbapi", "fbauth2", "fbconnect", "fb-lite"
    )
    private val blockedAppPackages = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram",
        "com.zing.zalo", "com.facebook.katana", "com.facebook.orca", "com.facebook.lite", "com.facebook.mlite"
    )

    private fun isAdHost(host: String): Boolean =
        adHosts.any { host == it || host.endsWith(".$it") }

    private fun setupClients(tab: Tab) {
        tab.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme ?: return false
                if (scheme == "webandr-back") {
                    view?.let { go(it, HOME) }
                    return true
                }
                if (scheme == "webandr-allow") {
                    u.getQueryParameter("h")?.let { phishingAllow.add(it.lowercase()) }
                    u.getQueryParameter("u")?.let { target -> view?.let { go(it, target) } }
                    return true
                }
                // Nho zoom theo trang: ap muc zoom da luu cua trang dich truoc khi no tai.
                if ((scheme == "http" || scheme == "https") && view != null) {
                    applyInitialScale(view, u.toString())
                    // Doi UA giua chung lam WebView huy lan chuyen trang -> tu tai lai URL voi UA moi.
                    if (request.method == "GET" && applyUaFor(view, u.toString())) {
                        val target = u.toString()
                        view.post { view.loadUrl(target) }
                        return true
                    }
                }
                if (scheme in listOf("http", "https", "about", "data", "blob", "file", "javascript")) return false
                // Telegram / Zalo / Facebook: luon o lai ban web, khong nhay sang app cai tren may.
                if (scheme in blockedAppSchemes) return true
                try {
                    val i = if (scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    else Intent(Intent.ACTION_VIEW, u)
                    if (scheme == "intent" && i.`package` in blockedAppPackages) {
                        i.getStringExtra("browser_fallback_url")
                            ?.takeIf { it.startsWith("http") }
                            ?.let { fb -> view?.let { go(it, fb) } }
                        return true
                    }
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    startActivity(i)
                } catch (e: Exception) {
                    // khong co app xu ly - bo qua
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val req = request ?: return null
                val host = req.url?.host ?: return null
                if (req.isForMainFrame && isPhishingHost(host) && !phishingAllow.contains(host.lowercase())) {
                    return phishingResponse(host, req.url.toString())
                }
                if (isAdHost(host)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return null
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                onUserScale(tab, newScale)
            }

            // Tien trinh hien thi bi he thong giet (het RAM / trang qua nang): neu khong xu ly thi ca app bi van.
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val v = view ?: return true
                CrashLog.log(act, "WebView mất tiến trình hiển thị (didCrash=" + (detail?.didCrash()) + ")", RuntimeException(tab.url))
                runOnUiThread { recoverCrashedWebView(tab, v) }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                tab.loadedAt = 0L
                tab.url = url ?: ""
                if (tab === active) {
                    urlBar.setText(displayUrl(tab.url))
                    progress.visibility = View.VISIBLE
                    updateTranslateBtn()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                tab.url = url ?: ""
                if (tab === active) {
                    urlBar.setText(displayUrl(tab.url))
                    progress.visibility = View.GONE
                    updateBookmarkBtn()
                }
                tab.loadedAt = System.currentTimeMillis()
                if (url != null && url.startsWith("http")) view?.evaluateJavascript(VOICE_HOOK_JS, null)   // nối nút mic (Google, YouTube...)
                if (!docStartScriptSupported && url != null && url.startsWith("http")) {
                    view?.evaluateJavascript(PW_JS, null)
                    // WebView cu khong ho tro script luc bat dau tai: chen sau khi tai xong.
                    view?.evaluateJavascript(oneTapScript(oneTapOn), null)
                    if (isYoutubeUrl(url)) view?.evaluateJavascript(ytScript(adSkipOn), null)
                }
                if (url != null && url.startsWith("http") && !url.contains(translateSuffix)) addHistory(view?.title ?: url, url)
                        if (url != null && url.contains("accounts.google.com")) view?.evaluateJavascript(ACCT_HOOK_JS, null)
                val pendingEm = pendingGoogleEmail
                if (pendingEm != null && url != null) {
                    if (url.contains("accounts.google.com")) {
                        view?.evaluateJavascript(googleFillScript(pendingEm), null)
                    } else if (url.startsWith("http") && !url.contains("google.com")) {
                        pendingGoogleEmail = null
                    }
                }
            }
        }

        tab.webView.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                tab.title = title ?: tab.url
                tab.button.text = tab.title
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (tab === active) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }

            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean {
                // Khi TAT "mo link 1 cham": chan moi cua so/tab moi do trang tu mo (quang cao pop-up).
                if (!oneTapOn) return false
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val t = newTab(null)
                transport.webView = t.webView
                msg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                tabs.firstOrNull { it.webView === window }?.let { closeTab(it) }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                rootFrame.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                return openFileChooser(filePathCallback, fileChooserParams)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                runOnUiThread {
                    val needed = mutableListOf<String>()
                    for (r in req.resources) {
                        if (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE) needed.add(Manifest.permission.CAMERA)
                        if (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE) needed.add(Manifest.permission.RECORD_AUDIO)
                    }
                    val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (missing.isEmpty()) {
                        req.grant(req.resources)
                    } else {
                        pendingPermRequest = req
                        requestPermissions(missing.toTypedArray(), REQ_MEDIA)
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        rootFrame.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    @Deprecated("Deprecated in Java")
    // Chon tai khoan Google cua he thong (khong can cap quyen). Neu may khong tra ve duoc
    // thi chuyen sang o nhap email. Sau do mo trang dang nhap va tu dien email vao o.
    private var pendingGoogleEmail: String? = null

    @Suppress("DEPRECATION")
    private fun pickGoogleAccount() {
        try {
            val types = arrayOf("com.google")
            val i = if (Build.VERSION.SDK_INT >= 26)
                android.accounts.AccountManager.newChooseAccountIntent(null, null, types, null, null, null, null)
            else
                android.accounts.AccountManager.newChooseAccountIntent(null, null, types, true, null, null, null, null)
            startActivityForResult(i, REQ_ACCOUNT)
        } catch (e: Throwable) {
            showGoogleEmailInput()
        }
    }

    // Cau noi: khi bam vao o email o trang dang nhap Google thi app hien danh sach tai khoan de chon.
    private val ACCT_HOOK_JS = "(function(){if(window.__wbAcct)return;window.__wbAcct=1;" +
        "function hook(){var i=document.querySelector('input[type=email],#identifierId,input[name=identifier]');" +
        "if(!i||i.__wbh)return;i.__wbh=1;" +
        "var f=function(){if(i.value)return;try{AndroidAcct.postMessage('pick')}catch(e){}};" +
        "i.addEventListener('focus',f);i.addEventListener('click',f);" +
        "if(!window.__wbAuto){window.__wbAuto=1;setTimeout(f,600)}}" +
        "hook();setInterval(hook,500)})()"

    private var acctPromptShowing = false

    private fun attachAcctBridge(wv: WebView) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(wv, "AndroidAcct", setOf("https://accounts.google.com")) { view, message, _, isMainFrame, _ ->
                    if (isMainFrame && message.data == "pick" && pendingGoogleEmail == null) runOnUiThread { showGoogleAccountPicker(view) }
                }
            }
        } catch (e: Exception) { }
    }

    // Email Google da dung (lan chon/nhap truoc) + ten tai khoan Google da luu mat khau.
    private fun googleEmails(): List<String> {
        val out = linkedSetOf<String>()
        try {
            val a = JSONArray(prefs.getString("gmails", "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) { }
        for (o in loadPw()) {
            if (o.optString("origin").contains("google.com") && o.optString("username").contains("@"))
                out.add(o.optString("username"))
        }
        return out.toList()
    }

    private fun rememberGmail(email: String) {
        val l = googleEmails().toMutableList()
        l.remove(email)
        l.add(0, email)
        prefs.edit().putString("gmails", JSONArray(l.take(10)).toString()).apply()
    }

    private fun showGoogleAccountPicker(wv: WebView) {
        if (acctPromptShowing) return
        val emails = googleEmails()
        val items = emails + listOf("Chọn tài khoản trên máy…", "Nhập email khác…")
        AlertDialog.Builder(this)
            .setTitle("Chọn tài khoản Google")
            .setItems(items.toTypedArray()) { _, i ->
                when {
                    i < emails.size -> {
                        rememberGmail(emails[i])
                        wv.evaluateJavascript(googleFillScript(emails[i]), null)
                    }
                    i == emails.size -> pickGoogleAccount()
                    else -> showGoogleEmailInput()
                }
            }
            .setNegativeButton("Đóng", null)
            .setOnDismissListener { acctPromptShowing = false }
            .show()
        acctPromptShowing = true
    }

    private fun showGoogleEmailInput() {
        val et = EditText(this).apply {
            hint = "ten@gmail.com"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        AlertDialog.Builder(this)
            .setTitle("Nhập email Google")
            .setMessage("Không lấy được danh sách tài khoản của máy. Nhập email để điền sẵn vào trang đăng nhập.")
            .setView(et)
            .setPositiveButton("Đăng nhập") { _, _ ->
                val v = et.text.toString().trim()
                if (v.contains("@")) startGoogleSignIn(v)
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun startGoogleSignIn(email: String) {
        rememberGmail(email)
        pendingGoogleEmail = email
        val e = Uri.encode(email)
        val url = "https://accounts.google.com/ServiceLogin?Email=" + e + "&login_hint=" + e +
                "&continue=" + Uri.encode("https://www.google.com/")
        active?.webView?.let { go(it, url) }
    }

    // Dien email vao o dinh danh cua trang dang nhap Google roi bam "Tiep theo".
    private fun googleFillScript(email: String): String =
        "(function(e){var t=0;var iv=setInterval(function(){" +
        "var i=document.querySelector('input[type=email],#identifierId,input[name=identifier]');" +
        "if(i){clearInterval(iv);if(i.value!==e){var st=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;" +
        "st.call(i,e);i.dispatchEvent(new Event('input',{bubbles:true}));i.dispatchEvent(new Event('change',{bubbles:true}));" +
        "setTimeout(function(){var b=document.querySelector('#identifierNext button,#identifierNext');if(b)b.click()},500)}}" +
        "else if(++t>40)clearInterval(iv)},250)})(" + JSONObject.quote(email) + ")"

    /** true = kết quả này thuộc trình duyệt. */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQ_ACCOUNT) {
            val email = data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (resultCode == RESULT_OK && !email.isNullOrEmpty()) startGoogleSignIn(email)
            else showGoogleEmailInput()
            return true
        }
        if (requestCode == REQ_VOICE) {
            if (resultCode == RESULT_OK) {
                val q = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!q.isNullOrBlank()) active?.webView?.let { go(it, voiceSearchUrl(q)) }
            }
            return true
        }
        if (requestCode == REQ_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return true
        }
        return false
    }

    fun handlePermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == REQ_VOICE_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchVoiceRecognizer()
            else Toast.makeText(this, "Cần cấp quyền micro để dùng tìm kiếm bằng giọng nói", Toast.LENGTH_SHORT).show()
            return true
        }
        if (requestCode == REQ_MEDIA) {
            val req = pendingPermRequest
            pendingPermRequest = null
            if (req != null) {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    req.grant(req.resources)
                } else {
                    req.deny()
                }
            }
            return true
        }
        return requestCode == REQ_STORAGE
    }

    // ====================== Tim kiem bang giong noi (hoc tu repo Y-utubecuatoi) ======================

    private inner class VoiceBridge {
        @JavascriptInterface fun startVoiceSearch() { runOnUiThread { launchVoiceRecognizer() } }
    }

    private fun launchVoiceRecognizer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE_PERM)
            return
        }
        val it = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm")
        }
        try { startActivityForResult(it, REQ_VOICE) }
        catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, "Máy chưa có ứng dụng nhận dạng giọng nói (cần ứng dụng Google). Hãy cài hoặc cập nhật Google từ CH Play", Toast.LENGTH_LONG).show()
        }
    }

    // Đang ở YouTube thì tìm trên YouTube, còn lại tìm trên Google.
    private fun voiceSearchUrl(q: String): String =
        if (isYoutubeUrl(active?.url)) "https://www.youtube.com/results?search_query=" + Uri.encode(q)
        else "https://www.google.com/search?q=" + Uri.encode(q)

    // ====================== Dieu huong / Menu ======================

    private fun loadInput(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file://") ||
                    t.startsWith("about:") -> t
            !t.contains(" ") && t.contains(".") -> "https://$t"
            else -> "https://www.google.com/search?q=" + Uri.encode(t)
        }
        active?.webView?.let { go(it, url) }
    }

    private fun showMenu(anchor: View) {
        val tab = active
        val popup = PopupMenu(this, anchor)
        val m = popup.menu
        m.add(0, 6, 0, "Chế độ máy tính").apply {
            isCheckable = true
            isChecked = tab?.desktop == true
        }
        m.add(0, 4, 0, "Lịch sử")
        m.add(0, 7, 0, "Xoá lịch sử")
        m.add(0, 3, 0, "Dấu trang")
        m.add(0, 5, 0, "QR Remote Input")
        m.add(0, 9, 0, "Truyền tệp")
        m.add(0, 11, 0, "Chặn web đen / lừa đảo")
        m.add(0, 13, 0, "Chặn quảng cáo YouTube (tự bấm Bỏ qua)").apply {
            isCheckable = true
            isChecked = adSkipOn
        }
        m.add(0, 14, 0, "Mở link bằng 1 chạm").apply {
            isCheckable = true
            isChecked = oneTapOn
        }
        // Mục "Đặt lại zoom trang này" (id 15) đã ẩn khỏi menu; chức năng resetZoom() vẫn giữ nguyên.
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                3 -> showList("Dấu trang", "bookmarks", false)
                4 -> showList("Lịch sử", "history", true)
                5 -> toggleQr()
                6 -> toggleDesktop()
                7 -> clearData()
                9 -> toggleCam()
                11 -> showPhishingManager()
                13 -> toggleAdSkip()
                14 -> toggleOneTap()
                15 -> resetZoom()
            }
            true
        }
        popup.show()
    }

    private fun toggleDesktop() {
        val tab = active ?: return
        tab.desktop = !tab.desktop
        tab.webView.settings.userAgentString = if (tab.desktop) desktopUa else mobileUa
        tab.webView.reload()
    }

    private fun clearData() {
        active?.webView?.clearCache(true)
        prefs.edit().remove("history").apply()
        Toast.makeText(this, "Đã xoá lịch sử", Toast.LENGTH_SHORT).show()
    }

    // ====================== Dau trang / Lich su ======================

    private fun readList(key: String): MutableList<Array<String>> {
        val out = mutableListOf<Array<String>>()
        try {
            val arr = JSONArray(prefs.getString(key, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(arrayOf(o.optString("title"), o.optString("url")))
            }
        } catch (e: Exception) {
            // du lieu hong - coi nhu rong
        }
        return out
    }

    private fun writeList(key: String, list: List<Array<String>>) {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().put("title", e[0]).put("url", e[1]))
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private fun addHistory(title: String, url: String) {
        val list = readList("history")
        if (list.isNotEmpty() && list[0][1] == url) return
        list.add(0, arrayOf(title, url))
        writeList("history", if (list.size > 50) list.subList(0, 50) else list)
    }

    private fun toggleBookmark() {
        val tab = active ?: return
        if (!tab.url.startsWith("http")) return
        val list = readList("bookmarks")
        val curUrl = displayUrl(tab.url)
        val existing = list.indexOfFirst { it[1] == curUrl }
        if (existing >= 0) {
            list.removeAt(existing)
            Toast.makeText(this, "Đã bỏ dấu trang", Toast.LENGTH_SHORT).show()
        } else {
            list.add(0, arrayOf(tab.title, curUrl))
            Toast.makeText(this, "Đã thêm dấu trang", Toast.LENGTH_SHORT).show()
        }
        writeList("bookmarks", list)
        updateBookmarkBtn()
    }

    private fun updateBookmarkBtn() {
        val u = displayUrl(active?.url ?: "")
        val on = u.isNotEmpty() && readList("bookmarks").any { it[1] == u }
        bookmarkBtn.setImageResource(if (on) R.drawable.ic_star_on else R.drawable.ic_star_off)
        bookmarkBtn.setColorFilter(if (on) 0xFFFFC107.toInt() else Color.WHITE)
    }

    // ====================== Trang tai xuong trong app ======================

    private class DlRow(val bar: ProgressBar, val status: TextView, val folder: TextView)

    private lateinit var dlScrim: FrameLayout
    private lateinit var dlPanel: LinearLayout
    private lateinit var dlRows: LinearLayout
    private lateinit var dlEmpty: TextView
    private val dlRowViews = mutableMapOf<Long, DlRow>()
    private val dlHandler = Handler(Looper.getMainLooper())
    private val dlTick = object : Runnable {
        override fun run() {
            refreshDownloads()
            if (dlScrim.visibility == View.VISIBLE) dlHandler.postDelayed(this, 500)
        }
    }

    private fun readDownloads(): MutableList<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        try {
            val arr = JSONArray(prefs.getString("downloads", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Pair(o.getLong("id"), o.optString("name")))
            }
        } catch (e: Exception) { }
        return out
    }

    private fun addDownloadRecord(id: Long, name: String) {
        val list = readDownloads()
        list.add(0, Pair(id, name))
        val arr = JSONArray()
        for (e in list.take(50)) arr.put(JSONObject().put("id", e.first).put("name", e.second))
        prefs.edit().putString("downloads", arr.toString()).apply()
        if (dlScrim.visibility == View.VISIBLE) populateDownloads()
    }

    private fun buildDownloads() {
        dlScrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            setOnClickListener { closeDownloads() }
        }
        dlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setOnClickListener { }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(COLOR_BAR)
            setPadding(dp(16), 0, 0, 0)
        }
        header.addView(TextView(this).apply {
            text = "⬇  Tải xuống"
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(WRAP, MATCH))
        // Nut xoa lich su tai xuong (ben phai tieu de). Chi xoa danh sach, KHONG xoa tep da tai.
        header.addView(TextView(this).apply {
            text = "🗑 Xoá"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { confirmClearDownloads() }
        }, LinearLayout.LayoutParams(WRAP, MATCH))
        header.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f))
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { closeDownloads() }
        }, LinearLayout.LayoutParams(dp(56), MATCH))
        dlPanel.addView(header, LinearLayout.LayoutParams(MATCH, dp(52)))
        dlEmpty = TextView(this).apply {
            text = "Chưa có tệp nào được tải xuống."
            textSize = 16f
            setTextColor(0xFF666666.toInt())
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        dlPanel.addView(dlEmpty, LinearLayout.LayoutParams(MATCH, WRAP))
        val sc = android.widget.ScrollView(this)
        dlRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sc.addView(dlRows, FrameLayout.LayoutParams(MATCH, WRAP))
        dlPanel.addView(sc, LinearLayout.LayoutParams(MATCH, 0, 1f))
        dlScrim.addView(dlPanel, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.TOP))
        rootFrame.addView(dlScrim, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun confirmClearDownloads() {
        if (readDownloads().isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Xoá lịch sử tải xuống?")
            .setMessage("Chỉ xoá danh sách này, các tệp đã tải vẫn còn trong máy.")
            .setPositiveButton("Xoá") { _, _ ->
                prefs.edit().remove("downloads").apply()
                populateDownloads()
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun populateDownloads() {
        dlRows.removeAllViews()
        dlRowViews.clear()
        val list = readDownloads()
        dlEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for ((id, name) in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(Color.BLACK)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            left.addView(bar, LinearLayout.LayoutParams(MATCH, dp(10)).apply { topMargin = dp(6) })
            val status = TextView(this).apply {
                text = "Đang chờ…"
                textSize = 14f
                setTextColor(0xFF555555.toInt())
            }
            left.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
            row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
            val folder = TextView(this).apply {
                text = "📂"
                textSize = 28f
                gravity = Gravity.CENTER
                visibility = View.GONE
                setOnClickListener { openDownloadsFolder() }
            }
            row.addView(folder, LinearLayout.LayoutParams(dp(56), dp(56)))
            dlRows.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            dlRowViews[id] = DlRow(bar, status, folder)
        }
        refreshDownloads()
    }

    private fun refreshDownloads() {
        if (dlRowViews.isEmpty()) return
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val seen = mutableSetOf<Long>()
        try {
            dm.query(DownloadManager.Query().setFilterById(*dlRowViews.keys.toLongArray()))?.use { c ->
                val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
                val iSt = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val iGot = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTot = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val r = dlRowViews[id] ?: continue
                    seen.add(id)
                    val got = c.getLong(iGot)
                    val tot = c.getLong(iTot)
                    r.folder.visibility = View.GONE
                    when (c.getInt(iSt)) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            r.bar.visibility = View.GONE
                            r.status.text = "✅ Đã tải xuống"
                            r.status.setTextColor(0xFF2E7D32.toInt())
                            r.folder.visibility = View.VISIBLE
                        }
                        DownloadManager.STATUS_FAILED -> {
                            r.bar.visibility = View.GONE
                            r.status.text = "❌ Tải xuống lỗi"
                            r.status.setTextColor(0xFFC62828.toInt())
                        }
                        DownloadManager.STATUS_PAUSED -> {
                            r.bar.visibility = View.VISIBLE
                            r.status.text = "⏸ Tạm dừng"
                        }
                        DownloadManager.STATUS_PENDING -> {
                            r.bar.visibility = View.VISIBLE
                            r.bar.isIndeterminate = true
                            r.status.text = "Đang chờ…"
                        }
                        else -> {
                            r.bar.visibility = View.VISIBLE
                            if (tot > 0) {
                                val pct = (got * 100 / tot).toInt()
                                r.bar.isIndeterminate = false
                                r.bar.progress = pct
                                r.status.text = "Đang tải $pct%  (" + fmtMb(got) + " / " + fmtMb(tot) + ")"
                            } else {
                                r.bar.isIndeterminate = true
                                r.status.text = "Đang tải… " + fmtMb(got)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        for ((id, r) in dlRowViews) if (id !in seen) {
            r.bar.visibility = View.GONE
            r.folder.visibility = View.GONE
            r.status.text = "Tệp không còn trong danh sách tải"
        }
    }

    private fun fmtMb(b: Long): String = String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)

    private fun openDownloads() {
        val m = resources.displayMetrics
        val landscape = if (rootFrame.width > 0 && rootFrame.height > 0) rootFrame.width > rootFrame.height
            else resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lp = dlPanel.layoutParams as FrameLayout.LayoutParams
        if (landscape) { lp.width = m.widthPixels / 2; lp.height = MATCH; lp.gravity = Gravity.START }
        else { lp.width = MATCH; lp.height = m.heightPixels / 2; lp.gravity = Gravity.TOP }
        dlPanel.layoutParams = lp
        dlScrim.visibility = View.VISIBLE
        populateDownloads()
        dlHandler.removeCallbacks(dlTick)
        dlHandler.postDelayed(dlTick, 500)
    }

    private fun closeDownloads() {
        dlScrim.visibility = View.GONE
        dlHandler.removeCallbacks(dlTick)
    }

    // Nut ben phai muc "Da tai xuong": mo thu muc Download tren may.
    private fun openDownloadsFolder() {
        try {
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload"),
                    "vnd.android.document/directory"
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                Toast.makeText(this, "Không mở được thư mục tải xuống", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun takeScreenshot() {
        val wv = active?.webView ?: return
        if (wv.width <= 0 || wv.height <= 0) return
        try {
            val bmp = Bitmap.createBitmap(wv.width, wv.height, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(bmp))
            val name = "Webandr_" + System.currentTimeMillis() + ".png"
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Webandr")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?: throw Exception("không tạo được tệp")
                contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Webandr")
                dir.mkdirs()
                FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                android.media.MediaScannerConnection.scanFile(this, arrayOf(File(dir, name).absolutePath), arrayOf("image/png"), null) /* để Ảnh/Tệp thấy ngay */
            }
            Toast.makeText(this, "Đã lưu ảnh chụp: Pictures/Webandr/$name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Không chụp được: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun rowAdapter(list: List<Array<String>>): android.widget.BaseAdapter = object : android.widget.BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(position: Int) = list[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val e = list[position]
                val row = LinearLayout(this@BrowserHost).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(10), dp(20), dp(10))
                }
                if (e[0].isNotBlank()) row.addView(TextView(this@BrowserHost).apply {
                    text = e[0]
                    textSize = 15f
                    setTextColor(Color.BLACK)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                })
                row.addView(TextView(this@BrowserHost).apply {
                    text = e[1]
                    textSize = 13f
                    setTextColor(0xFF666666.toInt())
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                })
                return row
            }
    }

    // ====================== Ngan keo dau trang (ben trai) ======================

    private lateinit var drawerScrim: FrameLayout
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerList: android.widget.ListView
    private lateinit var drawerEmpty: TextView

    private fun buildDrawer() {
        val w = dp(300)
        drawerScrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setOnClickListener { }
        }
        drawerPanel.addView(TextView(this).apply {
            text = "🔖  Dấu trang"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_BAR)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }, LinearLayout.LayoutParams(MATCH, dp(48)))
        drawerEmpty = TextView(this).apply {
            text = "Chưa có dấu trang nào.\nBấm ☆ trên thanh trên để thêm."
            setTextColor(0xFF666666.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        drawerPanel.addView(drawerEmpty, LinearLayout.LayoutParams(MATCH, WRAP))
        drawerList = android.widget.ListView(this).apply {
            setOnItemClickListener { _, _, i, _ ->
                val list = readList("bookmarks")
                if (i in list.indices) newTab(list[i][1])
                closeDrawer()
            }
        }
        drawerPanel.addView(drawerList, LinearLayout.LayoutParams(MATCH, 0, 1f))
        drawerScrim.addView(drawerPanel, FrameLayout.LayoutParams(w, MATCH, Gravity.START))
        rootFrame.addView(drawerScrim, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun openDrawer() {
        val list = readList("bookmarks")
        drawerList.adapter = rowAdapter(list)
        drawerEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        drawerScrim.visibility = View.VISIBLE
        drawerPanel.translationX = -dp(300).toFloat()
        drawerPanel.animate().translationX(0f).setDuration(200).start()
    }

    private fun closeDrawer() {
        drawerPanel.animate().translationX(-dp(300).toFloat()).setDuration(180)
            .withEndAction { drawerScrim.visibility = View.GONE }.start()
    }

    private fun showList(title: String, key: String, clearable: Boolean) {
        val list = readList(key)
        if (list.isEmpty()) {
            Toast.makeText(this, "$title: chưa có mục nào", Toast.LENGTH_SHORT).show()
            return
        }
        val adapter = rowAdapter(list)
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setAdapter(adapter) { _, i -> active?.webView?.let { go(it, list[i][1]) } }
            .setNegativeButton("Đóng", null)
        if (clearable) {
            b.setNeutralButton("Xoá hết") { _, _ -> prefs.edit().remove(key).apply() }
        }
        b.show()
    }

    // ====================== QR Remote Input ======================

    private fun toggleQr() {
        if (qrOverlay.visibility == View.VISIBLE) {
            qrOverlay.visibility = View.GONE
            return
        }
        ensureQrWeb()
        camOverlay.visibility = View.GONE
        qrOverlay.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureQrWeb() {
        if (qrWeb != null) return
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.webViewClient = assetClient()
        w.addJavascriptInterface(QrBridge(), "Android")
        w.loadUrl("https://appassets.androidplatform.net/assets/qrremote.html")
        qrWeb = w
        qrOverlay.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private inner class QrBridge {
        @JavascriptInterface
        fun onText(text: String) {
            runOnUiThread { injectText(text) }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { qrOverlay.visibility = View.GONE }
        }

        @JavascriptInterface
        fun copy(text: String) {
            runOnUiThread {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("link", text))
            }
        }
    }

    // Giong desktop: chi giu chu/so/gach duoi/khoang trang.
    private fun sanitizeText(text: String): String =
        text.replace(Regex("[^\\p{L}\\p{N}_\\s]"), "")

    private fun buildInjectionScript(text: String): String {
        val quoted = JSONObject.quote(text)
        return "(function(t){var el=document.activeElement;if(!el)return false;" +
                "try{if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){" +
                "var s=el.selectionStart!=null?el.selectionStart:el.value.length;" +
                "var e=el.selectionEnd!=null?el.selectionEnd:el.value.length;" +
                "el.setRangeText(t,s,e,'end');" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));return true;}" +
                "if(el.isContentEditable){document.execCommand('insertText',false,t);return true;}}" +
                "catch(x){}return false;})(" + quoted + ");"
    }

    private fun injectText(raw: String) {
        val clean = sanitizeText(raw)
        if (clean.isEmpty()) return
        val wv = typingWebView() ?: return
        wv.evaluateJavascript(buildInjectionScript(clean)) {
            // Giong desktop: tu dong nhan Enter sau khi chen.
            sendEnter(wv)
        }
    }

    private fun sendEnter(wv: WebView) {
        wv.requestFocus()
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    // WebView dang giu con tro: pane chia doi neu dang focus, nguoc lai la tab hien tai.
    private fun typingWebView(): WebView? {
        val pane = splitTab?.webView
        if (splitService != null && pane != null && pane.hasFocus()) return pane
        return active?.webView
    }

    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    private fun assetClient(): WebViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val u = request?.url ?: return null
            return assetLoader.shouldInterceptRequest(u)
        }
    }

    // ====================== Truyen tep ======================

    private val recordings = HashMap<String, FileOutputStream>()
    private var recSeq = 0

    private fun camDir(): File {
        val base = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val d = File(base, "TruyenTep")
        d.mkdirs()
        return d
    }

    private fun toggleCam() {
        if (camOverlay.visibility == View.VISIBLE) {
            camOverlay.visibility = View.GONE
            return
        }
        prefs.edit().putBoolean("camUsed", true).apply()
        ensureCamWeb()
        qrOverlay.visibility = View.GONE
        camOverlay.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun ensureCamWeb() {
        if (camWeb != null) return
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = assetClient()
        // Can onShowFileChooser de nut "gui tep" (📤) trong trang Truyen tep chon duoc tep tren dien thoai.
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean = openFileChooser(filePathCallback, fileChooserParams)
        }
        w.addJavascriptInterface(CamBridge(), "AndroidCam")
        w.loadUrl("https://appassets.androidplatform.net/assets/truyentep.html")
        camWeb = w
        camOverlay.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private inner class CamBridge {
        @JavascriptInterface
        fun folder(): String = camDir().absolutePath

        @JavascriptInterface
        fun startFile(name: String): String {
            return try {
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                synchronized(recordings) {
                    val id = "rec" + (++recSeq)
                    recordings[id] = FileOutputStream(File(camDir(), safe))
                    id
                }
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun appendChunk(id: String, b64: String): Boolean {
            return try {
                synchronized(recordings) {
                    val o = recordings[id]
                    if (o == null) {
                        false
                    } else {
                        o.write(Base64.decode(b64, Base64.DEFAULT))
                        true
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun finishFile(id: String): Boolean {
            return try {
                synchronized(recordings) {
                    val o = recordings.remove(id)
                    if (o == null) {
                        false
                    } else {
                        o.flush()
                        o.close()
                        true
                    }
                }
            } catch (e: Exception) {
                false
            }
        }

        // ---- Nhan tep / sua anh tu dien thoai (DataChannel) ----
        @JavascriptInterface
        fun startReceived(name: String): String {
            return try {
                val dir = receivedDir()
                var safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                if (safe.isEmpty() || safe.all { it == '.' }) safe = "tep_" + System.currentTimeMillis()
                if (safe.length > 120) safe = safe.takeLast(120)
                val dot = safe.lastIndexOf('.')
                val stem = if (dot > 0) safe.substring(0, dot) else safe
                val ext = if (dot > 0) safe.substring(dot) else ""
                var f = File(dir, safe)
                var n = 1
                while (f.exists()) {
                    f = File(dir, stem + " (" + n + ")" + ext)
                    n++
                }
                if (!isInReceivedDir(f)) return ""
                synchronized(recordings) {
                    val id = "rec" + (++recSeq)
                    recordings[id] = FileOutputStream(f)
                    JSONObject().put("id", id).put("path", f.absolutePath).toString()
                }
            } catch (e: Exception) {
                ""
            }
        }

        // Huy tep dang nhan do dang: dong luong ghi va xoa tep tam.
        @JavascriptInterface
        fun discardReceived(id: String, path: String) {
            try {
                synchronized(recordings) { recordings.remove(id)?.let { try { it.close() } catch (e: Exception) { } } }
                val f = File(path)
                if (isInReceivedDir(f) && f.isFile) f.delete()
            } catch (e: Exception) {
            }
        }

        // Chep tep da nhan sang Tai xuong/TruyenTep trong luong nen (tep lon khong treo giao dien).
        // Xong goi window.__published(ten, ok) trong trang Truyen tep. Tra ve true neu da bat dau chep.
        @JavascriptInterface
        fun publishDownloadAsync(path: String, mime: String, name: String): Boolean {
            val f = File(path)
            if (!isInReceivedDir(f) || !f.isFile) return false
            val type = if (mime.isNotEmpty()) mime else guessMime(f.name)
            Thread {
                val ok = try { publishToDownloads(f, type) } catch (e: Exception) { false }
                runOnUiThread {
                    camWeb?.evaluateJavascript(
                        "(window.__published&&window.__published(" + JSONObject.quote(name) + "," + ok + "))", null
                    )
                }
            }.start()
            return true
        }

        // Ghi de anh da sua. ext rong = giu nguyen duoi tep; tra ve duong dan moi ("" neu loi).
        @JavascriptInterface
        fun saveEdited(path: String, b64: String, ext: String): String {
            return try {
                val f = File(path)
                if (!isInReceivedDir(f) || !f.isFile) return ""
                val target = if (ext == "jpg" || ext == "png" || ext == "webp")
                    File(f.parentFile, f.nameWithoutExtension + "." + ext) else f
                target.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                if (target.absolutePath != f.absolutePath) f.delete()
                target.absolutePath
            } catch (e: Exception) {
                ""
            }
        }

        // Chep tep da nhan vao thu muc Tai xuong/TruyenTep de mo tu bat ky ung dung nao.
        @JavascriptInterface
        fun publishDownload(path: String, mime: String): Boolean {
            return try {
                val f = File(path)
                if (!isInReceivedDir(f) || !f.isFile) return false
                val type = if (mime.isNotEmpty()) mime else guessMime(f.name)
                publishToDownloads(f, type)
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun toastMsg(msg: String) {
            runOnUiThread { Toast.makeText(this@BrowserHost, msg, Toast.LENGTH_SHORT).show() }
        }

        // Co anh moi ve: dua trang Truyen tep len truoc de hien trinh sua anh.
        @JavascriptInterface
        fun showOverlay() {
            runOnUiThread {
                qrOverlay.visibility = View.GONE
                camOverlay.visibility = View.VISIBLE
            }
        }

        @JavascriptInterface
        fun textEdit(del: Int, ins: String) {
            runOnUiThread { applyTextEdit(del, ins) }
        }

        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { camOverlay.visibility = View.GONE }
        }
    }

    private fun receivedDir(): File {
        val base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
        val d = File(base, "TruyenTep")
        d.mkdirs()
        return d
    }

    private fun isInReceivedDir(f: File): Boolean {
        return try {
            f.canonicalPath.startsWith(receivedDir().canonicalPath + File.separator)
        } catch (e: Exception) {
            false
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun publishToDownloads(src: File, mime: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) publishToDownloadsQ(src, mime) else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TruyenTep")
            dir.mkdirs()
            src.copyTo(File(dir, src.name), overwrite = true)
            true
        }
    }

    @android.annotation.TargetApi(29)
    private fun publishToDownloadsQ(src: File, mime: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TruyenTep")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        val out = contentResolver.openOutputStream(uri) ?: return false
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
        return true
    }

    private fun openFileChooser(
        cb: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?
    ): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        return try {
            startActivityForResult(params!!.createIntent(), REQ_FILE)
            true
        } catch (e: Exception) {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            false
        }
    }

    // Dien thoai gui {del, ins}: xoa `del` ky tu truoc con tro roi chen `ins`.
    private fun applyTextEdit(delRaw: Int, ins: String) {
        val wv = typingWebView() ?: return
        val del = delRaw.coerceIn(0, 1000000)
        if (del == 0 && ins.isEmpty()) return
        if (ins == "\n" && del == 0) {
            sendEnter(wv)
            return
        }
        wv.evaluateJavascript("(" + TEXT_EDIT_FN + ")(" + del + "," + JSONObject.quote(ins) + ");", null)
    }

    // ====================== Mo link 1 cham / Chan quang cao YouTube ======================

    private fun isYoutubeUrl(url: String?): Boolean {
        val h = try { Uri.parse(url ?: return false).host } catch (e: Exception) { null } ?: return false
        return h == "youtube.com" || h.endsWith(".youtube.com") || h == "youtu.be"
    }

    private fun oneTapScript(on: Boolean): String = "window.__wbOneTap=" + on + ";" + ONE_TAP_JS

    private fun ytScript(on: Boolean): String = "window.__wbAdSkip=" + on + ";" + YT_AD_JS

    // Dang ky lai script luc-bat-dau-tai voi cai dat hien tai (cung cho ca iframe).
    private fun installPageScripts(wv: WebView) {
        if (!docStartScriptSupported) return
        val h = (wv.tag as? PageScripts) ?: PageScripts().also { wv.tag = it }
        try { h.oneTap?.remove() } catch (e: Exception) { }
        try { h.yt?.remove() } catch (e: Exception) { }
        h.oneTap = try {
            WebViewCompat.addDocumentStartJavaScript(wv, oneTapScript(oneTapOn), setOf("*"))
        } catch (e: Exception) { null }
        h.yt = try {
            WebViewCompat.addDocumentStartJavaScript(wv, ytScript(adSkipOn), setOf("https://*.youtube.com", "https://youtube.com"))
        } catch (e: Exception) { null }
    }

    private fun allWebViews(): List<WebView> = tabs.filter { !it.discarded }.map { it.webView } + splitTabs.values.map { it.webView }

    // Ap cai dat moi cho moi tab dang mo (trang hien tai nhan co ngay, trang sau nhan qua script).
    private fun applyPageFlags() {
        for (w in allWebViews()) {
            installPageScripts(w)
            w.evaluateJavascript("window.__wbOneTap=" + oneTapOn + ";window.__wbAdSkip=" + adSkipOn + ";", null)
        }
    }

    private fun updateOneTapBtn() { }

    private fun toggleOneTap() {
        oneTapOn = !oneTapOn
        prefs.edit().putBoolean("oneTap", oneTapOn).apply()
        updateOneTapBtn()
        applyPageFlags()
        Toast.makeText(
            this,
            if (oneTapOn) "Mở link 1 chạm: BẬT — chạm vào link là mở"
            else "Mở link 1 chạm: TẮT — chạm vào link sẽ không mở; nhấn giữ link để chọn mở",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun toggleAdSkip() {
        adSkipOn = !adSkipOn
        prefs.edit().putBoolean("adSkip", adSkipOn).apply()
        applyPageFlags()
        Toast.makeText(
            this,
            if (adSkipOn) "Chặn quảng cáo YouTube: BẬT" else "Chặn quảng cáo YouTube: TẮT",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showLinkDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle(if (url.length > 90) url.substring(0, 90) + "…" else url)
            .setItems(arrayOf("Mở trong tab mới", "Mở ở tab này", "Sao chép liên kết")) { _, i ->
                when (i) {
                    0 -> newTab(url)
                    1 -> active?.webView?.let { go(it, url) }
                    2 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("link", url))
                        Toast.makeText(this, "Đã sao chép liên kết", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    // ====================== Nho zoom theo trang ======================

    private var zoomCache: JSONObject? = null
    private val zoomRunnables = HashMap<WebView, Runnable>()

    private fun originOf(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        return try {
            val u = Uri.parse(url)
            val scheme = u.scheme ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = u.host ?: return null
            scheme + "://" + host + (if (u.port != -1) ":" + u.port else "")
        } catch (e: Exception) {
            null
        }
    }

    private fun zoomMap(): JSONObject {
        zoomCache?.let { return it }
        val o = try { JSONObject(prefs.getString("zoomByOrigin", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        zoomCache = o
        return o
    }

    // 0 = mac dinh cua WebView (chua tung zoom trang nay).
    private fun savedScalePercent(url: String): Int {
        val origin = originOf(url) ?: return 0
        val v = zoomMap().optDouble(origin, 0.0)
        return if (v > 0.0) (v * 100).toInt().coerceIn(50, 800) else 0
    }

    private fun applyInitialScale(wv: WebView, url: String) {
        wv.setInitialScale(savedScalePercent(url))
    }

    // Moi lan nguoi dung tai trang theo y minh: ap muc zoom da luu cua trang do roi moi tai.
    private fun go(wv: WebView, url: String) {
        applyInitialScale(wv, url)
        applyUaFor(wv, url)
        wv.loadUrl(url)
    }

    // Nguoi dung chum-zoom: luu lai theo tung trang (bo qua thay doi do bo cuc / xoay man hinh).
    private fun onUserScale(tab: Tab, newScale: Float) {
        val now = System.currentTimeMillis()
        if (tab.loadedAt == 0L || now - tab.loadedAt < 1500 || now - lastConfigChangeAt < 1500) return
        val origin = originOf(tab.url) ?: return
        val wv = tab.webView
        zoomRunnables.remove(wv)?.let { wv.removeCallbacks(it) }
        val r = Runnable {
            zoomRunnables.remove(wv)
            val o = zoomMap()
            if (Math.abs(o.optDouble(origin, 0.0) - newScale) < 0.02) return@Runnable
            o.put(origin, newScale.toDouble())
            if (o.length() > 300) {
                val k = o.keys().next()
                o.remove(k)
            }
            prefs.edit().putString("zoomByOrigin", o.toString()).apply()
        }
        zoomRunnables[wv] = r
        wv.postDelayed(r, 600)
    }

    private fun resetZoom() {
        val tab = active ?: return
        val origin = originOf(tab.url)
        if (origin == null) {
            Toast.makeText(this, "Trang này chưa có zoom riêng", Toast.LENGTH_SHORT).show()
            return
        }
        zoomMap().remove(origin)
        prefs.edit().putString("zoomByOrigin", zoomMap().toString()).apply()
        tab.webView.setInitialScale(0)
        tab.webView.reload()
        Toast.makeText(this, "Đã đặt lại zoom: " + origin, Toast.LENGTH_SHORT).show()
    }

    // ====================== Chia doi man hinh ======================

    private val splitUrls = linkedMapOf(
        "Telegram" to "https://web.telegram.org/k/",
        "Zalo" to "https://chat.zalo.me/",
        "Facebook" to "https://www.facebook.com/?locale=vi_VN",
        "YouTube" to "https://www.youtube.com/?hl=vi&gl=VN&persist_hl=1",
        "Google" to "https://www.google.com/?hl=vi"
    )
    private var splitService: String? = null
    private var splitTab: Tab? = null
    // Moi dich vu co WebView rieng, giu song khi an pane -> nho vi tri/trang, YouTube van phat tiep.
    private val splitTabs = linkedMapOf<String, Tab>()

    // Doc man hinh: pane o tren (3/10). Ngang man hinh: pane ben trai (2/7), giong ban may tinh.
    private fun applySplitLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        splitRoot.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        splitPaneHolder.layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, MATCH, 2f) else LinearLayout.LayoutParams(MATCH, 0, 3f)
        content.layoutParams =
            if (landscape) LinearLayout.LayoutParams(0, MATCH, 5f) else LinearLayout.LayoutParams(MATCH, 0, 7f)
        splitPaneHolder.visibility = if (splitService != null) View.VISIBLE else View.GONE
    }

    // (icon chinh thuc dang vector, mau nen hinh tron, do dem dp)
    private val sideIconSpec = mapOf(
        "Telegram" to Triple(R.drawable.ic_telegram, Color.WHITE, 2),
        "Zalo" to Triple(R.drawable.ic_zalo, 0xFF0068FF.toInt(), 4),
        "Facebook" to Triple(R.drawable.ic_facebook, Color.WHITE, 2),
        "YouTube" to Triple(R.drawable.ic_youtube, Color.WHITE, 5),
        "Google" to Triple(R.drawable.ic_google, Color.WHITE, 6)
    )
    private val sideIcons = mutableMapOf<String, View>()

    // Icon tron theo mau thuong hieu; icon dang chia doi duoc vien dam, cac icon con lai mo di.
    private fun updateSideIcons() {
        for ((n, v) in sideIcons) {
            val spec = sideIconSpec[n] ?: continue
            val active = splitService == n
            v.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(spec.second)
                setStroke(if (active) dp(3) else dp(1), if (active) COLOR_BAR else 0x33000000)
            }
            v.alpha = if (splitService == null || active) 1f else 0.5f
        }
    }

    private fun toggleSplit(service: String) {
        if (splitService == service) exitSplit() else enterSplit(service)
    }

    // YouTube / Google: dong lai chi nho duong dan cu (va chi chay nen khi dang phat video).
    // Telegram / Zalo / Facebook: dong la dong han, mo lai vao tu dau.
    private val KEEP_URL_SERVICES = setOf("YouTube", "Google")

    private fun enterSplit(service: String) {
        val url = splitUrls[service] ?: return
        val prev = splitService
        val t = splitTabs.getOrPut(service) {
            val wv = createConfiguredWebView()
            val nt = Tab(wv, TextView(this))
            // Zalo Web chi mo giao dien chat tren may tinh; UA di dong bi day sang trang tai app.
            if (service == "Zalo") { wv.settings.userAgentString = desktopUa; nt.desktop = true }
            setupClients(nt)
            wv.visibility = View.GONE
            splitPaneHolder.addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
            val saved = if (service in KEEP_URL_SERVICES) prefs.getString("split_$service", null) else null
            go(wv, if (!saved.isNullOrEmpty() && saved.startsWith("http")) saved else url)
            nt
        }
        if (t.paused) { t.webView.onResume(); t.paused = false }
        for ((n, tb) in splitTabs) tb.webView.visibility = if (n == service) View.VISIBLE else View.GONE
        splitTab = t
        splitService = service
        applySplitLayout()
        if (prev != null && prev != service) closeSplitService(prev)
    }

    private fun exitSplit() {
        val svc = splitService ?: return
        splitService = null
        applySplitLayout()
        closeSplitService(svc)
    }

    private fun closeSplitService(svc: String) {
        val t = splitTabs[svc] ?: return
        t.webView.visibility = View.GONE
        if (svc !in KEEP_URL_SERVICES) { dropSplit(svc, false); return }
        // Chi giu lai (chay nen) neu dang phat video/nhac.
        t.webView.evaluateJavascript(PLAY_JS) { r ->
            if (splitTabs[svc] === t && splitService != svc && r != "true") dropSplit(svc, true)
        }
    }

    private fun dropSplit(svc: String, remember: Boolean) {
        val t = splitTabs.remove(svc) ?: return
        val ed = prefs.edit()
        if (remember && t.url.startsWith("http")) ed.putString("split_$svc", t.url) else ed.remove("split_$svc")
        ed.apply()
        if (splitTab === t) splitTab = null
        val wv = t.webView
        zoomRunnables.remove(wv)?.let { wv.removeCallbacks(it) }
        splitPaneHolder.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    // ====================== Tab nen: tam dung / giai phong bo nho ======================

    private val ui = Handler(Looper.getMainLooper())
    private val SWEEP_MS = 3000L
    private val IDLE_TAB_MS = 10 * 60 * 1000L
    private val PLAY_JS = "(function(){try{var m=document.querySelectorAll('video,audio');" +
            "for(var i=0;i<m.length;i++){var e=m[i];if(!e.paused&&!e.ended&&e.readyState>2)return true}}" +
            "catch(x){}return false})()"

    private val sweep = object : Runnable {
        override fun run() {
            try { sweepBackground() } catch (e: Exception) { }
            ui.postDelayed(this, SWEEP_MS)
        }
    }

    private fun sweepBackground() {
        val now = System.currentTimeMillis()
        for (t in tabs.toList()) {
            if (t === active || t.discarded) continue
            if (!t.paused) {
                t.webView.evaluateJavascript(PLAY_JS) { r ->
                    if (!t.discarded && t !== active && !t.paused) {
                        t.playing = r == "true"
                        if (t.playing) t.lastUsed = System.currentTimeMillis()
                        else { t.webView.onPause(); t.paused = true }
                    }
                }
            } else if (now - t.lastUsed > IDLE_TAB_MS) {
                discardTab(t)
            }
        }
        // Dich vu chia doi dang an (YouTube/Google): chi song khi con phat video.
        for ((svc, t) in splitTabs.entries.toList()) {
            if (svc == splitService) continue
            t.webView.evaluateJavascript(PLAY_JS) { r ->
                if (splitTabs[svc] === t && splitService != svc && r != "true") dropSplit(svc, true)
            }
        }
    }

    private fun recoverCrashedWebView(tab: Tab, v: WebView) {
        val svc = splitTabs.entries.firstOrNull { it.value === tab }?.key
        if (svc != null) {
            val wasShown = splitService == svc
            dropSplit(svc, true)
            if (wasShown) enterSplit(svc)
        } else if (tabs.contains(tab) && tab.webView === v && !tab.discarded) {
            zoomRunnables.remove(v)?.let { v.removeCallbacks(it) }
            try { content.removeView(v); v.destroy() } catch (e: Exception) { }
            tab.discarded = true
            tab.paused = false
            tab.playing = false
            tab.state = null
            if (tab === active) selectTab(tab) else tab.button.alpha = 0.55f
        } else {
            return
        }
        Toast.makeText(this, "Trang quá nặng nên bị thu hồi bộ nhớ, đã tải lại", Toast.LENGTH_SHORT).show()
    }

    // Giai phong WebView cua tab khong dung; chi nho URL, bam vao se nap lai dung trang cu.
    private fun discardTab(t: Tab) {
        if (t.discarded || t === active || t.playing || !t.url.startsWith("http")) return
        val wv = t.webView
        zoomRunnables.remove(wv)?.let { wv.removeCallbacks(it) }
        // Luu lich su Back/Forward de nap lai khi bam vao tab.
        try {
            val b = Bundle()
            wv.saveState(b)
            t.state = b
        } catch (e: Exception) { t.state = null }
        content.removeView(wv)
        wv.stopLoading()
        wv.destroy()
        t.discarded = true
        t.paused = false
        t.playing = false
        t.button.alpha = 0.55f
    }

    private fun reviveTab(t: Tab) {
        val wv = createConfiguredWebView()
        if (t.desktop) wv.settings.userAgentString = desktopUa
        wv.visibility = View.GONE
        content.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        t.webView = wv
        t.discarded = false
        t.paused = false
        t.loadedAt = 0L
        t.button.alpha = 1f
        setupClients(t)
        val st = t.state
        t.state = null
        if (t.url.startsWith("http")) applyInitialScale(wv, t.url)
        val restored = try { st != null && wv.restoreState(st) != null } catch (e: Exception) { false }
        if (!restored && t.url.startsWith("http")) go(wv, t.url)
    }

    // ====================== Chan web den / lua dao ======================

    private val phishingAllow = mutableSetOf<String>()
    private var phishingCache: List<String>? = null

    private fun phishingList(): List<String> {
        phishingCache?.let { return it }
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(prefs.getString("phishing", "[]"))
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (e: Exception) {
            // du lieu hong - coi nhu rong
        }
        phishingCache = out
        return out
    }

    private fun savePhishing(list: List<String>) {
        val a = JSONArray()
        for (d in list) a.put(d)
        prefs.edit().putString("phishing", a.toString()).apply()
        phishingCache = list.toList()
    }

    private fun isPhishingHost(host: String): Boolean {
        val h = host.lowercase()
        return phishingList().any { h == it || h.endsWith(".$it") }
    }

    private fun phishingResponse(host: String, url: String): WebResourceResponse {
        val h = TextUtils.htmlEncode(host)
        val allow = TextUtils.htmlEncode("webandr-allow://go?h=" + Uri.encode(host) + "&u=" + Uri.encode(url))
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Cảnh báo</title></head>" +
                "<body style=\"font-family:sans-serif;background:#3a0f0f;color:#fff;padding:24px;text-align:center\">" +
                "<div style=\"font-size:56px\">⚠️</div><h2>Cảnh báo web đen / lừa đảo</h2>" +
                "<p>\"" + h + "\" nằm trong danh sách chặn của bạn. Tiếp tục truy cập có thể khiến bạn lộ " +
                "thông tin cá nhân, tài khoản ngân hàng hoặc bị lừa đảo.</p>" +
                "<p><a href=\"webandr-back://x\" style=\"display:inline-block;padding:12px 24px;background:#fff;" +
                "color:#3a0f0f;border-radius:24px;text-decoration:none;font-weight:bold\">Quay lại an toàn</a></p>" +
                "<p><a href=\"" + allow + "\" style=\"color:#ffb3b3;font-size:13px\">Tôi hiểu rủi ro, vẫn tiếp tục</a></p>" +
                "</body></html>"
        return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
    }

    private fun showPhishingManager() {
        val list = phishingList()
        val b = AlertDialog.Builder(this).setTitle("Chặn web đen (" + list.size + ")")
        if (list.isNotEmpty()) {
            b.setItems(list.toTypedArray()) { _, i -> confirmDeletePhishing(list[i]) }
        } else {
            b.setMessage("Danh sách đang trống. Thêm tên miền (vd: example.com) — truy cập tên miền đó và các tên miền con sẽ bị cảnh báo.")
        }
        b.setPositiveButton("Thêm") { _, _ -> promptAddPhishing() }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun promptAddPhishing() {
        val input = EditText(this).apply {
            hint = "example.com"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Thêm web vào danh sách chặn")
            .setView(input)
            .setPositiveButton("Thêm") { _, _ ->
                val d = input.text.toString().trim().lowercase()
                    .removePrefix("http://").removePrefix("https://")
                    .substringBefore("/").removePrefix("www.")
                if (d.isNotEmpty()) {
                    val list = phishingList().toMutableList()
                    if (!list.contains(d)) list.add(d)
                    savePhishing(list)
                    Toast.makeText(this, "Đã thêm: $d", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun confirmDeletePhishing(domain: String) {
        AlertDialog.Builder(this)
            .setTitle("Xoá khỏi danh sách chặn?")
            .setMessage(domain)
            .setPositiveButton("Xoá") { _, _ ->
                savePhishing(phishingList().filter { it != domain })
                phishingAllow.remove(domain)
            }
            .setNegativeButton("Giữ lại", null)
            .show()
    }

    // ====================== Mat khau da luu ======================

    private val docStartScriptSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }

    // Luu ma hoa bang Android Keystore (EncryptedSharedPreferences).
    private val securePrefs: SharedPreferences? by lazy {
        try {
            val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                this, "webandr_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun loadPw(): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        try {
            val a = JSONArray(securePrefs?.getString("list", "[]") ?: "[]")
            for (i in 0 until a.length()) out.add(a.getJSONObject(i))
        } catch (e: Exception) {
            // du lieu hong - coi nhu rong
        }
        return out
    }

    private fun savePwList(list: List<JSONObject>) {
        val a = JSONArray()
        for (o in list) a.put(o)
        securePrefs?.edit()?.putString("list", a.toString())?.apply()
    }

    private fun attachPasswordBridge(wv: WebView) {
        if (securePrefs == null) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(wv, "AndroidPw", setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
                if (isMainFrame) handlePwMessage(message.data, sourceOrigin.toString(), reply)
            }
        }
        if (docStartScriptSupported) {
            WebViewCompat.addDocumentStartJavaScript(wv, PW_JS, setOf("*"))
        }
    }

    private fun handlePwMessage(data: String?, origin: String, reply: JavaScriptReplyProxy) {
        if (data == null || !origin.startsWith("http")) return
        val m = try { JSONObject(data) } catch (e: Exception) { return }
        when (m.optString("t")) {
            "save" -> {
                val u = m.optString("u")
                val p = m.optString("p")
                if (p.isNotEmpty()) runOnUiThread { onPasswordCaptured(origin, u, p) }
            }
            "ask" -> {
                val matches = loadPw().filter {
                    it.optString("origin") == origin && !it.optBoolean("neverSave") &&
                            it.optString("password").isNotEmpty()
                }
                if (matches.isNotEmpty()) runOnUiThread { suggestPassword(origin, matches, reply) }
            }
        }
    }

    private var pwPromptShowing = false

    // De xuat dien mat khau: chi hien ten tai khoan, khong hien mat khau; chi dien khi nguoi dung dong y.
    private fun suggestPassword(origin: String, matches: List<JSONObject>, reply: JavaScriptReplyProxy) {
        if (pwPromptShowing) return
        pwPromptShowing = true
        fun fillWith(o: JSONObject) {
            try {
                reply.postMessage(
                    JSONObject().put("t", "fill")
                        .put("u", o.optString("username"))
                        .put("p", o.optString("password")).toString()
                )
            } catch (e: Exception) { }
        }
        fun label(o: JSONObject) = o.optString("username").ifEmpty { "(không có tên tài khoản)" }
        val b = AlertDialog.Builder(this)
            .setOnDismissListener { pwPromptShowing = false }
            .setNegativeButton("Không", null)
        if (matches.size == 1) {
            val o = matches[0]
            b.setTitle("Dùng mật khẩu đã lưu?")
                .setMessage(origin + "\n" + label(o))
                .setPositiveButton("Điền") { _, _ -> fillWith(o) }
                .setNeutralButton("Xoá") { _, _ ->
                    savePwList(loadPw().filter { it.optString("origin") != origin || it.optString("username") != o.optString("username") })
                }
        } else {
            val names = matches.map { label(it) }.toTypedArray()
            b.setTitle("Chọn tài khoản đã lưu")
                .setItems(names) { _, i -> fillWith(matches[i]) }
        }
        b.show()
    }

    private fun onPasswordCaptured(origin: String, username: String, password: String) {
        val list = loadPw()
        if (pwSavePromptShowing) return
        if (list.any {
                it.optString("origin") == origin && it.optString("username") == username &&
                        it.optString("password") == password
            }) return
        AlertDialog.Builder(this)
            .setTitle("Lưu mật khẩu?")
            .setMessage(origin + if (username.isNotEmpty()) "\n" + username else "")
            .setPositiveButton("Lưu") { _, _ ->
                val l = loadPw().filter {
                    !(it.optString("origin") == origin && it.optString("username") == username &&
                            !it.optBoolean("neverSave"))
                }.toMutableList()
                l.add(
                    JSONObject().put("origin", origin).put("username", username)
                        .put("password", password).put("savedAt", System.currentTimeMillis())
                )
                savePwList(l)
            }
            .setNegativeButton("Không", null)
            .setOnDismissListener { pwSavePromptShowing = false }
            .show()
        pwSavePromptShowing = true
    }

    private var pwSavePromptShowing = false
}

// Script chay trong moi trang: bat form dang nhap + dien san mat khau da luu.
private val PW_JS = """
(function(){
 if(window.__wbPw||!window.AndroidPw)return; window.__wbPw=true;
 function findUser(form,pw){var c=form.querySelectorAll('input[type="text"],input[type="email"],input[autocomplete="username"],input:not([type])');for(var i=0;i<c.length;i++){if(c[i]!==pw&&c[i].value)return c[i];}return null;}
 var lastKey='',lastT=0;
 function trySave(pw){try{if(!pw||!pw.value)return;var scope=pw.closest('form')||document;var u=findUser(scope,pw);var uv=u?u.value:'';var key=uv+'|'+pw.value;var now=Date.now();if(key===lastKey&&now-lastT<4000)return;lastKey=key;lastT=now;AndroidPw.postMessage(JSON.stringify({t:'save',u:uv,p:pw.value}));}catch(x){}}
 document.addEventListener('submit',function(e){try{var f=e.target;if(!(f instanceof HTMLFormElement))return;trySave(f.querySelector('input[type="password"]'));}catch(x){}},true);
 document.addEventListener('keydown',function(e){try{if(e.key!=='Enter')return;var el=e.target;if(!el||el.tagName!=='INPUT')return;var pw=el.type==='password'?el:(el.closest('form')||document).querySelector('input[type="password"]');trySave(pw);}catch(x){}},true);
 document.addEventListener('click',function(e){try{var t=e.target&&e.target.closest?e.target.closest('button,input[type="submit"],input[type="button"],[role="button"]'):null;if(!t)return;var pws=document.querySelectorAll('input[type="password"]');for(var i=0;i<pws.length;i++){if(pws[i].value){trySave(pws[i]);break;}}}catch(x){}},true);
 function fill(u,p){var pw=document.querySelector('input[type="password"]');if(!pw)return;pw.value=p;pw.dispatchEvent(new Event('input',{bubbles:true}));pw.dispatchEvent(new Event('change',{bubbles:true}));
  if(u){var scope=pw.closest('form')||document;var ui=scope.querySelector('input[type="text"],input[type="email"],input[autocomplete="username"],input:not([type])');if(ui){ui.value=u;ui.dispatchEvent(new Event('input',{bubbles:true}));ui.dispatchEvent(new Event('change',{bubbles:true}));}}}
 AndroidPw.onmessage=function(ev){try{var m=JSON.parse(ev.data);if(m.t==='fill')fill(m.u,m.p);}catch(x){}};
 var asked=false;
 function check(){try{var has=!!document.querySelector('input[type="password"]');if(has&&!asked){asked=true;AndroidPw.postMessage(JSON.stringify({t:'ask'}));}else if(!has){asked=false;}}catch(x){}}
 try{var ob=new MutationObserver(check);function start(){if(document.documentElement){ob.observe(document.documentElement,{childList:true,subtree:true});check();}}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();}catch(x){}
})();
"""

// Xoa `del` ky tu truoc con tro roi chen `ins` (nhap chu tu dien thoai qua Truyen tep).
private val TEXT_EDIT_FN = """
function(del, ins) {
  var el = document.activeElement;
  try {
    while (el && el.tagName === 'IFRAME' && el.contentDocument && el.contentDocument.activeElement) {
      el = el.contentDocument.activeElement;
    }
  } catch (e) {}
  if (!el) return false;
  var doc = el.ownerDocument;
  function stepBack(str, idx, n) {
    while (n > 0 && idx > 0) {
      idx--;
      var c = str.charCodeAt(idx);
      if (c >= 0xDC00 && c <= 0xDFFF && idx > 0) {
        var h = str.charCodeAt(idx - 1);
        if (h >= 0xD800 && h <= 0xDBFF) idx--;
      }
      n--;
    }
    return idx;
  }
  function fire(target) {
    try {
      target.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: ins ? 'insertText' : 'deleteContentBackward', data: ins || null }));
    } catch (e) {
      target.dispatchEvent(new Event('input', { bubbles: true }));
    }
  }
  if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
    var v = el.value;
    var s = null, e2 = null;
    try { s = el.selectionStart; e2 = el.selectionEnd; } catch (err) {}
    if (s === null || e2 === null) { s = e2 = v.length; }
    var start = (e2 === s) ? stepBack(v, s, del) : s;
    try {
      el.setRangeText(ins, start, e2, 'end');
    } catch (err) {
      var proto = el.tagName === 'INPUT' ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
      setter.call(el, v.slice(0, start) + ins + v.slice(e2));
    }
    fire(el);
    return true;
  }
  if (el.isContentEditable) {
    for (var k = 0; k < del; k++) doc.execCommand('delete');
    if (ins) doc.execCommand('insertText', false, ins);
    return true;
  }
  return false;
}
"""

// Tu choi/chan link khi nguoi dung TAT "mo link 1 cham" (giong ban may tinh): chan cham vao <a href>
// va window.open() de tranh lo tay mo trang quang cao. Link '#...' va 'javascript:' van chay binh thuong.
private val ONE_TAP_JS = """
(function(){
 if(window.__wbOneTapInstalled)return; window.__wbOneTapInstalled=true;
 if(typeof window.__wbOneTap==='undefined')window.__wbOneTap=true;
 function blockable(a){var h=a.getAttribute('href')||'';return h!==''&&h.charAt(0)!=='#'&&h.slice(0,11).toLowerCase()!=='javascript:';}
 function block(e){
  if(window.__wbOneTap!==false)return;
  var t=e.target;var a=(t&&t.closest)?t.closest('a[href]'):null;
  if(!a||!blockable(a))return;
  e.preventDefault();e.stopImmediatePropagation();
 }
 ['click','auxclick','mousedown','pointerdown','touchend'].forEach(function(n){document.addEventListener(n,block,true);});
 try{var no=window.open;window.open=function(){if(window.__wbOneTap===false)return null;return no.apply(window,arguments);};}catch(x){}
})();
"""

// YouTube: tu bam "Bo qua" quang cao, tua nhanh quang cao khong bo qua duoc (tat tieng roi nhay den cuoi),
// va an banner/quang cao hien thi. Bat/tat qua window.__wbAdSkip.
private val YT_AD_JS = """
(function(){
 if(window.__wbYtInstalled)return; window.__wbYtInstalled=true;
 if(typeof window.__wbAdSkip==='undefined')window.__wbAdSkip=true;
 var HIDE='.ytp-ad-overlay-container,.ytp-ad-text-overlay,.ytp-ad-image-overlay,.video-ads,#player-ads,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-in-feed-ad-layout-renderer,ytd-ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer{display:none!important}';
 var SKIP=['.ytp-ad-skip-button','.ytp-ad-skip-button-modern','.ytp-skip-ad-button','.videoAdUiSkipButton','.ytp-ad-skip-button-container button','.ytp-skip-ad button'];
 var mutedByUs=false,fastByUs=false;
 function tick(){
  try{
   var on=window.__wbAdSkip!==false;
   var st=document.getElementById('__wb_adcss');
   if(on&&!st&&document.documentElement){st=document.createElement('style');st.id='__wb_adcss';st.textContent=HIDE;document.documentElement.appendChild(st);}
   else if(!on&&st){st.parentNode.removeChild(st);}
   var v=document.querySelector('video.html5-main-video')||document.querySelector('video');
   var p=document.querySelector('.html5-video-player');
   var ad=!!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));
   if(on&&ad){
    for(var i=0;i<SKIP.length;i++){var b=document.querySelector(SKIP[i]);if(b)b.click();}
    if(v){
     if(!v.muted){v.muted=true;mutedByUs=true;}
     if(v.duration&&isFinite(v.duration)){v.currentTime=v.duration;}else{v.playbackRate=16;fastByUs=true;}
    }
   }else if(v){
    if(mutedByUs){v.muted=false;mutedByUs=false;}
    if(fastByUs){v.playbackRate=1;fastByUs=false;}
   }
  }catch(e){}
 }
 setInterval(tick,250);
})();
"""
