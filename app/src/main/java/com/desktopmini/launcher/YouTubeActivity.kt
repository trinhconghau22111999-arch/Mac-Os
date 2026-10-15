package com.desktopmini.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.Locale

private const val YT_ONLY_HOME = "https://www.youtube.com/?hl=vi&gl=VN&persist_hl=1"

/**
 * App con "YouTube": chỉ là một WebView mở YouTube, không có thanh địa chỉ.
 * - Chỉ cho vào youtube.com / youtu.be (và trang đăng nhập Google); link ra ngoài bị chặn.
 * - Chặn quảng cáo theo cách của app "Duyệt web" (Webandr): chặn tên miền quảng cáo
 *   + script tự bấm "Bỏ qua", tua nhanh quảng cáo không bỏ qua được, ẩn banner.
 */
class YouTubeActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var lastToastAt = 0L

    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
        "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
    )

    private fun hostIs(h: String, d: String) = h == d || h.endsWith(".$d")

    private fun isAdHost(h: String) = adHosts.any { hostIs(h, it) }

    /** Trang (khung chính) được phép mở: YouTube + trang đăng nhập/đồng ý của Google. */
    private fun allowedPage(u: Uri): Boolean {
        val scheme = (u.scheme ?: return false).lowercase()
        if (scheme == "about") return true
        if (scheme != "https" && scheme != "http") return false
        val h = (u.host ?: return false).lowercase()
        if (hostIs(h, "youtube.com") || h == "youtu.be" || hostIs(h, "youtube-nocookie.com")) {
            // /redirect là cổng YouTube dùng để đưa người xem ra web ngoài
            val p = u.path ?: ""
            return !p.startsWith("/redirect")
        }
        return h.startsWith("accounts.google.") || h.startsWith("consent.google.")
    }

    private fun toastBlocked() {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 1500) return
        lastToastAt = now
        runOnUiThread { Toast.makeText(this, "Ứng dụng này chỉ mở được YouTube", Toast.LENGTH_SHORT).show() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
        }
        web = WebView(this)
        web.addJavascriptInterface(VoiceBridge(), "AndroidVoice")
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportMultipleWindows(false)              // link mở cửa sổ mới sẽ đi qua bộ lọc bên dưới
        s.javaScriptCanOpenWindowsAutomatically = false
        s.builtInZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // UA như Chrome thật (bỏ "; wv" và "Version/4.0") + không gửi X-Requested-With: đăng nhập Google dễ hơn
        s.userAgentString = WebSettings.getDefaultUserAgent(this).replace("; wv)", ")").replace(" Version/4.0", "")
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        } catch (e: Exception) { }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // Script chặn quảng cáo chạy ngay khi trang YouTube bắt đầu tải
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                WebViewCompat.addDocumentStartJavaScript(web, AD_ON + YT_ONLY_AD_JS, setOf("https://*.youtube.com", "https://youtube.com"))
        } catch (e: Exception) { }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val r = request ?: return false
                if (!r.isForMainFrame) return false
                if (allowedPage(r.url)) return false
                toastBlocked()
                return true                                 // không mở link ngoài
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val r = request ?: return null
                val h = (r.url?.host ?: return null).lowercase()
                if (isAdHost(h)) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                // Chuyển hướng từ máy chủ ra ngoài YouTube (không qua shouldOverrideUrlLoading)
                if (r.isForMainFrame && !allowedPage(r.url)) {
                    toastBlocked()
                    val html = "<meta name=viewport content='width=device-width,initial-scale=1'>" +
                        "<body style='background:#111;color:#eee;font:16px sans-serif;text-align:center;padding:48px 20px'>" +
                        "Ứng dụng này chỉ mở được YouTube.<br><br>Bấm Quay lại để trở về.</body>"
                    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                }
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // WebView cũ không hỗ trợ script lúc bắt đầu tải: chèn sau khi tải xong (script tự chống chạy 2 lần)
                val h = try { Uri.parse(url ?: "").host } catch (e: Exception) { null } ?: return
                if (hostIs(h, "youtube.com")) {
                    view?.evaluateJavascript(AD_ON + YT_ONLY_AD_JS, null)
                    view?.evaluateJavascript(VOICE_HOOK_JS, null)   // nối nút mic của YouTube
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) { callback?.onCustomViewHidden(); return }
                customView = view
                customCallback = callback
                view.setBackgroundColor(Color.BLACK)
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                LockService.setVideoFull(true)
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
            override fun onHideCustomView() { hideCustomView() }
        }

        val first = intent?.dataString?.takeIf { allowedPage(Uri.parse(it)) } ?: YT_ONLY_HOME
        web.loadUrl(first)
    }

    // ---- Tìm kiếm bằng giọng nói (học từ repo Y-utubecuatoi): nút mic của YouTube không chạy trong WebView
    // (Web Speech API không có), nên bắt cú bấm rồi gọi bộ nhận dạng giọng nói gốc của Android. ----
    inner class VoiceBridge {
        @JavascriptInterface fun startVoiceSearch() { runOnUiThread { launchVoiceRecognizer() } }
    }

    private fun launchVoiceRecognizer() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE_PERM)
            return
        }
        val it = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm trên YouTube")
        }
        try { startActivityForResult(it, REQ_VOICE) }
        catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Máy chưa có ứng dụng nhận dạng giọng nói (cần ứng dụng Google). Hãy cài hoặc cập nhật Google từ CH Play", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_VOICE) {
            if (resultCode == RESULT_OK) {
                val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!q.isNullOrBlank()) web.loadUrl("https://www.youtube.com/results?search_query=" + Uri.encode(q))
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_VOICE_PERM) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchVoiceRecognizer()
            else Toast.makeText(this, "Cần cấp quyền micro để dùng tìm kiếm bằng giọng nói", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideCustomView() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        LockService.setVideoFull(false)
        customCallback?.onCustomViewHidden()
        customCallback = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        val u = intent?.dataString ?: return
        if (allowedPage(Uri.parse(u))) web.loadUrl(u)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustomView()
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()                   // hết trang để lùi: đóng app, về Desktop Mini
        }
    }

    override fun onDestroy() {
        if (customView != null) LockService.setVideoFull(false)
        try { root.removeView(web); web.destroy() } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val AD_ON = "window.__wbAdSkip=true;"
        private const val REQ_VOICE = 201
        private const val REQ_VOICE_PERM = 202
    }
}

