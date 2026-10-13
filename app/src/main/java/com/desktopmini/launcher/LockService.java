package com.desktopmini.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
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
    private View topStrip, navCover;
    private LinearLayout topBar;
    private String fgPkg;
    private boolean policyApplied;
    private final Runnable autoHideTop = new Runnable() { public void run() { hideTopBar(); } };
    private final Runnable autoHide = new Runnable() { public void run() { hideDock(); } };

    /** Gọi từ MainActivity: lưu dữ liệu biểu tượng Dock (JSON) để vẽ trong cửa sổ nổi. */
    public static void saveData(Context c, String json) { c.getSharedPreferences(PK, 0).edit().putString("data", json).apply(); }
    public static void saveEnabled(Context c, boolean on) {
        c.getSharedPreferences(PK, 0).edit().putBoolean("on", on).apply();
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.apply(); } });
    }
    public static boolean isEnabled(Context c) { return c.getSharedPreferences(PK, 0).getBoolean("on", true); }

    @Override protected void onServiceConnected() {
        inst = this; wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (!isEnabled(this)) setImmersivePolicy(false);
        apply();
    }
    @Override public boolean onUnbind(Intent i) { inst = null; tearDown(); return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; tearDown(); super.onDestroy(); }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c); hideDock(); hideTopBar(); removeStrip(); updateStrip();
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
        fgPkg = pk;
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
        if (!enabled) { hideDock(); hideTopBar(); removeStrip(); setImmersivePolicy(false); } else updateStrip();
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
        if (want) {
            setImmersivePolicy(true);
            if (strip == null) addStrip();
            if (topStrip == null) addTopStrip();
            if (!policyApplied && navCover == null) addNavCover();
        } else { removeStrip(); hideTopBar(); }
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
        if (topStrip != null) { try { wm.removeView(topStrip); } catch (Exception e) { } topStrip = null; }
        if (navCover != null) { try { wm.removeView(navCover); } catch (Exception e) { } navCover = null; }
    }

    /* ---- Ẩn thanh trạng thái + thanh điều hướng của hệ thống ---- */
    /** Cách 1 (Android ≤ 10, cần quyền WRITE_SECURE_SETTINGS cấp qua adb): ẩn thật bằng policy_control. */
    private void setImmersivePolicy(boolean on) {
        if (Build.VERSION.SDK_INT > 29) { policyApplied = false; return; }
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) { policyApplied = false; return; }
            Settings.Global.putString(getContentResolver(), "policy_control", on ? "immersive.full=*" : null);
            policyApplied = on;
        } catch (Exception e) { policyApplied = false; }
    }
    private int statusBarH() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }
    private final View.OnTouchListener eat = new View.OnTouchListener() {
        @Override public boolean onTouch(View v, MotionEvent ev) { return true; }
    };
    /** Cách 2 (mọi máy): phủ tấm đen lên thanh nav 3 phím để che + chặn chạm. */
    private void addNavCover() {
        int nb = navBarH(); if (nb <= 0) return;
        View v = new View(this); v.setBackgroundColor(Color.BLACK); v.setOnTouchListener(eat);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, nb,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        try { wm.addView(v, lp); navCover = v; } catch (Exception e) { navCover = null; }
    }

    /* ---- Dải kích hoạt ở mép trên: vuốt xuống / rê chuột lên trên cùng -> hiện thanh 3 nút ---- */
    private void addTopStrip() {
        final View v = new View(this);
        /* Nếu không ẩn thật được thanh trạng thái thì phủ đen đúng chiều cao của nó */
        v.setBackgroundColor(policyApplied ? Color.TRANSPARENT : Color.BLACK);
        v.setOnHoverListener(new View.OnHoverListener() {
            @Override public boolean onHover(View view, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE) showTopBar();
                return true;
            }
        });
        v.setOnTouchListener(new View.OnTouchListener() {
            float y0;
            @Override public boolean onTouch(View view, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: y0 = ev.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE: if (ev.getRawY() - y0 > dp(16)) { showTopBar(); y0 = -Float.MAX_VALUE; } return true;
                    default: return true;
                }
            }
        });
        int th = policyApplied ? dp(10) : Math.max(statusBarH(), dp(16));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, th,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP;
        try { wm.addView(v, lp); topStrip = v; } catch (Exception e) { topStrip = null; }
    }

    /* ---- Thanh trên cùng: 3 nút Đóng / Thu nhỏ / Phóng to ---- */
    private TextView lightBtn(int color, String glyph, View.OnClickListener l) {
        TextView t = new TextView(this);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(color);
        g.setStroke(dp(1), 0x40000000);
        t.setBackground(g); t.setText(glyph); t.setGravity(Gravity.CENTER);
        t.setTextColor(0x99000000); t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18); t.setIncludeFontPadding(false);
        t.setOnClickListener(l);
        return t;
    }
    private void addLight(LinearLayout row, int color, String glyph, View.OnClickListener l) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        lp.rightMargin = dp(12);
        row.addView(lightBtn(color, glyph, l), lp);
    }
    private String appLabel() {
        if (fgPkg == null) return "";
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(fgPkg, 0)).toString(); }
        catch (Exception e) { return fgPkg; }
    }
    private void showTopBar() {
        if (wm == null || !enabled || launcherFront) return;
        if (topBar != null && topBar.isAttachedToWindow()) { h.removeCallbacks(autoHideTop); h.postDelayed(autoHideTop, 4500); return; }
        LinearLayout row = new LinearLayout(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideTopBar(); return true; }
                h.removeCallbacks(autoHideTop); h.postDelayed(autoHideTop, 4500);
                return super.dispatchTouchEvent(ev);
            }
        };
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(24)); bg.setColor(0xDB24262E); bg.setStroke(dp(1), 0x2EFFFFFF);
        row.setBackground(bg);
        final String target = fgPkg;
        addLight(row, 0xFFFF5F57, "\u00D7", new View.OnClickListener() { public void onClick(View v) { closeCurrent(target); } });
        addLight(row, 0xFFFEBC2E, "\u2212", new View.OnClickListener() { public void onClick(View v) { hideTopBar(); doGlobal(GLOBAL_ACTION_HOME); } });
        TextView title = new TextView(this); title.setText(appLabel()); title.setTextColor(0xCCFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14); title.setSingleLine(true); title.setPadding(dp(4), 0, 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        topBar = row;
        int w = Math.min(screenW() - dp(16), dp(760));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, dp(52),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; lp.y = dp(8);
        try { wm.addView(topBar, lp); h.removeCallbacks(autoHideTop); h.postDelayed(autoHideTop, 4500); } catch (Exception e) { topBar = null; }
    }
    private void hideTopBar() {
        h.removeCallbacks(autoHideTop);
        if (topBar != null) { try { if (topBar.isAttachedToWindow()) wm.removeView(topBar); } catch (Exception e) { } topBar = null; }
    }
    private void doGlobal(final int act) { h.postDelayed(new Runnable() { public void run() { performGlobalAction(act); } }, 80); }
    /** Nút đỏ: về màn hình chính rồi tắt tiến trình nền của app đang mở. */
    private void closeCurrent(final String pkg) {
        hideTopBar(); doGlobal(GLOBAL_ACTION_HOME);
        if (pkg == null || pkg.equals(getPackageName()) || pkg.equals("com.android.systemui")) return;
        h.postDelayed(new Runnable() { public void run() {
            try { ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg); } catch (Exception e) { }
        } }, 900);
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
        if (wm == null || !enabled || launcherFront) return;
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
        hideDock(); hideTopBar(); removeStrip(); setImmersivePolicy(false);
        if (web != null) { try { web.destroy(); } catch (Exception e) { } web = null; }
        dock = null; webReady = false;
    }
}
