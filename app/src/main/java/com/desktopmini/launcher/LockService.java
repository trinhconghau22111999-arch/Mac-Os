package com.desktopmini.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.HashSet;
import java.util.Set;

/**
 * Dịch vụ trợ năng: khóa màn hình, menu nguồn, và Dock nổi trên các app khác.
 * Khi đang ở app khác, vuốt lên từ mép dưới (hoặc rê chuột xuống đáy) thì Dock hiện lên như cửa sổ nổi.
 */
public class LockService extends AccessibilityService {
    public static LockService inst;
    private static final String PK = "dock";

    private WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private View strip;
    private DockLayout dock;
    private WebView web;
    private boolean webReady, launcherFront, enabled = true;
    private final Runnable autoHide = new Runnable() { public void run() { hideDock(); } };

    /** Gọi từ MainActivity: lưu dữ liệu biểu tượng Dock (JSON) để vẽ trong cửa sổ nổi. */
    public static void saveData(Context c, String json) { c.getSharedPreferences(PK, 0).edit().putString("data", json).apply(); }
    public static void saveEnabled(Context c, boolean on) {
        c.getSharedPreferences(PK, 0).edit().putBoolean("on", on).apply();
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.apply(); } });
    }
    public static boolean isEnabled(Context c) { return c.getSharedPreferences(PK, 0).getBoolean("on", true); }

    @Override protected void onServiceConnected() {
        inst = this; wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE); apply();
    }
    @Override public boolean onUnbind(Intent i) { inst = null; tearDown(); return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; tearDown(); super.onDestroy(); }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c); hideDock(); removeStrip(); updateStrip();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pk = e.getPackageName().toString();
        if (pk.equals(getPackageName())) {
            CharSequence c = e.getClassName();
            if (c != null && c.toString().endsWith(".MainActivity")) setLauncherFront(true);
            return; /* cửa sổ nổi của chính mình: bỏ qua */
        }
        if (pk.equals("com.android.systemui") || imePackages().contains(pk)) return; /* thanh thông báo, bàn phím: không đổi app đang mở */
        setLauncherFront(false);
    }
    @Override public void onInterrupt() { }

    private Set<String> imePackages() {
        Set<String> s = new HashSet<String>();
        try {
            InputMethodManager m = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : m.getEnabledInputMethodList()) s.add(i.getPackageName());
        } catch (Exception ex) { }
        return s;
    }

    private void apply() {
        enabled = isEnabled(this);
        if (!enabled) { hideDock(); removeStrip(); } else updateStrip();
    }
    private void setLauncherFront(boolean b) {
        if (launcherFront == b) return;
        launcherFront = b;
        if (b) hideDock();
        updateStrip();
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    /** Chiều cao thanh 3 phím điều hướng của Android (0 nếu dùng cử chỉ), để dải kích hoạt nằm trên nó. */
    private int navBarH() {
        try {
            android.graphics.Point real = new android.graphics.Point(), size = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(real); wm.getDefaultDisplay().getSize(size);
            return Math.max(0, real.y - size.y);
        } catch (Exception e) { return 0; }
    }
    private int screenW() { return getResources().getDisplayMetrics().widthPixels; }

    /* ---- Dải kích hoạt ở mép dưới ---- */
    private void updateStrip() {
        boolean want = enabled && !launcherFront && wm != null;
        if (want && strip == null) addStrip(); else if (!want) removeStrip();
    }
    private void addStrip() {
        final View v = new View(this);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setOnHoverListener(new View.OnHoverListener() {
            @Override public boolean onHover(View view, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE) showDock();
                return true;
            }
        });
        v.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            @Override public boolean onTouch(View view, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: y0 = ev.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE: if (y0 - ev.getRawY() > dp(16)) { showDock(); y0 = Float.MAX_VALUE; } return true;
                    default: return true;
                }
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(screenW() * 0.5f), dp(22),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; lp.y = navBarH();
        try { wm.addView(v, lp); strip = v; } catch (Exception e) { strip = null; }
    }
    private void removeStrip() {
        if (strip != null) { try { wm.removeView(strip); } catch (Exception e) { } strip = null; }
    }

    /* ---- Cửa sổ Dock nổi ---- */
    private class DockLayout extends FrameLayout {
        DockLayout(Context c) { super(c); }
        @Override public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideDock(); return true; }
            resetAutoHide();
            return super.dispatchTouchEvent(ev);
        }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent ev) { resetAutoHide(); return super.dispatchGenericMotionEvent(ev); }
    }
    private class DockJs {
        @JavascriptInterface public void open(final String id) {
            h.post(new Runnable() { public void run() {
                hideDock();
                try {
                    startActivity(new Intent(LockService.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra("openApp", id));
                } catch (Exception e) { }
            } });
        }
        @JavascriptInterface public void nav(final String k) {
            h.post(new Runnable() { public void run() {
                hideDock();
                final int act = "back".equals(k) ? GLOBAL_ACTION_BACK : "recents".equals(k) ? GLOBAL_ACTION_RECENTS : GLOBAL_ACTION_HOME;
                h.postDelayed(new Runnable() { public void run() { performGlobalAction(act); } }, 80);
            } });
        }
        @JavascriptInterface public void keep() { h.post(new Runnable() { public void run() { resetAutoHide(); } }); }
    }
    private void showDock() {
        if (wm == null || !enabled) return;
        if (dock != null && dock.isAttachedToWindow()) { resetAutoHide(); return; }
        if (dock == null) {
            dock = new DockLayout(this);
            web = new WebView(new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault));
            WebSettings ws = web.getSettings(); ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true);
            web.setBackgroundColor(Color.TRANSPARENT);
            web.setVerticalScrollBarEnabled(false); web.setHorizontalScrollBarEnabled(false);
            web.addJavascriptInterface(new DockJs(), "DockApp");
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView v, String url) { webReady = true; pushData(); }
            });
            dock.addView(web, new FrameLayout.LayoutParams(-1, -1));
            web.loadUrl("file:///android_asset/dock.html");
        } else pushData();
        int w = Math.min(screenW() - dp(16), dp(760));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, dp(92),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; lp.y = navBarH() + dp(10);
        try { wm.addView(dock, lp); resetAutoHide(); } catch (Exception e) { }
    }
    private void pushData() {
        if (web == null || !webReady) return;
        String d = getSharedPreferences(PK, 0).getString("data", "[]");
        web.evaluateJavascript("window.setDock&&setDock(" + d + ");window.reveal&&reveal()", null);
    }
    private void resetAutoHide() { h.removeCallbacks(autoHide); h.postDelayed(autoHide, 4500); }
    private void hideDock() {
        h.removeCallbacks(autoHide);
        if (dock != null && dock.isAttachedToWindow()) { try { wm.removeView(dock); } catch (Exception e) { } }
    }
    private void tearDown() {
        hideDock(); removeStrip();
        if (web != null) { try { web.destroy(); } catch (Exception e) { } web = null; }
        dock = null; webReady = false;
    }
}
