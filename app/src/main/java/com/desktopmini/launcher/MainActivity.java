package com.desktopmini.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Size;
import androidx.webkit.WebViewAssetLoader;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static MainActivity inst;
    FrameLayout root;
    private boolean askingRuntime = false;
    WebView web;
    final HashMap<String, WebView> ext = new HashMap<String, WebView>();
    /* Zoom (2 ngón / Ctrl+lăn chuột) + nhớ mức zoom theo từng ứng dụng web nhúng */
    final HashMap<String, ZoomHelper> zooms = new HashMap<String, ZoomHelper>();
    /* Tự đóng hẳn trang web nhúng đã ẩn lâu để giải phóng RAM, như điện thoại đóng ứng dụng nền.
       Mở lại cửa sổ đó thì trang được tạo mới và tải lại (extShow -> extGet). Đổi EXT_IDLE_MS để chỉnh thời gian chờ. */
    private static final long EXT_IDLE_MS = 10 * 60 * 1000L;
    /* YouTube: báo cho trang luôn là "đang hiển thị" để trình phát không tự dừng khi cửa sổ bị ẩn / app khác che */
    private static final String YT_KEEP_JS = "(function(){try{var d=document;Object.defineProperty(d,'hidden',{configurable:true,get:function(){return false}});Object.defineProperty(d,'visibilityState',{configurable:true,get:function(){return 'visible'}});['visibilitychange','webkitvisibilitychange'].forEach(function(t){d.addEventListener(t,function(e){e.stopImmediatePropagation()},true)});window.addEventListener('blur',function(e){e.stopImmediatePropagation()},true)}catch(e){}})();";
    final HashMap<String, Long> extHiddenAt = new HashMap<String, Long>();
    private final android.os.Handler reaper = new android.os.Handler(android.os.Looper.getMainLooper());
    /* Cài/gỡ/cập nhật app: dựng lại danh sách app cho Launchpad và Tìm kiếm (gộp các sự kiện bắn liền nhau, chờ 2 giây) */
    private final Runnable appsRefresh = new Runnable() { public void run() { appsAt = 0; prefetchApps(); } };
    private final android.content.BroadcastReceiver pkgRx = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { reaper.removeCallbacks(appsRefresh); reaper.postDelayed(appsRefresh, 2000); }
    };
    /* Độ sáng thấp nhất khi không dùng: không chạm/gõ/rê chuột sau idleDimMs thì hạ độ sáng cửa sổ xuống mức tối nhất, chạm lại thì sáng như cũ */
    volatile boolean idleDimOn = false;
    volatile long idleDimMs = 5 * 60000L;
    volatile long lastTouchAt = android.os.SystemClock.elapsedRealtime();
    private boolean idleDimmed = false, inFg = false;
    private float idleDimPrev = -1f;
    private final Runnable idleTask = new Runnable() { public void run() { checkIdleDim(); reaper.postDelayed(this, 5000); } };

    private void userActive() {
        lastTouchAt = android.os.SystemClock.elapsedRealtime();
        if (idleDimmed) undimIdle();
    }
    void undimIdle() {
        if (!idleDimmed) return;
        idleDimmed = false;
        try { WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = idleDimPrev; getWindow().setAttributes(lp); } catch (Exception e) { }
    }
    /** Chưa tới hạn thì thôi; đang xem video toàn màn hình hoặc đang phát nhạc/video trong trang web nhúng thì không hạ (tránh tối màn hình khi đang xem). */
    private void checkIdleDim() {
        if (!idleDimOn || idleDimmed || !inFg) return;
        if (android.os.SystemClock.elapsedRealtime() - lastTouchAt < idleDimMs) return;
        if (extFullView != null) return;
        try {
            boolean music = ((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive();
            if (music) for (WebView v : ext.values()) if (v.getVisibility() == View.VISIBLE) return;
        } catch (Exception e) { }
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            idleDimPrev = lp.screenBrightness; lp.screenBrightness = 0.004f; getWindow().setAttributes(lp);
            idleDimmed = true;
        } catch (Exception e) { }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent ev) { userActive(); return super.dispatchTouchEvent(ev); }
    @Override public boolean dispatchKeyEvent(KeyEvent ev) { userActive(); return super.dispatchKeyEvent(ev); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) { userActive(); return super.dispatchGenericMotionEvent(ev); }

    private final Runnable reapTask = new Runnable() { public void run() { reapIdleExt(); reaper.postDelayed(this, 60000); } };
    /** Gmail / Drive / Zalo / Facebook web: giao diện máy tính. */
    private static final String UA_DESK = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    /** UA máy tính nhưng mang đúng số phiên bản Chrome thật của WebView trên máy (không khai man phiên bản, bỏ "; wv"), để Google chấp nhận đăng nhập. */
    private String cleanDeskUa() {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/[\\d.]+").matcher(WebSettings.getDefaultUserAgent(this));
            if (m.find()) return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " + m.group() + " Safari/537.36";
        } catch (Exception e) { }
        return UA_DESK;
    }
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    /** Đẩy sự kiện từ service nền vào giao diện HTML. */
    static void push(final String fn, final String json) {
        final MainActivity a = inst;
        if (a == null || a.web == null) return;
        a.runOnUiThread(new Runnable() { public void run() { a.web.evaluateJavascript("window." + fn + "&&window." + fn + "(" + json + ")", null); } });
    }

    static String iconOf(Context c, String pkg) {
        try { return icon(c.getPackageManager().getApplicationIcon(pkg), 64); } catch (Exception e) { return ""; }
    }

    private final BroadcastReceiver media = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String cmd = i.getStringExtra("cmd");
            if (cmd != null && web != null) web.evaluateJavascript("window.nativeMedia&&window.nativeMedia('" + cmd + "')", null);
        }
    };

    private final BroadcastReceiver scr = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (web != null) web.evaluateJavascript("window.nativeScreenOff&&window.nativeScreenOff()", null);
        }
    };
    android.media.Ringtone previewTone;
    /* ---- Bluetooth: thiết bị tìm thấy xung quanh + proxy hồ sơ để biết thiết bị nào đang kết nối ---- */
    /** "Duyệt web": trình duyệt Webandr đầy đủ chạy nhúng trong cửa sổ (tạo khi mở lần đầu). */
    BrowserHost browserHost;
    final java.util.LinkedHashMap<String, JSONObject> btFound = new java.util.LinkedHashMap<String, JSONObject>();
    final HashMap<Integer, BluetoothProfile> btProx = new HashMap<Integer, BluetoothProfile>();
    boolean btRecv = false, btProxReq = false;
    final BroadcastReceiver btRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try {
                String act = i.getAction();
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(act)) { btDiscovering = false; return; }
                if (!BluetoothDevice.ACTION_FOUND.equals(act)) return;
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || d.getBondState() == BluetoothDevice.BOND_BONDED) return;
                String nm = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (nm == null || nm.length() == 0) { try { nm = d.getName(); } catch (Exception e) { } }
                boolean named = nm != null && nm.length() > 0;
                String ad = d.getAddress();
                JSONObject o = new JSONObject(); o.put("name", named ? nm : "Thiết bị " + (ad == null ? "" : ad)); o.put("addr", ad); o.put("named", named);
                synchronized (btFound) {
                    JSONObject old = btFound.get(ad);
                    if (old != null && old.optBoolean("named") && !named) return;
                    btFound.put(ad, o);
                }
            } catch (Exception e) { }
        }
    };
    boolean btDiscovering = false;


    /** Origin ảo của trang chính; các tệp trong assets/ được phục vụ qua WebViewAssetLoader. */
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net/assets/";
    private WebViewAssetLoader assetLoader;

    /** Một lần duy nhất sau khi chuyển sang AssetLoader: xóa dữ liệu localStorage cũ của origin file:// (trang mới ở origin khác nên không đọc được, chỉ tốn chỗ). */
    private void purgeOldFileOrigin() {
        final android.content.SharedPreferences sp = getSharedPreferences("dm_native", MODE_PRIVATE);
        if (sp.getBoolean("purged_file_origin_v1", false)) return;
        try {
            final WebView pw = new WebView(this);
            WebSettings ps = pw.getSettings();
            ps.setJavaScriptEnabled(true); ps.setDomStorageEnabled(true);
            ps.setAllowFileAccess(false); ps.setAllowContentAccess(false);
            pw.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(final WebView v, String u) {
                    /* chờ vài giây cho WebView kịp ghi việc xóa xuống đĩa rồi mới hủy và đánh dấu đã xong */
                    v.postDelayed(new Runnable() { public void run() {
                        sp.edit().putBoolean("purged_file_origin_v1", true).apply();
                        try { v.destroy(); } catch (Exception e) { }
                    } }, 6000);
                }
            });
            pw.loadUrl("file:///android_asset/purge-old.html");
        } catch (Throwable t) { }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        CrashLog.install(getApplicationContext());
        final String lastExit = CrashLog.reportLastExit(this);
        final String lastCrash = lastExit != null ? "bị tắt do " + lastExit : CrashLog.takeLastCrash(this);
        if (lastCrash != null) gkToast("Lần trước app bị lỗi: " + lastCrash + "\nChi tiết: " + CrashLog.where(this));
        new Thread(new Runnable() { public void run() { try { TrashStore.purgeOld(MainActivity.this); } catch (Exception e) { } } }).start();   /* Thùng rác: dọn mục quá 7 ngày */
        assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or2, int ob) {
            if (b != ob || t != ot) reportDockGap(); } });
        if (Build.VERSION.SDK_INT >= 30) {
            /* Không để hệ thống tự chừa chỗ cho thanh trạng thái/điều hướng: nếu không, mỗi lần thanh hiện thoáng qua (vuốt mép, quay lại app...)
               giao diện bị đẩy xuống ("giật"), có lúc kẹt lại một khoảng trống bằng thanh menu ở trên cùng. Chỉ chừa chỗ cho bàn phím. */
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    int ime = in.getInsets(WindowInsets.Type.ime()).bottom;
                    if (v.getPaddingBottom() != ime) v.setPadding(0, 0, 0, ime);
                    /* Vuốt từ mép trên/dưới làm thanh hệ thống hiện ra: ẩn lại ngay để không kéo được thanh hệ thống */
                    if (!imeUp && (in.isVisible(WindowInsets.Type.statusBars()) || in.isVisible(WindowInsets.Type.navigationBars()))) rehideBars();
                    return WindowInsets.CONSUMED;
                }
            });
        } else {
            /* Android 10 trở xuống (vd. 7.1.1): khung nội dung mặc định của hệ thống có fitsSystemWindows=true nên tự đệm một khoảng bằng thanh trạng thái
               mỗi khi cờ toàn màn hình bị hệ thống gỡ tạm (vuốt mép, quay lại app, đóng bàn phím...) -> giao diện giật và kẹt khoảng trống ở trên cùng.
               Tắt đệm tự động đó, tự chừa chỗ chỉ cho bàn phím. */
            try {
                View content = findViewById(android.R.id.content);
                content.setFitsSystemWindows(false);
                if (content.getParent() instanceof View) ((View) content.getParent()).setFitsSystemWindows(false);
            } catch (Exception e) { }
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    /* Đáy lớn hơn thanh điều hướng = bàn phím đang mở */
                    int kb = in.getSystemWindowInsetBottom() > in.getStableInsetBottom() ? in.getSystemWindowInsetBottom() : 0;
                    if (v.getPaddingBottom() != kb) v.setPadding(0, 0, 0, kb);
                    return in.consumeSystemWindowInsets();
                }
            });
            /* Vuốt từ mép làm thanh hệ thống hiện ra: ẩn lại ngay */
            getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
                @Override public void onSystemUiVisibilityChange(int vis) {
                    if (!imeUp && (vis & (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) != (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) rehideBars();
                }
            });
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        /* Trang chính chạy ở https://appassets.androidplatform.net/assets/ (WebViewAssetLoader), không còn là file://:
           không cần quyền đọc tệp cục bộ, và có origin thật nên áp được chính sách bảo mật. Khóa mọi quyền file/content. */
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        web.setBackgroundColor(0x00000000);
        web.setFocusable(true); web.setFocusableInTouchMode(true); web.requestFocus();
        web.setWebViewClient(new WebViewClient() {
            /* Trang chính chỉ được ở trong APP_ORIGIN (tài nguyên của app). Mọi điều hướng khác bị chặn (link ngoài mở ở app/trình duyệt riêng, không nạp vào trang có cầu AndroidLauncher) */
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return assetLoader.shouldInterceptRequest(req.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                String u = req.getUrl().toString();
                if (u.startsWith(APP_ORIGIN) || u.startsWith("about:")) return false;
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { }
                }
                return true;
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onPermissionRequest(final android.webkit.PermissionRequest q) {
                runOnUiThread(new Runnable() { public void run() {
                    ArrayList<String> miss = new ArrayList<String>(); boolean known = false;
                    for (String rs : q.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(rs)) { known = true; if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.RECORD_AUDIO); }
                        else if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(rs)) { known = true; if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.CAMERA); }
                    }
                    if (!known) { q.deny(); return; }
                    if (miss.isEmpty()) { answerWeb(q); return; }
                    if (micReq != null) micReq.deny();
                    micReq = q; askingRuntime = true;
                    requestPermissions(miss.toArray(new String[0]), 77);
                } });
            }
        });
        web.addJavascriptInterface(new LauncherBridge(this), "AndroidLauncher");
        purgeOldFileOrigin();
        web.loadUrl(APP_ORIGIN + "index.html");
        prefetchApps();
        warnOldWebView();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        IntentFilter f = new IntentFilter("com.desktopmini.launcher.MEDIA");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(media, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(media, f);
        ScreenTime.start(this);
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scr, sf, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(scr, sf);
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED); pf.addAction(Intent.ACTION_PACKAGE_REPLACED); pf.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgRx, pf, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(pkgRx, pf);
        trackIme();
        immersive();
        askPermissions();
        reaper.postDelayed(reapTask, 60000);
        reaper.postDelayed(idleTask, 5000);
    }

    @Override protected void onPause() {
        super.onPause();
        inFg = false; undimIdle();
        if (browserHost != null) { try { browserHost.onPause(); } catch (Exception e) { } }
        if (wordHost != null) wordHost.save();
        if (omemProfile.length() > 0) com.desktopmini.launcher.drive3.DriveEmbed.captureIfActive(this, omemProfile);
        /* Tối ưu: rời Desktop Mini thì tạm dừng mọi trang web nhúng (YouTube, Zalo, Office...) cho đỡ tốn pin/RAM, như ứng dụng ở nền trên điện thoại */
        for (java.util.Map.Entry<String, WebView> e : ext.entrySet()) extPause(e.getKey(), e.getValue());
        for (String k : ext.keySet()) markExtHidden(k);
    }

    /** Tạm dừng trang web nhúng khi bị ẩn. Riêng YouTube đang phát video/nhạc thì KHÔNG tạm dừng: cứ phát tiếp dù cửa sổ bị thu nhỏ, bị app khác che hoặc rời khỏi Desktop Mini. */
    void extPause(String key, WebView v) {
        if (v == null) return;
        if ("youtube".equals(key)) {
            try { if (((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive()) return; } catch (Exception e) { }
        }
        try { v.onPause(); } catch (Exception e) { }
    }

    /** Ghi lại thời điểm trang bắt đầu bị ẩn (chỉ ghi lần đầu, không đặt lại khi ẩn tiếp). */
    void markExtHidden(String key) {
        if (!extHiddenAt.containsKey(key)) extHiddenAt.put(key, android.os.SystemClock.elapsedRealtime());
    }

    /** Đóng hẳn các trang nhúng đã ẩn quá EXT_IDLE_MS. Bỏ qua trang đang hiện, "Gõ văn bản" (giữ tài liệu đang soạn) và lúc đang phát nhạc. */
    private void reapIdleExt() { reapExt(EXT_IDLE_MS); }

    /** minIdleMs = 0: đóng NGAY mọi trang nhúng đang ẩn (dùng khi máy sắp hết RAM), không chờ đủ thời gian. */
    private void reapExt(long minIdleMs) {
        if (ext.isEmpty()) return;
        if (minIdleMs == 0 && extFullView != null) return; /* đang xem toàn màn hình thì không đụng tới */
        long now = android.os.SystemClock.elapsedRealtime();
        boolean music = false;
        try { music = ((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive(); } catch (Exception e) { }
        if (music && minIdleMs > 0) return; /* đang phát thì giữ nguyên; chỉ khi máy sắp hết RAM (minIdleMs = 0) mới đóng cả trang đang phát */
        for (String key : new ArrayList<String>(ext.keySet())) {
            Long t = extHiddenAt.get(key);
            WebView v = ext.get(key);
            if (v == null || v.getVisibility() == View.VISIBLE || "word".equals(key)) continue;
            if (minIdleMs > 0 && (t == null || now - t < minIdleMs)) continue;
            ext.remove(key); zooms.remove(key); extHiddenAt.remove(key);
            try { v.loadUrl("about:blank"); root.removeView(v); v.destroy(); } catch (Exception e) { }
        }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        /* Máy sắp hết RAM thật (đang chạy mà RAM tới mức "nguy cấp", hoặc app ở nền bị đẩy tới giữa/cuối danh sách bị giết): đóng ngay trang nhúng đang ẩn.
           Cố ý KHÔNG dùng mức UI_HIDDEN (20) và BACKGROUND (40): hai mức này xảy ra mỗi khi mở app khác từ launcher, chưa phải thiếu RAM, đóng thì YouTube/Zalo bị tải lại liên tục. */
        if (browserHost != null) { try { browserHost.onTrimMemory(level); } catch (Exception e) { } }
        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_MODERATE) reapExt(0);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            for (WebView v : ext.values()) { try { if (v.getVisibility() != View.VISIBLE) v.clearCache(false); } catch (Exception e) { } }
        }
    }

    @Override protected void onDestroy() {
        if (browserHost != null) { try { browserHost.destroy(); } catch (Exception e) { } browserHost = null; }
        aiCancel();
        stopMediaWatch();
        reaper.removeCallbacks(reapTask); reaper.removeCallbacks(idleTask); reaper.removeCallbacks(appsRefresh);
        try { unregisterReceiver(pkgRx); } catch (Exception e) { }
        try { unregisterReceiver(media); } catch (Exception e) { }
        try { unregisterReceiver(scr); } catch (Exception e) { }
        if (btRecv) { try { unregisterReceiver(btRx); } catch (Exception e) { } }
        if (inst == this) inst = null;
        super.onDestroy();
    }

    /** Bàn phím đang mở? Khi mở thì không ép ẩn thanh hệ thống, vì làm vậy sẽ đóng bàn phím (ô nhập bị văng). */
    private boolean imeUp = false;
    private android.webkit.PermissionRequest micReq = null;
    /** Trả lời yêu cầu micro/camera của trang web: chỉ cho phần mà Android đã cấp. */
    private void answerWeb(android.webkit.PermissionRequest q) {
        ArrayList<String> ok = new ArrayList<String>();
        for (String rs : q.getResources()) {
            boolean g = Build.VERSION.SDK_INT < 23;
            if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(rs)) g = g || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            else if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(rs)) g = g || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (g) ok.add(rs);
        }
        if (ok.isEmpty()) q.deny(); else q.grant(ok.toArray(new String[0]));
    }
    private void trackIme() {
        final View d = getWindow().getDecorView();
        d.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                boolean up;
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsets wi = d.getRootWindowInsets();
                    up = wi != null && wi.isVisible(WindowInsets.Type.ime());
                } else {
                    android.graphics.Rect r = new android.graphics.Rect();
                    d.getWindowVisibleDisplayFrame(r);
                    int h = d.getRootView().getHeight();
                    up = h > 0 && (h - r.bottom) > h * 0.15f;
                }
                if (up != imeUp) { imeUp = up; if (!up) immersive(); }
            }
        });
    }

    private boolean rehidePending = false;
    /** Thanh hệ thống vừa hiện (do vuốt từ mép): ẩn lại ngay ở vòng lặp giao diện kế tiếp. */
    private void rehideBars() {
        if (rehidePending) return;
        rehidePending = true;
        root.post(new Runnable() { public void run() { rehidePending = false; if (inFg) immersive(); } });
    }

    private void immersive() {
        if (imeUp) return;
        if (Build.VERSION.SDK_INT >= 30) {
            /* Android 11+: cờ SYSTEM_UI_FLAG cũ hay bị bỏ qua, dùng WindowInsetsController để ẩn thanh điều hướng */
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Xin các quyền thường ngay lần mở đầu tiên sau khi cài. */
    private void askPermissions() {
        ArrayList<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_AUDIO); need.add(Manifest.permission.READ_MEDIA_IMAGES); need.add(Manifest.permission.READ_MEDIA_VIDEO); need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 31) { need.add(Manifest.permission.BLUETOOTH_CONNECT); need.add(Manifest.permission.BLUETOOTH_SCAN); }
        need.add(Manifest.permission.RECORD_AUDIO);
        need.add(Manifest.permission.CAMERA);
        need.add(Manifest.permission.READ_CALENDAR);
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        ArrayList<String> miss = new ArrayList<String>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        if (!miss.isEmpty()) { askingRuntime = true; requestPermissions(miss.toArray(new String[0]), 7); }
    }

    private boolean listed(String setting) {
        String v = Settings.Secure.getString(getContentResolver(), setting);
        return v != null && v.contains(getPackageName());
    }
    boolean notifAccess() { return listed("enabled_notification_listeners"); }
    boolean a11yOn() { return listed(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); }
    Uri mediaUri(String kind) {
        if ("vid".equals(kind)) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        if ("aud".equals(kind)) return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    /** Desktop Mini có đang là màn hình chính mặc định của Android không. */
    boolean isDefaultHome() {
        try {
            ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    boolean filesOk() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Mỗi quyền đặc biệt chỉ hỏi một lần; lần lượt từng quyền mỗi khi quay lại app. */
    private void setupNext() {
        SharedPreferences sp = getSharedPreferences("setup", 0);
        if (!Settings.System.canWrite(this) && !sp.getBoolean("ws", false)) {
            sp.edit().putBoolean("ws", true).apply();
            go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); return;
        }
        if (!notifAccess() && !sp.getBoolean("nl", false)) {
            sp.edit().putBoolean("nl", true).apply();
            go(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); return;
        }
        if (!a11yOn() && !sp.getBoolean("ax", false)) {
            sp.edit().putBoolean("ax", true).apply();
            go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return;
        }
        if (Build.VERSION.SDK_INT <= 29 && !hasSecureWrite() && !sp.getBoolean("ss", false)) {
            sp.edit().putBoolean("ss", true).apply();
            askSecureWrite(); return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted() && !sp.getBoolean("dnd", false)) {
            sp.edit().putBoolean("dnd", true).apply();
            go(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return;
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !sp.getBoolean("af", false)) {
            sp.edit().putBoolean("af", true).apply();
            go(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); return;
        }
    }


    private static final String SS_CMD = "pm grant com.desktopmini.launcher android.permission.WRITE_SECURE_SETTINGS";
    private boolean hasSecureWrite() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }
    /** Android 10 trở xuống: hỏi quyền để ẩn hẳn thanh trạng thái / thanh điều hướng khi mở app khác. */
    private void askSecureWrite() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Ẩn thanh trạng thái và thanh điều hướng")
            .setMessage("Để ẩn hẳn thanh thông báo và thanh điều hướng của hệ thống khi mở app khác, Desktop Mini cần quyền đặc biệt WRITE_SECURE_SETTINGS.\n\n"
                + "Android không có hộp xin quyền này, nên phải cấp bằng một trong hai cách:\n"
                + "• Máy đã root: bấm \"Cấp bằng root\".\n"
                + "• Máy tính: nối adb rồi chạy\nadb shell " + SS_CMD)
            .setPositiveButton("Cấp bằng root", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { grantByRoot(); } })
            .setNeutralButton("Sao chép lệnh adb", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    try {
                        ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(android.content.ClipData.newPlainText("adb", "adb shell " + SS_CMD));
                        android.widget.Toast.makeText(MainActivity.this, "Đã sao chép lệnh", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { }
                } })
            .setNegativeButton("Để sau", null)
            .show();
    }
    private void grantByRoot() {
        new Thread(new Runnable() { public void run() {
            boolean ok = false;
            try {
                Process p = Runtime.getRuntime().exec(new String[] { "su", "-c", SS_CMD });
                p.waitFor();
            } catch (Exception e) { }
            ok = hasSecureWrite();
            final boolean res = ok;
            runOnUiThread(new Runnable() { public void run() {
                android.widget.Toast.makeText(MainActivity.this, res ? "Đã cấp quyền, thanh hệ thống sẽ được ẩn khi mở app khác"
                    : "Không cấp được (máy chưa root?). Hãy dùng lệnh adb.", android.widget.Toast.LENGTH_LONG).show();
            } });
        } }).start();
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        askingRuntime = false;
        if (browserHost != null && c >= 2100 && c < 2200) { browserHost.handlePermissionsResult(c, p, r); return; }
        if (c == PERM_VOICE) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) launchVoiceSearch();
            else android.widget.Toast.makeText(this, "Cần cấp quyền micro để dùng tìm kiếm bằng giọng nói", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (c == PERM_AI) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) aiListen();
            else android.widget.Toast.makeText(this, "Cần cấp quyền micro để ra lệnh bằng giọng nói", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (c == 77) {
            final android.webkit.PermissionRequest q = micReq; micReq = null;
            if (q != null) answerWeb(q);
            return;
        }
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        root.post(new Runnable() { public void run() { setupNext(); } });
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (wordHost != null && wordHost.handleResult(req, res, data)) return;
        if (browserHost != null && browserHost.handleActivityResult(req, res, data)) return;
        if (req == REQ_OMEM_PICK) {
            if (res == RESULT_OK && data != null) {
                Uri[] u = null;
                android.content.ClipData cd = data.getClipData();
                if (cd != null) { u = new Uri[cd.getItemCount()]; for (int k = 0; k < u.length; k++) u[k] = cd.getItemAt(k).getUri(); }
                else if (data.getData() != null) u = new Uri[]{ data.getData() };
                if (u != null && u.length > 0) {
                    omemPending = u; omemUploadStarted = false;
                    omemToast("Đã chọn " + u.length + " tệp – đang gửi lên Drive");
                    if (omemReady) { omemUploadStarted = true; omemRunUpload(); }
                }
            }
            return;
        }
        if (req == REQ_EXTFILE) {
            if (extFileCb != null) { extFileCb.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(res, data)); extFileCb = null; }
            return;
        }
        if (req == REQ_VOICE) {
            if (res == RESULT_OK && data != null) {
                ArrayList<String> rs = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (rs != null && !rs.isEmpty()) {
                    String q = rs.get(0);
                    WebView tw = voiceKey == null ? null : ext.get(voiceKey);
                    if (tw != null) {   // nút mic trong cửa sổ YouTube / Duyệt web nhúng
                        String cu = tw.getUrl();
                        boolean onYt = "youtube".equals(voiceKey) || (cu != null && cu.contains("youtube.com"));
                        tw.loadUrl((onYt ? "https://www.youtube.com/results?search_query=" : "https://www.google.com/search?q=") + Uri.encode(q));
                    } else push("nativeVoiceResult", org.json.JSONObject.quote(q));
                }
            }
            return;
        }
        if (req == 91) push("nativeDelResult", res == RESULT_OK ? "1" : "0");
    }
    /** Ẩn thanh điều hướng ngay và thêm một lần sau 0,4 giây (một số máy tự hiện lại thanh khi app vừa quay về). */
    private void hideBars() {
        immersive();
        getWindow().getDecorView().postDelayed(new Runnable() { public void run() { immersive(); } }, 400);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    /** Đo phần màn hình thật nằm dưới vùng Desktop để Dock nổi phủ kín, không để hở giữa Dock và cửa sổ app. */
    /** Dock nổi vừa đổi vị trí/cỡ: đo lại. */
    static void dockMoved() { final MainActivity a = inst; if (a == null || a.root == null) return; a.runOnUiThread(new Runnable() { public void run() { a.reportDockGap(); } }); }
    private void reportDockGap() {
        try {
            int[] loc = new int[2]; root.getLocationOnScreen(loc);
            android.graphics.Point p = new android.graphics.Point(); getWindowManager().getDefaultDisplay().getRealSize(p);
            if (root.getHeight() > 0) LockService.setDesktopGap(p.y - (loc[1] + root.getHeight()));
        } catch (Throwable t) { }
    }
    @Override protected void onResume() {
        super.onResume();
        inFg = true; lastTouchAt = android.os.SystemClock.elapsedRealtime();
        imeUp = false; hideBars(); LockService.launcherResumed(); reportDockGap(); root.postDelayed(new Runnable() { public void run() { reportDockGap(); } }, 700); prefetchApps(); scanNewMedia(); startMediaWatch();
        for (java.util.Map.Entry<String, WebView> e : ext.entrySet()) { try { if (e.getValue().getVisibility() == View.VISIBLE) { e.getValue().onResume(); extHiddenAt.remove(e.getKey()); } } catch (Exception x) { } }
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        if (!askingRuntime) root.post(new Runnable() { public void run() { setupNext(); } });
    }

    /* Đăng ký vào thư viện (MediaStore) các ảnh/video/nhạc mới nằm trong bộ nhớ nhưng chưa được hệ thống quét (vd. ảnh chụp màn hình của trình duyệt trên Android 9 trở xuống), rồi nạp lại danh sách */
    private volatile boolean scanBusy = false, scanAgain = false;
    private static final java.util.HashSet<String> MEDIA_EXT = new java.util.HashSet<String>(java.util.Arrays.asList(
        "jpg","jpeg","png","gif","webp","bmp","heic","heif","mp4","mkv","webm","3gp","mov","avi","m4v","mp3","m4a","aac","wav","ogg","flac","opus"));

    /* ===== Theo dõi ảnh / video / tệp mới: có tệp mới là quét một lần và nạp lại danh sách ===== */
    boolean mediaWatching = false;
    private final java.util.ArrayList<android.os.FileObserver> fileObs = new java.util.ArrayList<android.os.FileObserver>();
    final Runnable doScan = new Runnable() { public void run() { scanMedia(true); } };
    private final Runnable doReload = new Runnable() { public void run() { try { web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null); } catch (Exception e) { } } };
    private final android.database.ContentObserver mediaObs = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            /* Thư viện vừa thay đổi (ảnh mới, quét xong...): chỉ nạp lại danh sách, không quét thêm để tránh lặp */
            invalidatePhotos();
            reaper.removeCallbacks(doReload); reaper.postDelayed(doReload, 800);
        }
    };
    private void startMediaWatch() {
        if (mediaWatching) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            && !(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            && !(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)) return;
        mediaWatching = true;
        try {
            getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
            getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
            getContentResolver().registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObs);
            getContentResolver().registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObs);
        } catch (Exception e) { }
        /* Tệp mới do app khác chép/tải/chụp vào mà hệ thống chưa kịp quét: theo dõi các thư mục chính */
        String[] subs = {"", "DCIM", "DCIM/Camera", "DCIM/Screenshots", "Pictures", "Pictures/Screenshots", "Pictures/Webandr", "Pictures/DesktopMini",
            "Download", "Movies", "Music", "Documents", "Pictures/Zalo", "Pictures/Messenger", "Pictures/Facebook"};
        for (File r : storageRoots()) for (String sub : subs) {
            File d = sub.isEmpty() ? r : new File(r, sub);
            if (!d.isDirectory()) continue;
            try {
                android.os.FileObserver fo = new android.os.FileObserver(d.getAbsolutePath(),
                    android.os.FileObserver.CREATE | android.os.FileObserver.MOVED_TO | android.os.FileObserver.CLOSE_WRITE) {
                    @Override public void onEvent(int ev, String path) {
                        if (path == null || path.startsWith(".")) return;
                        /* chờ tệp ghi xong rồi mới quét (gộp nhiều sự kiện liên tiếp thành một lần) */
                        reaper.removeCallbacks(doScan); reaper.postDelayed(doScan, 1500);
                    }
                };
                fo.startWatching(); fileObs.add(fo);
            } catch (Throwable e) { }
        }
    }
    private void stopMediaWatch() {
        if (!mediaWatching) return;
        mediaWatching = false;
        try { getContentResolver().unregisterContentObserver(mediaObs); } catch (Exception e) { }
        for (android.os.FileObserver fo : fileObs) { try { fo.stopWatching(); } catch (Exception e) { } }
        fileObs.clear();
        reaper.removeCallbacks(doScan); reaper.removeCallbacks(doReload);
    }
    /* Bộ nhớ đệm ảnh nhỏ: RAM (tối đa ~10 MB) + ổ đĩa (cacheDir/thumbs, tự dọn khi quá nhiều) */
    final android.util.LruCache<String, String> thumbMem = new android.util.LruCache<String, String>(10 * 1024 * 1024) {
        @Override protected int sizeOf(String k, String v) { return v.length() * 2; }
    };
    private void trimThumbs() {
        try {
            File[] fs = new File(getCacheDir(), "thumbs").listFiles();
            if (fs == null || fs.length <= 4000) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() { @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); } });
            for (int i = 0; i < fs.length - 3000; i++) fs[i].delete();
        } catch (Exception e) { }
    }
    void scanNewMedia() { scanMedia(false); }
    /** Gốc các ổ lưu trữ: bộ nhớ trong + thẻ nhớ ngoài (nếu có). */
    private java.util.ArrayList<File> storageRoots() {
        java.util.ArrayList<File> roots = new java.util.ArrayList<File>();
        try { roots.add(Environment.getExternalStorageDirectory()); } catch (Exception e) { }
        try {
            File[] fs = getExternalFilesDirs(null);
            if (fs != null) for (File f : fs) {
                if (f == null) continue;
                String ap = f.getAbsolutePath(); int k = ap.indexOf("/Android/data/");
                if (k > 0) { File r = new File(ap.substring(0, k)); if (r.isDirectory() && !roots.contains(r)) roots.add(r); }
            }
        } catch (Exception e) { }
        return roots;
    }
    /** Đường dẫn mọi ảnh/video/nhạc mà thư viện (MediaStore) đã biết. */
    private java.util.HashSet<String> knownMediaPaths() {
        java.util.HashSet<String> known = new java.util.HashSet<String>();
        try {
            Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.MediaColumns.DATA},
                MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (1,2,3)", null, null);
            if (c != null) { try { while (c.moveToNext()) { String d = c.getString(0); if (d != null) known.add(d); } } finally { c.close(); } }
        } catch (Exception e) { }
        return known;
    }
    /* Tìm mọi tệp ảnh/video/nhạc nằm trong bộ nhớ nhưng thư viện chưa biết (so với MediaStore, không dựa vào ngày sửa tệp) rồi nhờ hệ thống quét; xong thì nạp lại danh sách. */
    volatile String photosCache = null; volatile int photosGen = 0;
    void invalidatePhotos() { photosGen++; photosCache = null; }
    private final java.util.HashSet<String> triedMedia = new java.util.HashSet<String>();
    private static final String[] PRIO_DIRS = {"DCIM", "Pictures", "Download", "Movies", "Music", "Documents"};
    private void reloadUi() {
        invalidatePhotos();
        runOnUiThread(new Runnable() { public void run() { try { web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null); } catch (Exception e) { } } });
    }
    private void newestFirst(java.util.ArrayList<File> l) {
        java.util.Collections.sort(l, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
    }
    /** Nhờ hệ thống quét một lô tệp (tối đa 5000, mới nhất trước) và chờ quét xong. */
    private void scanBatch(java.util.ArrayList<File> files, java.util.HashSet<String> done) {
        int n = Math.min(files.size(), 5000); if (n == 0) return;
        final String[] paths = new String[n];
        for (int i = 0; i < n; i++) {
            File f = files.get(i); paths[i] = f.getAbsolutePath(); done.add(paths[i]);
            synchronized (triedMedia) { triedMedia.add(paths[i] + "|" + f.lastModified()); }
        }
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(n);
        android.media.MediaScannerConnection.scanFile(MainActivity.this, paths, null, new android.media.MediaScannerConnection.OnScanCompletedListener() {
            @Override public void onScanCompleted(String path, Uri uri) { latch.countDown(); }
        });
        try { latch.await(180, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { }
    }
    /* Tìm mọi tệp ảnh/video/nhạc nằm trong bộ nhớ nhưng thư viện chưa biết (so với MediaStore, không dựa vào ngày sửa tệp) rồi nhờ hệ thống quét.
       Hai lượt: thư mục ảnh/video chính quét trước và hiện ngay, phần còn lại của bộ nhớ quét sau. */
    void scanMedia(final boolean force) {
        if (scanBusy) { scanAgain = true; return; }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            && !(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            && !(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)) return;
        scanBusy = true; scanAgain = false;
        new Thread(new Runnable() { public void run() {
            try {
                trimThumbs();
                final java.util.HashSet<String> known = knownMediaPaths();
                final java.util.HashSet<String> done = new java.util.HashSet<String>();
                java.util.ArrayList<File> roots = storageRoots();
                java.util.ArrayList<File> f1 = new java.util.ArrayList<File>();
                for (File r : roots) for (String sub : PRIO_DIRS) { File d = new File(r, sub); if (d.isDirectory()) walkMedia(d, known, f1, 1); }
                newestFirst(f1);
                if (!f1.isEmpty()) { scanBatch(f1, done); reloadUi(); }
                java.util.ArrayList<File> f2 = new java.util.ArrayList<File>();
                for (File r : roots) walkMedia(r, known, f2, 0);
                java.util.Iterator<File> it = f2.iterator();
                while (it.hasNext()) if (done.contains(it.next().getAbsolutePath())) it.remove();
                newestFirst(f2);
                if (!f2.isEmpty()) { scanBatch(f2, done); reloadUi(); }
            } catch (Throwable e) { }
            finally {
                scanBusy = false;
                if (scanAgain) runOnUiThread(new Runnable() { public void run() { scanMedia(true); } });
            }
        } }).start();
    }
    private void walkMedia(File d, java.util.HashSet<String> known, java.util.ArrayList<File> out, int depth) {
        if (d == null || depth > 10 || out.size() > 20000) return;
        File[] fs = d.listFiles(); if (fs == null) return;
        for (File f : fs) { if (f.getName().equals(".nomedia")) return; }
        for (File f : fs) {
            String nm = f.getName();
            if (nm.startsWith(".")) continue;
            if (f.isDirectory()) { if (depth == 0 && nm.equals("Android")) continue; walkMedia(f, known, out, depth + 1); }
            else {
                int i = nm.lastIndexOf('.');
                if (i > 0 && f.length() > 0 && MEDIA_EXT.contains(nm.substring(i + 1).toLowerCase(java.util.Locale.ROOT)) && !known.contains(f.getAbsolutePath())) {
                    boolean tried; synchronized (triedMedia) { tried = triedMedia.contains(f.getAbsolutePath() + "|" + f.lastModified()); }
                    if (!tried) out.add(f);
                }
            }
        }
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        /* Đang xem video toàn màn hình mà bấm app khác / Launchpad / thanh menu ở Dock nổi: thoát toàn màn hình trước để app mới hiện lên TRÊN CÙNG (không bị video che) */
        extHideFull();
        if (i != null && i.getBooleanExtra("openMS", false)) { web.evaluateJavascript("window.nativeMS&&window.nativeMS()", null); return; } /* vuốt lên từ thanh Desktop */
        String mc = i == null ? null : i.getStringExtra("mbClick");
        if (mc != null) {   /* chạm vào thanh menu nổi: bấm nút tương ứng của thanh menu HTML */
            final String id = mc;
            web.postDelayed(new Runnable() { public void run() { runMenuClick(id); } }, i.getBooleanExtra("mbFront", false) ? 0 : 300);
            return;
        }
        String oa = i == null ? null : i.getStringExtra("openApp");
        if (oa != null) web.evaluateJavascript("window.nativeOpenApp&&window.nativeOpenApp(" + JSONObject.quote(oa) + ")", null);
        else web.evaluateJavascript("window.nativeHome&&window.nativeHome()", null);
    }
    void runMenuClick(String id) { web.evaluateJavascript("window.nativeMenuClick&&window.nativeMenuClick(" + JSONObject.quote(id) + ")", null); }
    @Override public void onBackPressed() { if (extFullView != null) { extHideFull(); return; } web.evaluateJavascript("window.nativeBack&&window.nativeBack()", null); }

    /** Mở trình duyệt mặc định của app ("Duyệt web"); có url thì mở thẳng trang đó trong thẻ mới. */
    void openWeb(String url) {
        Intent i = new Intent(this, WebBrowserActivity.class);
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) i.setData(Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { }
    }

    /** Cầu nối nút mic cho cửa sổ web nhúng (YouTube, Duyệt web): nhớ cửa sổ nào bấm để trả kết quả đúng nơi. */

    /** Chuẩn hóa đường dẫn từ trang web trước khi thao tác tệp: lấy đường dẫn thật (xóa ../ và liên kết tượng trưng),
     *  chỉ cho phép trong bộ nhớ ngoài (/storage, /mnt, thẻ nhớ), chặn thư mục dữ liệu riêng của app (cookie, đăng nhập, cơ sở dữ liệu). Sai thì trả null. */
    File safeFile(String path) {
        if (path == null || path.length() == 0 || path.indexOf('\0') >= 0) return null;
        try {
            File f = new File(path).getCanonicalFile();
            String c = f.getPath();
            String data = new File(getApplicationInfo().dataDir).getCanonicalPath();
            if (c.equals(data) || c.startsWith(data + "/") || c.startsWith("/data/") || c.equals("/data")) return null;
            String ext = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (c.equals(ext) || c.startsWith(ext + "/") || c.startsWith("/storage/") || c.startsWith("/mnt/")) return f;
        } catch (Exception e) { }
        return null;
    }

    private class WebVoice {
        final String k;
        WebVoice(String k) { this.k = k; }
        /** Chỉ nhận lệnh từ trang https đang hiển thị trong đúng WebView của mình (YouTube thì phải đúng tên miền YouTube), chặn trang khác lợi dụng cầu để bật mic. */
        @JavascriptInterface public void startVoiceSearch() { runOnUiThread(new Runnable() { public void run() {
            WebView wv = ext.get(k); String u = wv == null ? null : wv.getUrl();
            if (u == null || !u.startsWith("https://")) return;
            if ("youtube".equals(k) && !ytAllowed(Uri.parse(u))) return;
            voiceKey = k; launchVoiceSearch(); } }); }
    }

    /** Nút mic của thanh tìm kiếm Google trên Desktop: xin quyền micro rồi mở bộ nhận dạng giọng nói của Android; kết quả trả về JS qua nativeVoiceResult(text). */
    void launchVoiceSearch() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askingRuntime = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERM_VOICE);
            return;
        }
        Intent it = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag());
        it.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm trên Google");
        try { startActivityForResult(it, REQ_VOICE); }
        catch (Exception e) { android.widget.Toast.makeText(this, "Máy chưa có ứng dụng nhận dạng giọng nói (cần ứng dụng Google). Hãy cài hoặc cập nhật Google từ CH Play", android.widget.Toast.LENGTH_LONG).show(); }
    }

    /* ================= Nút AI giọng nói (góc trái dưới) ================= */
    private VoiceAi aiVoice;
    private static final int PERM_AI = 79;
    boolean aiAvailable() { if (aiVoice == null) aiVoice = new VoiceAi(this); return aiVoice.available(); }
    /** Bắt đầu nghe lệnh: xin quyền micro nếu chưa có; kết quả đẩy về giao diện qua window.aiVoice(...). */
    void aiListen() {
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askingRuntime = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, PERM_AI);
            push("aiVoice", "{\"t\":\"error\",\"code\":" + android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS + ",\"ask\":true}");
            return;
        }
        if (aiVoice == null) aiVoice = new VoiceAi(this);
        if (!aiVoice.available()) { push("aiVoice", "{\"t\":\"error\",\"code\":-1}"); return; }
        aiVoice.start();
    }
    void aiStop() { if (aiVoice != null) aiVoice.stop(); }
    void aiCancel() { if (aiVoice != null) aiVoice.cancel(); }

    /** Mở app con "YouTube". */
    void openYt() {
        Intent i = new Intent(this, YouTubeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { }
    }

    /* ================= Chụp ảnh màn hình (nút vuông góc phải dưới) ================= */
    private void shotDone(final boolean ok) {
        runOnUiThread(new Runnable() { public void run() {
            if (web != null) web.evaluateJavascript("window.shotDone&&window.shotDone(" + ok + ")", null);
        } });
    }

    /** Chụp cả cửa sổ (kể cả trang web nhúng/Gecko): PixelCopy từ Android 8, máy cũ hơn thì vẽ lại cây View. */
    void captureScreen() {
        final View dv = getWindow().getDecorView();
        final int w = dv.getWidth(), h = dv.getHeight();
        if (w <= 0 || h <= 0) { shotDone(false); return; }
        final Bitmap bm;
        try { bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); }
        catch (Throwable t) { shotDone(false); return; }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                android.view.PixelCopy.request(getWindow(), bm, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                    @Override public void onPixelCopyFinished(int r) {
                        if (r == android.view.PixelCopy.SUCCESS) saveShotAsync(bm); else shotDone(false);
                    }
                }, new android.os.Handler(android.os.Looper.getMainLooper()));
                return;
            } catch (Throwable t) { /* rơi xuống cách vẽ lại */ }
        }
        try { dv.draw(new Canvas(bm)); } catch (Throwable t) { shotDone(false); return; }
        saveShotAsync(bm);
    }

    private void saveShotAsync(final Bitmap bm) {
        new Thread(new Runnable() { public void run() {
            boolean ok = false;
            try { ok = saveShot(bm); } catch (Throwable t) { }
            try { bm.recycle(); } catch (Throwable t) { }
            shotDone(ok);
        } }).start();
    }

    /** Lưu PNG vào Pictures/Screenshots (ứng dụng Ảnh tự xếp vào mục "Ảnh chụp màn hình"). */
    private boolean saveShot(Bitmap bm) throws Exception {
        String nm = "Screenshot_" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".png";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, nm);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) return false;
            OutputStream os = getContentResolver().openOutputStream(u);
            boolean ok = bm.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(u, v, null, null);
            return ok;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"); dir.mkdirs();
        File f = new File(dir, nm);
        FileOutputStream os2 = new FileOutputStream(f);
        boolean ok2 = bm.compress(Bitmap.CompressFormat.PNG, 100, os2);
        os2.close();
        if (!ok2) return false;
        try {
            ContentValues v2 = new ContentValues();
            v2.put(MediaStore.Images.Media.DISPLAY_NAME, nm);
            v2.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v2.put(MediaStore.Images.Media.DATA, f.getAbsolutePath());
            getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v2);
        } catch (Exception e) { sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))); }
        return true;
    }

    /* ================= Tìm tệp trong máy (Spotlight) ================= */
    JSONArray findFiles(String q) {
        JSONArray arr = new JSONArray();
        q = q == null ? "" : q.trim();
        if (q.length() < 2) return arr;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            && !(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            && !(Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)) return arr;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        try {
            String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            Cursor c = getContentResolver().query(MediaStore.Files.getContentUri("external"),
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED},
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? ESCAPE '\\' AND " + MediaStore.MediaColumns.MIME_TYPE + " IS NOT NULL",
                new String[]{like}, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c != null) {
                try {
                    while (c.moveToNext() && arr.length() < 30) {
                        String path = c.getString(1);
                        if (path == null || path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/") || !seen.add(path)) continue;
                        File f = new File(path);
                        if (!f.isFile()) continue;
                        JSONObject o = new JSONObject();
                        o.put("n", c.getString(0)); o.put("p", path); o.put("s", c.getLong(2)); o.put("t", c.getLong(3));
                        arr.put(o);
                    }
                } finally { c.close(); }
            }
        } catch (Exception e) { }
        /* MediaStore chưa lập chỉ mục tệp nào khớp: duyệt thư mục (chỉ khi được cấp quyền mọi tệp) */
        if (arr.length() == 0 && (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())) {
            try {
                final String lq = q.toLowerCase(java.util.Locale.ROOT);
                java.util.ArrayDeque<File> dq = new java.util.ArrayDeque<File>();
                java.util.ArrayDeque<Integer> dp = new java.util.ArrayDeque<Integer>();
                dq.add(Environment.getExternalStorageDirectory()); dp.add(0);
                int visited = 0; long end = System.currentTimeMillis() + 3500;
                while (!dq.isEmpty() && arr.length() < 30 && visited < 15000 && System.currentTimeMillis() < end) {
                    File d = dq.poll(); int depth = dp.poll();
                    File[] l = d.listFiles(); if (l == null) continue;
                    for (File f : l) {
                        visited++;
                        String nm = f.getName();
                        if (nm.startsWith(".")) continue;
                        if (f.isDirectory()) { if (depth < 6 && !nm.equals("Android")) { dq.add(f); dp.add(depth + 1); } }
                        else if (nm.toLowerCase(java.util.Locale.ROOT).contains(lq) && arr.length() < 30) {
                            JSONObject o = new JSONObject();
                            o.put("n", nm); o.put("p", f.getAbsolutePath()); o.put("s", f.length()); o.put("t", f.lastModified() / 1000);
                            arr.put(o);
                        }
                    }
                }
            } catch (Exception e) { }
        }
        return arr;
    }

    /** Mở app "Ổ mềm" = Drive 3 hồ sơ (màn hình chọn Ảnh / Video / Tệp). */
    void openDriveHome() {
        Intent i = new Intent(this, com.desktopmini.launcher.drive3.HomeActivity.class);
        try { startActivity(i); } catch (Exception e) { }
    }

    /** Mở app con "Gõ văn bản". */
    void openTy() {
        Intent i = new Intent(this, TypingActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); } catch (Exception e) { }
    }

    void go(Intent i) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(i); } catch (Exception e) { } }

    /* ================= Trình duyệt nhúng trong cửa sổ: YouTube, Duyệt web, Gmail, Drive, Zalo, Facebook ================= */
    private static final String[] AD_HOSTS = {
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
        "propellerads.com", "adsterra.com", "exoclick.com", "pubmatic.com", "rubiconproject.com",
        "criteo.com", "scorecardresearch.com", "moatads.com", "amazon-adsystem.com"
    };
    WordHost wordHost;
    private static final int REQ_WORD = 1002;
    private static final int REQ_EXTFILE = 1003;
    private static final int REQ_VOICE = 1004;
    private static final int PERM_VOICE = 78;
    /** Cửa sổ web nhúng (khóa trong ext) đang chờ kết quả giọng nói; null = thanh tìm kiếm Google trên Desktop. */
    String voiceKey = null;
    private android.webkit.ValueCallback<Uri[]> extFileCb;
    private static final String[] MS_HOSTS = {
        "office.com", "office365.com", "microsoft365.com", "microsoft.com", "microsoftonline.com", "microsoftonline-p.com",
        "live.com", "sharepoint.com", "onedrive.com", "1drv.ms", "office.net", "msauth.net", "msftauth.net", "msocdn.com",
        "windows.net", "azureedge.net", "sway.com", "officeapps.live.com", "cloud.microsoft", "msn.com", "bing.com"
    };
    /** Khóa các app "Office online": chỉ cho vào các trang của Microsoft (Office, OneDrive, SharePoint, đăng nhập). */
    private static boolean msAllowed(Uri u) {
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (sc.equals("about") || sc.equals("blob") || sc.equals("data")) return true;
        if (!sc.equals("https") && !sc.equals("http")) return false;
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (h.length() == 0) return false;
        for (String d : MS_HOSTS) if (hostIs(h, d)) return true;
        return false;
    }
    private View extFullView;
    private android.webkit.WebChromeClient.CustomViewCallback extFullCb;

    private static boolean hostIs(String h, String d) { return h.equals(d) || h.endsWith("." + d); }
    private static boolean isAdHost(String h) { for (String d : AD_HOSTS) if (hostIs(h, d)) return true; return false; }
    /** Khóa "youtube": chỉ cho vào youtube.com / youtu.be và trang đăng nhập/đồng ý của Google. */
    private static boolean ytAllowed(Uri u) {
        String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (sc.equals("about")) return true;
        if (!sc.equals("https") && !sc.equals("http")) return false;
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase();
        if (h.length() == 0) return false;
        if (hostIs(h, "youtube.com") || h.equals("youtu.be") || hostIs(h, "youtube-nocookie.com")) {
            String p = u.getPath() == null ? "" : u.getPath();
            return !p.startsWith("/redirect");   // cổng đưa người xem ra web ngoài
        }
        return h.startsWith("accounts.google.") || h.startsWith("consent.google.");
    }
    private long extToastAt = 0;
    private void extToast(String m) {
        long now = System.currentTimeMillis();
        if (now - extToastAt < 1500) return;
        extToastAt = now;
        android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show();
    }
    /** Báo cho giao diện HTML địa chỉ/trạng thái hiện tại của WebView nhúng (thanh địa chỉ, nút lùi/tiến). */
    private void extNav(String key, WebView v, int progress) {
        if (web == null || v == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("url", v.getUrl() == null ? "" : v.getUrl()); o.put("back", v.canGoBack()); o.put("fwd", v.canGoForward()); o.put("p", progress);
            web.evaluateJavascript("window.nativeExtNav&&window.nativeExtNav(" + JSONObject.quote(key) + "," + o.toString() + ")", null);
        } catch (Exception e) { }
    }
    void extHideFull() {
        if (extFullView == null) return;
        try { root.removeView(extFullView); } catch (Exception e) { }
        extFullView = null; LockService.setVideoFull(false);
        if (extFullCb != null) { try { extFullCb.onCustomViewHidden(); } catch (Exception e) { } extFullCb = null; }
    }

    /** Tối đa số trang web nhúng (Zalo, Facebook, Drive, YouTube, Office...) sống cùng lúc, không tính "Gõ văn bản". Mở thêm thì trang ẩn lâu nhất bị đóng trước. */
    private static final int EXT_MAX = 3;
    private void capExt(String keep) {
        if (extFullView != null) return;
        try { if (((android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE)).isMusicActive()) return; } catch (Exception e) { }
        while (true) {
            int n = 0; String oldest = null; long ot = Long.MAX_VALUE;
            for (java.util.Map.Entry<String, WebView> e : ext.entrySet()) {
                String k = e.getKey(); if ("word".equals(k)) continue;
                n++;
                if (k.equals(keep) || e.getValue().getVisibility() == View.VISIBLE) continue;
                Long t = extHiddenAt.get(k); long tt = t == null ? 0L : t;
                if (tt < ot) { ot = tt; oldest = k; }
            }
            if (n < EXT_MAX || oldest == null) return;
            WebView v = ext.remove(oldest); zooms.remove(oldest); extHiddenAt.remove(oldest);
            if (v != null) { try { v.loadUrl("about:blank"); root.removeView(v); v.destroy(); } catch (Exception e) { } }
        }
    }

    /* ================= Ổ mềm nhúng trong cửa sổ (Android < 9): thấy Dock, các hồ sơ đổi cookie như bản Legacy ================= */
    private static final int REQ_OMEM_PICK = 1005;
    /** Hồ sơ đang mở trong cửa sổ Ổ mềm: "anh" / "video" / "tep" ("" = chưa mở). */
    String omemProfile = "";
    /** Tệp đã chọn bằng nút "Tải lên", chờ Drive gọi hộp chọn tệp để giao vào. */
    Uri[] omemPending;
    boolean omemReady, omemUploadStarted;

    private void omemToast(String m) { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show(); }

    /** Mở / chuyển hồ sơ: lưu cookie hồ sơ cũ, nạp cookie hồ sơ mới rồi tải lại Drive. PHẢI chạy trên luồng giao diện. */
    void omemSwitch(String profile, boolean upload) {
        if (!com.desktopmini.launcher.drive3.DriveEmbed.valid(profile)) return;
        WebView old = ext.get("omem");
        if (old != null && profile.equals(omemProfile)) { if (upload) omemPick(profile); return; }
        android.view.ViewGroup.LayoutParams lp = null; int vis = View.GONE;
        if (old != null) {
            lp = old.getLayoutParams(); vis = old.getVisibility();
            ext.remove("omem"); zooms.remove("omem"); extHiddenAt.remove("omem");
            try { old.loadUrl("about:blank"); root.removeView(old); old.destroy(); } catch (Exception e) { }
        }
        com.desktopmini.launcher.drive3.DriveEmbed.activate(this, profile);   /* PHẢI chạy trước khi tạo WebView của hồ sơ */
        omemProfile = profile; omemPending = null; omemReady = false; omemUploadStarted = false;
        WebView v = extGet("omem", com.desktopmini.launcher.drive3.DriveEmbed.START_URL);
        if (lp != null) {   /* giữ nguyên vị trí cũ trong cửa sổ: giao diện HTML không cần đặt lại */
            v.setLayoutParams(lp); v.setVisibility(vis);
            if (vis == View.VISIBLE) { try { v.onResume(); } catch (Exception e) { } }
        }
        if (upload) omemPick(profile);
    }

    private void omemPick(String profile) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(com.desktopmini.launcher.drive3.DriveEmbed.pickType(profile));
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_OMEM_PICK); } catch (Exception e) { omemToast("Không mở được trình chọn tệp"); }
    }

    /** Tự bấm "Mới" rồi "Tải tệp lên"; Drive gọi hộp chọn tệp thì onShowFileChooser đưa tệp đã chọn vào. */
    private void omemRunUpload() {
        omemClick(com.desktopmini.launcher.drive3.DriveEmbed.clickNewJs(), 8, new Runnable() {
            @Override public void run() { omemClick(com.desktopmini.launcher.drive3.DriveEmbed.clickUploadJs(), 6, null); }
        });
    }

    private void omemClick(final String js, final int tries, final Runnable next) {
        if (omemPending == null) return;   /* tệp đã được giao cho Drive */
        final WebView v = ext.get("omem");
        if (v == null) return;
        v.evaluateJavascript(js, new android.webkit.ValueCallback<String>() {
            @Override public void onReceiveValue(String r) {
                if ("true".equals(r)) { if (next != null) v.postDelayed(next, 800); return; }
                if (tries > 0) {
                    v.postDelayed(new Runnable() { @Override public void run() { omemClick(js, tries - 1, next); } }, 800);
                } else if (omemPending != null) {
                    omemToast("Chưa tự bấm được – hãy bấm Mới → Tải tệp lên, tệp đã chọn sẽ được gửi");
                }
            }
        });
    }

    BrowserHost browserGet(String url) {
        if (browserHost == null) {
            browserHost = new BrowserHost(this, new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override public kotlin.Unit invoke() {
                    if (web != null) web.evaluateJavascript("window.nativeCloseApp&&window.nativeCloseApp('webbrowser')", null);
                    return kotlin.Unit.INSTANCE;
                }
            });
            browserHost.start(url != null && url.startsWith("http") ? url : null);
            View v = browserHost.getRoot();
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(1, 1));
        }
        return browserHost;
    }
    void browserHide() { if (browserHost != null) { try { browserHost.getRoot().setVisibility(View.GONE); } catch (Exception e) { } } }

    WebView extGet(final String key, String url) {
        WebView v = ext.get(key);
        if (v == null && !"word".equals(key)) capExt(key);
        if (v == null && "word".equals(key)) {
            /* "Gõ văn bản": trình soạn thảo kiểu Word chạy offline trong cửa sổ (phần lõi dùng chung ở WordHost) */
            v = new WebView(this);
            wordHost = new WordHost(this, v, REQ_WORD, new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override public kotlin.Unit invoke() {
                    if (web != null) web.evaluateJavascript("window.nativeCloseApp&&window.nativeCloseApp('typing')", null);
                    return kotlin.Unit.INSTANCE;
                }
            });
            ZoomHelper zw = ZoomHelper.attach(this, v, key);
            zooms.put(key, zw);
            wordHost.setZoom(zw);
            wordHost.setup();
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(1, 1));
            v.loadUrl(url);
            ext.put(key, v);
            return v;
        }
        if (v == null) {
            final boolean yt = "youtube".equals(key), br = "web".equals(key), ms = key.startsWith("ms-"), om = "omem".equals(key);
            v = new WebView(this);
            final WebView wv = v;
            final ZoomHelper zm = ZoomHelper.attach(this, v, key);
            zooms.put(key, zm);
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
            if (yt || br || ms || om) {
                s.setDatabaseEnabled(true);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setSupportMultipleWindows(false); s.setJavaScriptCanOpenWindowsAutomatically(false);
                s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
                s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            }
            if (yt) {
                /* UA như Chrome thật (bỏ "; wv" và "Version/4.0") + không gửi X-Requested-With: đăng nhập Google dễ hơn */
                s.setUserAgentString(WebSettings.getDefaultUserAgent(this).replace("; wv)", ")").replace(" Version/4.0", ""));
                try {
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                        androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, new java.util.HashSet<String>());
                } catch (Exception e) { }
                try {
                    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        java.util.HashSet<String> origins = new java.util.HashSet<String>();
                        origins.add("https://*.youtube.com"); origins.add("https://youtube.com");
                        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(v, "window.__wbAdSkip=true;" + YT_KEEP_JS + YouTubeActivityKt.YT_ONLY_AD_JS, origins);
                    }
                } catch (Exception e) { }
            } else {
                s.setUserAgentString(om ? cleanDeskUa() : ("gmail".equals(key) || "drive".equals(key) || "zalo".equals(key) || "facebook".equals(key) || br || ms) ? UA_DESK : UA);
                if (ms || om) { /* Microsoft / Ổ mềm (Google): bỏ X-Requested-With để trang đăng nhập không coi là "ứng dụng không an toàn" */
                    s.setJavaScriptCanOpenWindowsAutomatically(true);   // đăng nhập Microsoft/Google có thể mở cửa sổ phụ
                    try {
                        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                            androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, new java.util.HashSet<String>());
                    } catch (Exception e) { }
                }
            }
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(v, true);
            if (yt || br) v.addJavascriptInterface(new WebVoice(key), "AndroidVoice");
            v.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    if (om) {   /* Ổ mềm: chỉ http(s), như màn hình hồ sơ gốc; ghi nhớ tên miền để lưu/nạp cookie đúng chỗ */
                        com.desktopmini.launcher.drive3.DriveEmbed.noteHost(req.getUrl().getHost());
                        String osc = req.getUrl().getScheme();
                        return !("http".equals(osc) || "https".equals(osc));
                    }
                    if (!(yt || br || ms) || !req.isForMainFrame()) return false;
                    Uri u = req.getUrl();
                    if (yt) {
                        if (ytAllowed(u)) return false;
                        extToast("Ứng dụng này chỉ mở được YouTube"); return true;   // không mở link ngoài
                    }
                    if (ms) {
                        if (msAllowed(u)) return false;
                        extToast("Ứng dụng này chỉ mở được Microsoft Office"); return true;   // không mở link ngoài
                    }
                    String sc = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                    if (sc.equals("http") || sc.equals("https") || sc.equals("about") || sc.equals("file")) return false;
                    if (sc.equals("intent") || sc.equals("market")) { extToast("Đã chặn liên kết mở ứng dụng khác"); return true; }
                    return false;
                }
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    if (om && req.getUrl() != null) com.desktopmini.launcher.drive3.DriveEmbed.noteHost(req.getUrl().getHost());
                    if (!(yt || br || ms) || req.getUrl() == null || req.getUrl().getHost() == null) return null;
                    String h = req.getUrl().getHost().toLowerCase();
                    if (isAdHost(h)) return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                    if (yt && req.isForMainFrame() && !ytAllowed(req.getUrl())) {   // chuyển hướng từ máy chủ ra ngoài YouTube
                        runOnUiThread(new Runnable() { public void run() { extToast("Ứng dụng này chỉ mở được YouTube"); } });
                        String html = "<meta name=viewport content='width=device-width,initial-scale=1'><body style='background:#111;color:#eee;font:16px sans-serif;text-align:center;padding:48px 20px'>Ứng dụng này chỉ mở được YouTube.<br><br>Bấm Quay lại để trở về.</body>";
                        try { return new android.webkit.WebResourceResponse("text/html", "utf-8", new java.io.ByteArrayInputStream(html.getBytes("UTF-8"))); } catch (Exception e) { return null; }
                    }
                    if (ms && req.isForMainFrame() && !msAllowed(req.getUrl())) {   // chuyển hướng từ máy chủ ra ngoài Office
                        runOnUiThread(new Runnable() { public void run() { extToast("Ứng dụng này chỉ mở được Microsoft Office"); } });
                        String html = "<meta name=viewport content='width=device-width,initial-scale=1'><body style='font:16px sans-serif;text-align:center;padding:48px 20px'>Ứng dụng này chỉ mở được Microsoft Office.<br><br>Bấm Quay lại để trở về.</body>";
                        try { return new android.webkit.WebResourceResponse("text/html", "utf-8", new java.io.ByteArrayInputStream(html.getBytes("UTF-8"))); } catch (Exception e) { return null; }
                    }
                    return null;
                }
                @Override public void onScaleChanged(WebView view, float oldScale, float newScale) { zm.onScaleChanged(oldScale, newScale); }
                @Override public void onPageStarted(WebView view, String u, android.graphics.Bitmap f) { zm.onPageStarted(); if (yt || br || ms) extNav(key, view, 10); }
                @Override public void doUpdateVisitedHistory(WebView view, String u, boolean r) { if (yt || br || ms) extNav(key, view, 50); }
                @Override public void onPageFinished(WebView view, String u) {
                    zm.onPageFinished(view);
                    if (om && u != null) {
                        String oh = Uri.parse(u).getHost();
                        if (oh != null && oh.equals("drive.google.com")) {
                            view.evaluateJavascript(com.desktopmini.launcher.drive3.DriveEmbed.tidyJs(), null);
                            omemReady = true;
                            /* đã chọn tệp bằng nút "Tải lên": vào tới trang Drive thì tự bấm Mới -> Tải tệp lên */
                            if (omemPending != null && !omemUploadStarted) { omemUploadStarted = true; view.postDelayed(new Runnable() { @Override public void run() { omemRunUpload(); } }, 1500); }
                        }
                    }
                    if (yt || br || ms) {
                        /* WebView cũ không hỗ trợ script lúc bắt đầu tải: chèn sau khi tải xong (script tự chống chạy 2 lần) */
                        try { String h = Uri.parse(u == null ? "" : u).getHost(); if (h != null && hostIs(h.toLowerCase(), "youtube.com")) view.evaluateJavascript("window.__wbAdSkip=true;" + YT_KEEP_JS + YouTubeActivityKt.YT_ONLY_AD_JS, null); } catch (Exception e) { }
                        if (u != null && u.startsWith("http") && (yt || br)) view.evaluateJavascript(YouTubeActivityKt.VOICE_HOOK_JS, null);   // nối nút mic
                        extNav(key, view, 100);
                    }
                    CookieManager.getInstance().flush();
                }
                @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                    if (req.isForMainFrame() && web != null) web.evaluateJavascript("window.nativeExtError&&window.nativeExtError(" + JSONObject.quote(key) + ")", null);
                }
            });
            if (yt || br || ms || om) {
                v.setWebChromeClient(new android.webkit.WebChromeClient() {
                    @Override public void onProgressChanged(WebView view, int p) { extNav(key, view, p); }
                    /* xem video toàn màn hình */
                    @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                        if (view == null) return;
                        if (extFullView != null) { cb.onCustomViewHidden(); return; }
                        extFullView = view; extFullCb = cb; LockService.setVideoFull(true);
                        view.setBackgroundColor(0xFF000000);
                        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                    }
                    @Override public void onHideCustomView() { extHideFull(); }
                    /* chọn tệp từ máy để mở trong Word/Excel/PowerPoint Online, Duyệt web */
                    @Override public boolean onShowFileChooser(WebView w, android.webkit.ValueCallback<Uri[]> cb, FileChooserParams params) {
                        if (om && omemPending != null) {   /* nút "Tải lên": giao thẳng tệp đã chọn cho Drive */
                            Uri[] given = omemPending; omemPending = null;
                            cb.onReceiveValue(given);
                            omemToast("Đang tải lên Drive…");
                            return true;
                        }
                        if (extFileCb != null) extFileCb.onReceiveValue(null);
                        extFileCb = cb;
                        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                        it.addCategory(Intent.CATEGORY_OPENABLE); it.setType("*/*");
                        if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        try { startActivityForResult(Intent.createChooser(it, "Chọn tệp"), REQ_EXTFILE); return true; }
                        catch (Exception e) { extFileCb = null; return false; }
                    }
                });
            }
            if (br || ms || om) {
                v.setDownloadListener(new android.webkit.DownloadListener() {
                    @Override public void onDownloadStart(String dUrl, String ua, String cd, String mime, long len) {
                        try {
                            android.app.DownloadManager.Request rq = new android.app.DownloadManager.Request(Uri.parse(dUrl));
                            String nm = android.webkit.URLUtil.guessFileName(dUrl, cd, mime);
                            rq.setMimeType(mime); rq.addRequestHeader("User-Agent", ua);
                            String ck = CookieManager.getInstance().getCookie(dUrl); if (ck != null) rq.addRequestHeader("Cookie", ck);
                            rq.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                            rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, nm);
                            ((android.app.DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(rq);
                            android.widget.Toast.makeText(MainActivity.this, "Đang tải: " + nm, android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Exception e) { android.widget.Toast.makeText(MainActivity.this, "Không tải được tệp", android.widget.Toast.LENGTH_SHORT).show(); }
                    }
                });
            }
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(1, 1));
            v.loadUrl(url);
            ext.put(key, v);
        }
        return v;
    }


    /* ================= Trình duyệt Gecko nhúng trong cửa sổ (chép từ repo dang-nhap-github) ================= */
    private static final String GK_HOME = "https://www.google.com/";
    private static final String GK_SEARCH = "https://www.google.com/search?q=";
    private static org.mozilla.geckoview.GeckoRuntime gkRuntime;
    org.mozilla.geckoview.GeckoSession gkSession;
    org.mozilla.geckoview.GeckoView gkView;
    private boolean gkBackOk, gkFwdOk;
    private String gkUrl = "";
    private int gkProg = 0;

    void gkPush() {
        if (web == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("url", gkUrl); o.put("back", gkBackOk); o.put("fwd", gkFwdOk); o.put("p", gkProg);
            web.evaluateJavascript("window.nativeGk&&window.nativeGk(" + o.toString() + ")", null);
        } catch (Exception e) { }
    }
    void gkToast(final String msg) {
        runOnUiThread(new Runnable() { public void run() { android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_LONG).show(); } });
    }
    private String gkHeader(org.mozilla.geckoview.WebResponse r, String key) {
        if (r.headers == null) return null;
        for (java.util.Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }
    private int gkDlSeq = 0;
    /** Báo tiến độ tải xuống cho giao diện (trang Tải xuống trong cửa sổ trình duyệt). */
    private void gkDl(final int id, final String name, final String state, final long got, final long total, final String msg) {
        runOnUiThread(new Runnable() { public void run() {
            if (web == null) return;
            try {
                JSONObject o = new JSONObject();
                o.put("id", id); o.put("name", name); o.put("state", state); o.put("got", got); o.put("total", total); o.put("msg", msg == null ? "" : msg);
                web.evaluateJavascript("window.nativeGkDl&&window.nativeGkDl(" + o.toString() + ")", null);
            } catch (Exception e) { }
        } });
    }
    private void gkDownload(final org.mozilla.geckoview.WebResponse r) {
        final String name0 = android.webkit.URLUtil.guessFileName(r.uri, gkHeader(r, "content-disposition"), gkHeader(r, "content-type"));
        String ct = gkHeader(r, "content-type");
        if (ct != null) { int sc = ct.indexOf(';'); if (sc >= 0) ct = ct.substring(0, sc); ct = ct.trim(); }
        final String mime = (ct == null || ct.length() == 0) ? "application/octet-stream" : ct;
        long tl = -1;
        try { String cl = gkHeader(r, "content-length"); if (cl != null) tl = Long.parseLong(cl.trim()); } catch (Exception e) { tl = -1; }
        final long total = tl;
        final int id = ++gkDlSeq;
        gkDl(id, name0, "run", 0, total, "");
        new Thread(new Runnable() { public void run() {
            Uri mu = null; OutputStream out = null; String finalName = name0, legacyPath = null;
            try {
                InputStream in = r.body;
                if (in == null) throw new java.io.IOException("không có dữ liệu");
                if (Build.VERSION.SDK_INT >= 29) {
                    /* Android 10+: ghi vào Download qua MediaStore (không cần quyền riêng) */
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name0);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    mu = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (mu == null) throw new java.io.IOException("không tạo được tệp");
                    out = getContentResolver().openOutputStream(mu);
                    Cursor c = getContentResolver().query(mu, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
                    if (c != null) { try { if (c.moveToFirst() && c.getString(0) != null) finalName = c.getString(0); } finally { c.close(); } }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!(dir.exists() || dir.mkdirs()) || !dir.canWrite()) dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, name0);
                    int i = 1; String base = name0, ex = "";
                    int dot = name0.lastIndexOf('.');
                    if (dot > 0) { base = name0.substring(0, dot); ex = name0.substring(dot); }
                    while (f.exists()) f = new File(dir, base + " (" + (i++) + ")" + ex);
                    finalName = f.getName();
                    out = new FileOutputStream(f);
                    legacyPath = f.getAbsolutePath();
                }
                if (out == null) throw new java.io.IOException("không ghi được tệp");
                long got = 0, lastPush = 0;
                try {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); got += n;
                        long now = System.currentTimeMillis();
                        if (now - lastPush > 250) { lastPush = now; gkDl(id, finalName, "run", got, total, ""); }
                    }
                } finally { out.close(); }
                in.close();
                if (mu != null) {
                    android.content.ContentValues v2 = new android.content.ContentValues();
                    v2.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(mu, v2, null, null);
                } else if (legacyPath != null) {
                    android.media.MediaScannerConnection.scanFile(MainActivity.this, new String[]{legacyPath}, null, null);
                }
                gkDl(id, finalName, "ok", got, got, "");
            } catch (Exception e) {
                if (mu != null) { try { getContentResolver().delete(mu, null, null); } catch (Exception x) { } }
                gkDl(id, finalName, "err", 0, total, String.valueOf(e.getMessage()));
            }
        } }).start();
    }
    String gkToUrl(String text) {
        if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("file://") || text.startsWith("about:")) return text;
        if (!text.contains(" ") && text.contains(".")) return "https://" + text;
        return GK_SEARCH + Uri.encode(text);
    }
    private float gkZoom = 1f;
    private void gkApplyZoom() {
        try {
            gkRuntime.getSettings().setAutomaticFontSizeAdjustment(false);
            gkRuntime.getSettings().setFontSizeFactor(gkZoom);
        } catch (Throwable t) { }
    }
    private void gkStep(float f) {
        gkZoom = Math.max(0.5f, Math.min(3f, gkZoom * f));
        gkApplyZoom();
        getSharedPreferences("dm_zoom", MODE_PRIVATE).edit().putFloat("gecko", gkZoom).apply();
    }
    void gkEnsure() {
        if (gkView != null) return;
        CrashLog.mark(this, "mở trình duyệt Gecko (gkEnsure)");
        if (gkRuntime == null) {
            gkRuntime = org.mozilla.geckoview.GeckoRuntime.create(getApplicationContext());
            /* cho phép 2 ngón zoom cả trang cấm zoom; nạp mức zoom chữ đã lưu */
            try { org.mozilla.geckoview.GeckoRuntimeSettings.class.getMethod("setForceUserScalableEnabled", boolean.class).invoke(gkRuntime.getSettings(), true); } catch (Throwable t) { }
            gkZoom = getSharedPreferences("dm_zoom", MODE_PRIVATE).getFloat("gecko", 1f);
            if (Math.abs(gkZoom - 1f) > 0.01f) gkApplyZoom();
        }
        gkUrl = GK_HOME; gkBackOk = false; gkFwdOk = false; gkProg = 0;
        gkView = new org.mozilla.geckoview.GeckoView(this);
        /* TextureView để lớp nổi nằm đúng thứ tự trên giao diện HTML */
        gkView.setViewBackend(org.mozilla.geckoview.GeckoView.BACKEND_TEXTURE_VIEW);
        /* Ctrl + lăn chuột / Ctrl + - 0: đổi cỡ chữ cả trang (lưu lại); 2 ngón dùng zoom gốc của Gecko */
        gkView.setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override public boolean onGenericMotion(View view, android.view.MotionEvent e) {
                if (e.getAction() != android.view.MotionEvent.ACTION_SCROLL || (e.getMetaState() & android.view.KeyEvent.META_CTRL_ON) == 0) return false;
                float dy = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL);
                if (dy == 0) return false;
                gkStep((float) Math.pow(1.1, dy)); return true;
            }
        });
        gkView.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int code, android.view.KeyEvent e) {
                if (!e.isCtrlPressed()) return false;
                boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
                if (code == android.view.KeyEvent.KEYCODE_PLUS || code == android.view.KeyEvent.KEYCODE_EQUALS || code == android.view.KeyEvent.KEYCODE_NUMPAD_ADD) { if (down) gkStep(1.1f); return true; }
                if (code == android.view.KeyEvent.KEYCODE_MINUS || code == android.view.KeyEvent.KEYCODE_NUMPAD_SUBTRACT) { if (down) gkStep(1f / 1.1f); return true; }
                if (code == android.view.KeyEvent.KEYCODE_0 || code == android.view.KeyEvent.KEYCODE_NUMPAD_0) { if (down) gkStep(1f / gkZoom); return true; }
                return false;
            }
        });
        final org.mozilla.geckoview.GeckoSession se = new org.mozilla.geckoview.GeckoSession();
        gkSession = se;
        se.setContentDelegate(new org.mozilla.geckoview.GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(org.mozilla.geckoview.GeckoSession s, org.mozilla.geckoview.WebResponse response) { gkDownload(response); }
            @Override public void onCrash(org.mozilla.geckoview.GeckoSession s) { CrashLog.log(MainActivity.this, "Gecko: tiến trình trang web bị văng (onCrash)", new Exception("onCrash")); gkToast("Trang web bị văng. Chi tiết: " + CrashLog.where(MainActivity.this)); }
            @Override public void onKill(org.mozilla.geckoview.GeckoSession s) { CrashLog.log(MainActivity.this, "Gecko: tiến trình trang web bị hệ thống tắt, thường do thiếu RAM (onKill)", new Exception("onKill")); gkToast("Trang web bị tắt (có thể thiếu RAM). Chi tiết: " + CrashLog.where(MainActivity.this)); }
        });
        se.setProgressDelegate(new org.mozilla.geckoview.GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(org.mozilla.geckoview.GeckoSession s, String url) { if (url != null) gkUrl = url; gkProg = 5; gkPush(); }
            @Override public void onProgressChange(org.mozilla.geckoview.GeckoSession s, int p) { gkProg = p; gkPush(); }
            @Override public void onPageStop(org.mozilla.geckoview.GeckoSession s, boolean success) { gkProg = 0; gkPush(); if (success) CrashLog.clearMark(MainActivity.this); }
        });
        se.setNavigationDelegate(new org.mozilla.geckoview.GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(org.mozilla.geckoview.GeckoSession s, boolean can) { gkBackOk = can; gkPush(); }
            @Override public void onCanGoForward(org.mozilla.geckoview.GeckoSession s, boolean can) { gkFwdOk = can; gkPush(); }
            @Override public org.mozilla.geckoview.GeckoResult<org.mozilla.geckoview.GeckoSession> onNewSession(org.mozilla.geckoview.GeckoSession s, String uri) {
                /* Link mở tab mới -> mở ngay trong tab hiện tại */
                se.loadUri(uri); return null;
            }
        });
        se.open(gkRuntime);
        gkView.setSession(se);
        gkView.setVisibility(View.GONE);
        root.addView(gkView, new FrameLayout.LayoutParams(1, 1));
        se.loadUri(GK_HOME);
    }
    void gkHideNow() { if (gkView != null) gkView.setVisibility(View.GONE); }

    /** Giao diện cần WebView (Chrome) từ bản 105 trở lên; máy Android 7 thường phải cập nhật Android System WebView. */
    private void warnOldWebView() {
        try {
            String ua = web.getSettings().getUserAgentString();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(ua == null ? "" : ua);
            if (!m.find()) return;
            int v = Integer.parseInt(m.group(1));
            android.content.SharedPreferences w = getSharedPreferences("wvwarn", 0);
            if (v < 105 && w.getInt("shown", 0) != v) {
                w.edit().putInt("shown", v).apply();
                android.widget.Toast.makeText(this, "WebView hệ thống (Chrome " + v + ") quá cũ, giao diện có thể lỗi. Hãy cập nhật \"Android System WebView\" trong Google Play.", android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable e) { }
    }

    volatile String appsCache;
    /* Mốc thời gian lần dựng danh sách app gần nhất: giao diện so sánh số này để biết có cần nạp lại danh sách không */
    volatile long appsVer = 0;
    /** Dựng sẵn danh sách app cài đặt (kèm icon) ở luồng nền để Launchpad mở ngay, không khựng. */
    private long appsAt;
    private void prefetchApps() {
        long now = System.currentTimeMillis();
        if (appsCache != null && now - appsAt < 30000) return;
        appsAt = now;
        new Thread(new Runnable() { public void run() {
            try { LauncherBridge b = new LauncherBridge(MainActivity.this); String r = b.buildAppsNow(); appsCache = r; appsVer = System.currentTimeMillis(); } catch (Throwable e) { }
        } }, "apps-prefetch").start();
    }

    static String icon(Drawable d, int n) {
        Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm); d.setBounds(0, 0, n, n); d.draw(c);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 90, os);
        return "data:image/png;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
    }

}