// Chép từ app "Duyệt web" (Webandr): tự bấm "Bỏ qua" quảng cáo YouTube, tua nhanh quảng cáo không bỏ qua được
// (tắt tiếng rồi nhảy đến cuối), ẩn banner/quảng cáo hiển thị.
const val YT_ONLY_AD_JS = """
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

// Gắn mọi nút "Tìm kiếm bằng giọng nói" trên trang (YouTube, Google...) sang AndroidVoice. Dò theo aria-label/title
// thay vì class CSS vì nhãn hỗ trợ tiếp cận ít khi đổi giữa các bản cập nhật giao diện. (Học từ repo Y-utubecuatoi.)
const val VOICE_HOOK_JS = """
(function(){
 if(window.__dmVoiceHook)return; window.__dmVoiceHook=true;
 var L=['tìm kiếm bằng giọng nói','tim kiem bang giong noi','search by voice','voice search','search with your voice'];
 function txt(el){return ((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')).toLowerCase();}
 function has(t){for(var i=0;i<L.length;i++){if(t.indexOf(L[i])!==-1)return true;}return false;}
 function hook(){
  Array.prototype.forEach.call(document.querySelectorAll('button,[role="button"],a,div[aria-label],span[aria-label]'),function(el){
   if(el.__dmVoice)return;
   if(!has(txt(el)))return;
   el.__dmVoice=true;
   el.addEventListener('click',function(ev){
    if(window.AndroidVoice){ev.preventDefault();ev.stopPropagation();window.AndroidVoice.startVoiceSearch();}
   },true);
  });
 }
 hook(); setInterval(hook,1000);
 try{new MutationObserver(hook).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})();
"""
