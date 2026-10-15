package com.desktopmini.launcher.drive3;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import java.util.ArrayList;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Một hồ sơ Drive. Mỗi lớp con chạy trong process riêng (xem AndroidManifest)
 * và dùng thư mục dữ liệu WebView riêng => tài khoản Google tách biệt.
 */
public abstract class DriveActivity extends Activity {

    protected abstract String suffix();
    protected abstract String startUrl();
    protected abstract String label();
    protected abstract int accent();
    protected abstract String pickType();

    private static final int RC_CHOOSER = 12;
    private static final int RAIL_BG = 0xFF16181D;

    /** Số màn hình hồ sơ còn sống trong process này (mỗi hồ sơ Android 9+ có process riêng). */
    private static int alive = 0;

    private WebView web;
    private ProgressBar bar;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private ValueCallback<Uri[]> chooserCb;
    private Uri[] pending;
    private boolean pageReady;
    private boolean uploadStarted;

    // --- Theo dõi tiến độ tải lên ---
    private LinearLayout upBox;
    private TextView upText;
    private ProgressBar upBar;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean tracking;
    private boolean sawBusy;
    private int idlePolls;
    private int stalePolls;
    private long lastBytes;
    private long totalBytes;
    private int fileCount;
    private boolean askedPerms;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive++;
        // setDataDirectorySuffix chỉ có từ Android 9 (API 28). Android 7.1.1 không có hàm này:
        // gọi thẳng sẽ văng app, nên phải kiểm tra phiên bản.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WebView.setDataDirectorySuffix(suffix());
            } catch (IllegalStateException ignored) { }
        }

        // Android < 9: 3 hồ sơ chạy chung 1 process nên đăng nhập được tách bằng cách đổi cookie
        // (xem DriveCookies). Việc đổi cookie sẽ làm đứt tải lên của hồ sơ khác, nên chặn mở hồ sơ
        // này khi hồ sơ kia còn đang tải lên dở.
        if (DriveCookies.enabled()) {
            String busyProfile = DriveCookies.uploading();
            if (!busyProfile.isEmpty() && !busyProfile.equals(suffix())) {
                toast("Đang tải lên ở hồ sơ " + profileName(busyProfile)
                        + " – đợi tải xong rồi mở hồ sơ này");
                finish();
                return;
            }
            CookieManager.getInstance().setAcceptCookie(true);
            DriveCookies.activate(this, suffix()); // PHẢI chạy trước khi tạo WebView
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(RAIL_BG);

        buildUi();
        setupWebView();
        web.loadUrl(startUrl());
        // Chỉ mở trình chọn tệp lần đầu, không lặp lại khi activity được dựng lại (recreate)
        if (b == null && getIntent().getBooleanExtra("sync", false)) pickFiles();
        else askPerms();
    }

    private static String profileName(String suffix) {
        switch (suffix) {
            case "anh": return "Ảnh";
            case "video": return "Video";
            default: return "Tệp";
        }
    }

    /** Xin quyền thông báo (Android 13+) và ghi thư mục Download (Android 7–9). */
    private void askPerms() {
        if (askedPerms || Build.VERSION.SDK_INT < 23) return;
        askedPerms = true;
        ArrayList<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 21);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(accent()));
        root.addView(bar, new FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP));

        // Nút "<" đậm giữa hàng tiêu đề, bấm để quay lại (hết lịch sử thì về màn hình chính)
        TextView back = new TextView(this);
        back.setText("<");
        back.setTextColor(Color.WHITE);
        back.setTextSize(26);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xCC202124);
        pill.setCornerRadius(dp(18));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(18));
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), pill, mask));
        back.setElevation(dp(4));
        back.setClickable(true);
        back.setOnClickListener(v -> onBackPressed());
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(64), dp(36),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bp.topMargin = dp(6);
        root.addView(back, bp);

        // Khung tiến độ tải lên ở đáy màn hình (ẩn cho tới khi có tệp đang tải)
        upBox = new LinearLayout(this);
        upBox.setOrientation(LinearLayout.VERTICAL);
        upBox.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable upBg = new GradientDrawable();
        upBg.setColor(0xE6202124);
        upBg.setCornerRadius(dp(14));
        upBox.setBackground(upBg);
        upBox.setElevation(dp(6));
        upBox.setVisibility(View.GONE);

        upText = new TextView(this);
        upText.setTextColor(Color.WHITE);
        upText.setTextSize(14);
        upText.setTypeface(Typeface.DEFAULT_BOLD);
        upBox.addView(upText, new LinearLayout.LayoutParams(-2, -2));

        upBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        upBar.setMax(100);
        upBar.setProgressTintList(ColorStateList.valueOf(accent()));
        LinearLayout.LayoutParams ubp = new LinearLayout.LayoutParams(-1, dp(6));
        ubp.topMargin = dp(6);
        upBox.addView(upBar, ubp);

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(dp(300), -2,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        boxLp.bottomMargin = dp(12);
        root.addView(upBox, boxLp);

        setContentView(root);
    }

    // ================= Tiến độ tải lên =================

    /**
     * Chạy trong trang Drive: đếm số byte đã gửi (qua XHR upload) và đọc dòng trạng thái
     * "Đang tải N mục lên / Upload complete" của Drive. Trả về chuỗi "bận|xong|lỗi|byte".
     */
    private static final String UP_JS = """
(function(){
  if(window.__d3u) return;
  var done=0, live={}, id=0;
  try{
    var P=XMLHttpRequest.prototype, send=P.send;
    P.send=function(body){
      try{
        if(body && this.upload){
          var k=++id, x=this;
          live[k]=0;
          x.upload.addEventListener('progress',function(e){live[k]=e.loaded||0;});
          x.addEventListener('loadend',function(){done+=(live[k]||0);delete live[k];});
        }
      }catch(e){}
      return send.apply(this,arguments);
    };
  }catch(e){}
  var rb=/^(uploading|đang tải)/i;
  var rd=/(upload complete|uploads complete|tải lên xong|đã tải lên|đã tải .* lên|tải lên hoàn tất)/i;
  var rf=/(upload failed|uploads failed|tải lên không thành công|không tải lên được|tải lên thất bại)/i;
  window.__d3reset=function(){done=0;live={};};
  window.__d3u=function(){
    var b=0,d=0,f=0,l=done,k;
    for(k in live) l+=live[k];
    try{
      var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n,c=0;
      while((n=w.nextNode()) && c++<30000){
        var t=n.nodeValue;
        if(!t||t.length>90) continue;
        t=t.replace(/\\s+/g,' ').trim();
        if(rb.test(t)) b=1;
        else if(rf.test(t)) f=1;
        else if(rd.test(t)) d=1;
      }
    }catch(e){}
    return b+'|'+d+'|'+f+'|'+l;
  };
})();
""";

    private long sizeOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) { }
        return 0;
    }

    private int notifId() {
        return 2000 + 2 * (suffix().hashCode() & 0x3FF);
    }

    private Class<?> serviceClass() {
        // Hồ sơ Legacy chạy trong process chính nên dịch vụ nền cũng phải ở process chính
        if (DriveCookies.enabled()) return UploadService.L.class;
        switch (suffix()) {
            case "anh": return UploadService.P1.class;
            case "video": return UploadService.P2.class;
            default: return UploadService.P3.class;
        }
    }

    /** Gọi khi tệp đã được giao cho Drive: bật giữ sáng màn hình, dịch vụ nền và thanh tiến độ. */
    private void beginUpload(Uri[] files) {
        if (files == null || files.length == 0) return;
        totalBytes = 0;
        for (Uri u : files) totalBytes += sizeOf(u);
        fileCount = files.length;
        sawBusy = false;
        idlePolls = 0;
        stalePolls = 0;
        lastBytes = 0;
        DriveCookies.setUploading(suffix()); // Android < 9: khoá việc đổi hồ sơ cho tới khi tải xong
        web.evaluateJavascript(UP_JS, null);
        web.evaluateJavascript("if(window.__d3reset)window.__d3reset();", null);

        // Giữ màn hình sáng trong lúc tải
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Dịch vụ nền: giữ process + CPU + Wi-Fi, kèm thông báo tiến độ
        try {
            Intent si = new Intent(this, serviceClass());
            si.putExtra("id", notifId());
            si.putExtra("cls", getClass().getName());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(si);
            else startService(si);
        } catch (Exception ignored) { }

        showProgress(-1);
        if (!tracking) {
            tracking = true;
            ui.postDelayed(poll, 1000);
        }
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!tracking) return;
            web.evaluateJavascript("(window.__d3u?window.__d3u():'')", v -> {
                if (!tracking) return;
                handlePoll(v);
                if (tracking) ui.postDelayed(poll, 1000);
            });
        }
    };

    private void handlePoll(String raw) {
        if (raw == null) return;
        String s = raw.replace("\"", "");
        String[] p = s.split("\\|");
        if (p.length < 4) {
            // Trang vừa tải lại, cài lại bộ đếm
            web.evaluateJavascript(UP_JS, null);
            return;
        }
        boolean busy = "1".equals(p[0]);
        boolean fin = "1".equals(p[1]);
        boolean failed = "1".equals(p[2]);
        long sent = 0;
        try { sent = Long.parseLong(p[3]); } catch (NumberFormatException ignored) { }

        if (busy) { sawBusy = true; idlePolls = 0; } else idlePolls++;
        if (sent > lastBytes) { lastBytes = sent; stalePolls = 0; } else stalePolls++;

        int pct = totalBytes > 0 ? (int) Math.min(99, sent * 100 / totalBytes) : -1;

        if (failed && !busy) { finishUpload(false); return; }
        // Drive đã hiện "xong" và hết dòng "đang tải"
        if (sawBusy && !busy && (fin || idlePolls >= 4)) { finishUpload(true); return; }
        // Không thấy dòng trạng thái nhưng đã gửi đủ byte và im lặng một lúc
        if (!sawBusy && totalBytes > 0 && sent >= totalBytes && idlePolls >= 6) {
            finishUpload(true);
            return;
        }
        // Bỏ cuộc theo dõi nếu 2 phút không có dấu hiệu nào (Drive không tải, hoặc đổi giao diện)
        if (!busy && stalePolls >= 120) { finishUpload(sent > 0); return; }

        showProgress(pct);
    }

    private void showProgress(int pct) {
        String t = "Đang tải lên " + fileCount + " tệp" + (pct >= 0 ? " · " + pct + "%" : "…");
        upText.setText(t);
        upBar.setIndeterminate(pct < 0);
        if (pct >= 0) upBar.setProgress(pct);
        upBox.setVisibility(View.VISIBLE);
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                    notifId(), UploadService.build(this, getClass().getName(), pct, t, true));
        } catch (Exception ignored) { }
    }

    private void finishUpload(boolean ok) {
        tracking = false;
        ui.removeCallbacks(poll);
        if (suffix().equals(DriveCookies.uploading())) DriveCookies.setUploading("");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        upBox.setVisibility(View.GONE);
        try { stopService(new Intent(this, serviceClass())); } catch (Exception ignored) { }
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(notifId());
        } catch (Exception ignored) { }
        String msg = ok ? "Đã tải lên xong " + fileCount + " tệp" : "Tải lên chưa thành công";
        toast(msg);
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                    notifId() + 1,
                    UploadService.build(this, getClass().getName(), 100, msg, false));
        } catch (Exception ignored) { }
    }

    /**
     * Chạy trên trang Drive: tự chuyển sang dạng lưới và ẩn phần "Thư mục",
     * chỉ để lại phần "Tệp". Tìm theo chữ tiêu đề nên không phụ thuộc tên class của Drive.
     */
    static final String TIDY_JS = """
(function(){
  if(window.__d3) return; window.__d3=true;
  var gridDone=false, promoClicked=false;
  function norm(s){return (s||'').replace(/\\s+/g,' ').trim().toLowerCase();}
  function txtOf(e){return norm(e.innerText||e.textContent);}
  // Tìm phần tử chứa đúng đoạn chữ (duyệt theo text node, không phụ thuộc class của Drive)
  function find(labels, after){
    var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var n;
    while((n=w.nextNode())){
      if(labels.indexOf(norm(n.nodeValue))<0) continue;
      var e=n.parentElement;
      if(!e) continue;
      if(after && !(after.compareDocumentPosition(e) & 4)) continue;
      return e;
    }
    return null;
  }
  function hide(e){
    if(e && e!==document.body && e!==document.documentElement)
      e.style.setProperty('display','none','important');
  }
  // Ẩn nguyên 1 dòng menu có chữ này
  function hideRow(labels){
    var e=find(labels);
    if(!e) return;
    var t=txtOf(e), p=e;
    while(p.parentElement && p.parentElement!==document.body && txtOf(p.parentElement)===t) p=p.parentElement;
    hide(p);
  }
  // Bảng đề xuất "Tải ứng dụng Drive": ẩn và bấm "Không, cảm ơn" một lần
  function hidePromo(){
    var btn=find(['không, cảm ơn','no, thanks','no thanks']);
    if(!btn) return;
    var box=null, el=btn;
    while(el && el!==document.body){
      var role=el.getAttribute('role');
      if(getComputedStyle(el).position==='fixed'||role==='dialog'||role==='alertdialog'){box=el;break;}
      el=el.parentElement;
    }
    if(box && txtOf(box).length>=300) box=null;
    if(!box){
      var h=find(['tải ứng dụng drive','get the drive app']);
      if(h){
        var p=h;
        while(p.parentElement && !p.parentElement.contains(btn)) p=p.parentElement;
        box=p.parentElement;
        if(box && txtOf(box).length>=300) box=null;
      }
    }
    hide(box);
    if(!promoClicked){promoClicked=true;try{btn.click();}catch(x){}}
  }
  function hideFolders(){
    var f=find(['folders','thư mục']);
    if(!f) return;
    var g=find(['files','tệp'], f);
    if(!g) return;
    var p=f;
    while(p.parentElement && !p.parentElement.contains(g)) p=p.parentElement;
    if(p!==document.body && !p.contains(g)) hide(p);
  }
  function ensureGrid(){
    if(gridDone) return;
    var n=document.querySelectorAll('[aria-label],[data-tooltip]');
    for(var i=0;i<n.length;i++){
      var e=n[i];
      var t=(e.getAttribute('aria-label')||e.getAttribute('data-tooltip')||'').toLowerCase();
      var toGrid=(t.indexOf('grid')>=0&&t.indexOf('switch')>=0)||(t.indexOf('lưới')>=0&&t.indexOf('chuyển')>=0);
      if(toGrid){e.click();gridDone=true;return;}
    }
  }
  setInterval(function(){
    if(!document.body) return;
    try{
      hidePromo();
      hideRow(['tải ứng dụng google drive','get the drive app','get drive for desktop']);
      hideFolders();
      ensureGrid();
    }catch(x){}
  },900);
})();
""";

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        // Giữ nguyên User-Agent thật của WebView, không giả mạo.

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            // Android < 9: ghi nhớ các tên miền hồ sơ này đã truy cập để lưu/nạp lại cookie đúng chỗ
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView v, WebResourceRequest r) {
                if (DriveCookies.enabled()) DriveCookies.noteHost(r.getUrl().getHost());
                return super.shouldInterceptRequest(v, r);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (DriveCookies.enabled()) DriveCookies.noteHost(r.getUrl().getHost());
                String sch = r.getUrl().getScheme();
                return !("http".equals(sch) || "https".equals(sch));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) {
                bar.setVisibility(View.VISIBLE);
                if (url != null && "drive.google.com".equals(Uri.parse(url).getHost())) {
                    web.evaluateJavascript(UP_JS, null); // cài bộ đếm tải lên càng sớm càng tốt
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                if (url != null && "drive.google.com".equals(Uri.parse(url).getHost())) {
                    web.evaluateJavascript(UP_JS, null);
                    web.evaluateJavascript(TIDY_JS, null);
                    pageReady = true;
                    // Đã chọn tệp từ máy: vào tới trang Drive thì tự bấm Mới -> Tải tệp lên.
                    if (pending != null && !uploadStarted) {
                        uploadStarted = true;
                        web.postDelayed(() -> runUpload(), 1500);
                    }
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                bar.setProgress(p);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (chooserCb != null) chooserCb.onReceiveValue(null);
                chooserCb = null;
                if (pending != null) {
                    Uri[] given = pending;
                    cb.onReceiveValue(given);
                    pending = null;
                    toast("Đang tải lên Drive…");
                    beginUpload(given);
                    return true;
                }
                chooserCb = cb;
                try {
                    startActivityForResult(params.createIntent(), RC_CHOOSER);
                } catch (Exception e) {
                    chooserCb = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view;
                customCb = cb;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                com.desktopmini.launcher.LockService.setVideoFull(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                com.desktopmini.launcher.LockService.setVideoFull(false);
                if (customCb != null) customCb.onCustomViewHidden();
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                String ck = CookieManager.getInstance().getCookie(url);
                if (ck != null) r.addRequestHeader("Cookie", ck);
                r.addRequestHeader("User-Agent", ua);
                r.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, cd, mime));
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                toast("Đang tải xuống…");
            } catch (Exception e) {
                toast("Không tải xuống được");
            }
        });
    }

    private static final int RC_PICK = 11;

    static final String CLICK_NEW_JS = """
(function(){
  var L=['new','mới','tạo mới'];
  var n=document.querySelectorAll('[aria-label],[data-tooltip],div[role=button],button');
  for(var i=0;i<n.length;i++){
    var e=n[i];
    var t=(e.getAttribute('aria-label')||e.getAttribute('data-tooltip')||e.innerText||'').trim().toLowerCase();
    for(var j=0;j<L.length;j++){
      if(t===L[j]||t.endsWith('\\n'+L[j])){e.click();return true;}
    }
  }
  return false;
})();
""";

    static final String CLICK_UPLOAD_JS = """
(function(){
  var L=['file upload','tải tệp lên','upload files','upload file','tải lên tệp','upload','tải lên'];
  var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n;
  while((n=w.nextNode())){
    var t=(n.nodeValue||'').replace(/\\s+/g,' ').trim().toLowerCase();
    if(L.indexOf(t)<0) continue;
    var e=n.parentElement; if(!e) continue;
    var c=e.closest('[role=menuitem],[role=button],button,li')||e;
    c.click(); return true;
  }
  return false;
})();
""";

    /** Chọn tệp trong máy, lọc theo mục: ảnh / video / tệp. */
    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(pickType());
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, RC_PICK);
        } catch (Exception e) {
            toast("Không mở được trình chọn tệp");
        }
    }

    /** Tự bấm "Mới" rồi "Tải tệp lên"; Drive gọi hộp chọn tệp thì ta đưa tệp đã chọn vào. */
    private void runUpload() {
        clickStep(CLICK_NEW_JS, 8, () -> clickStep(CLICK_UPLOAD_JS, 6, null));
    }

    private void clickStep(final String js, final int tries, final Runnable next) {
        if (pending == null) return; // tệp đã được giao cho Drive
        web.evaluateJavascript(js, v -> {
            if ("true".equals(v)) {
                if (next != null) web.postDelayed(next, 800);
                return;
            }
            if (tries > 0) {
                web.postDelayed(() -> clickStep(js, tries - 1, next), 800);
            } else if (pending != null) {
                toast("Chưa tự bấm được – hãy bấm Mới → Tải tệp lên, tệp đã chọn sẽ được gửi");
            }
        });
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        if (rc == RC_PICK) {
            if (res == RESULT_OK && data != null) {
                Uri[] u = parse(data);
                if (u != null && u.length > 0) {
                    pending = u;
                    uploadStarted = false;
                    toast("Đã chọn " + u.length + " tệp – đang gửi lên Drive");
                    if (pageReady) {
                        uploadStarted = true;
                        runUpload();
                    }
                }
            }
        } else if (rc == RC_CHOOSER && chooserCb != null) {
            Uri[] chosen = WebChromeClient.FileChooserParams.parseResult(res, data);
            chooserCb.onReceiveValue(chosen);
            chooserCb = null;
            beginUpload(chosen); // tự bấm Mới -> Tải tệp lên cũng được theo dõi
        }
        askPerms();
    }

    private Uri[] parse(Intent d) {
        ClipData c = d.getClipData();
        if (c != null) {
            Uri[] u = new Uri[c.getItemCount()];
            for (int i = 0; i < u.length; i++) u[i] = c.getItemAt(i).getUri();
            return u;
        }
        return d.getData() != null ? new Uri[]{d.getData()} : null;
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (DriveCookies.enabled()) DriveCookies.captureIfActive(this, suffix());
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Android < 9: trong lúc ở nền có hồ sơ khác lấy quyền sở hữu cookie -> nạp lại cookie
        // của hồ sơ này rồi dựng lại màn hình (trang Drive được tải lại bằng đăng nhập đúng).
        if (DriveCookies.enabled() && web != null
                && !DriveCookies.active(this).equals(suffix())) {
            DriveCookies.activate(this, suffix());
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        tracking = false;
        ui.removeCallbacksAndMessages(null);
        if (suffix().equals(DriveCookies.uploading())) DriveCookies.setUploading("");
        if (upBox != null && upBox.getVisibility() == View.VISIBLE) {
            // Thoát giữa chừng: dừng dịch vụ nền và dọn thông báo
            try { stopService(new Intent(this, serviceClass())); } catch (Exception ignored) { }
            try {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(notifId());
            } catch (Exception ignored) { }
        }
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
        alive--;
        if (isFinishing() && alive <= 0) exitProfileProcess();
    }

    /**
     * Thoát hồ sơ = giải phóng luôn process riêng của hồ sơ (WebView Drive rất tốn RAM), lần mở sau dựng lại sạch.
     * Chỉ chạy ở process phụ ":p1/:p2/:p3" của Android 9+; TUYỆT ĐỐI không đụng process chính (Desktop Mini)
     * và không chạy ở bản Legacy (Android dưới 9, chung process chính).
     */
    private void exitProfileProcess() {
        if (DriveCookies.enabled() || Build.VERSION.SDK_INT < 28) return;
        String pn;
        try { pn = android.app.Application.getProcessName(); } catch (Throwable t) { return; }
        if (pn == null || pn.indexOf(':') < 0) return;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { android.os.Process.killProcess(android.os.Process.myPid()); }
        }, 600);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
