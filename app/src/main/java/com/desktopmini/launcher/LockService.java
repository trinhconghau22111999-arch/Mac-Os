package com.desktopmini.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dịch vụ trợ năng: khóa màn hình, menu nguồn, và nút tròn "Desktop" nổi trên các app khác.
 * Bấm nút -> về màn hình Desktop; nhấn giữ rồi kéo -> đặt nút ở vị trí tùy ý (được nhớ lại).
 */
public class LockService extends AccessibilityService {
    public static LockService inst;
    private static final String PK = "dock";

    private WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean policyHintShown = false;
    private boolean launcherFront, enabled = true;
    private boolean policyApplied;
    /* Khóa thanh hệ thống: tự đóng thanh thông báo (kéo từ trên xuống) và màn hình đa nhiệm ngay khi chúng vừa mở */
    private volatile boolean barLock = true, recentsOpen = false;
    private long lastClose = 0;
    /* Thanh Desktop: đang có video phát toàn màn hình (do các cửa sổ web của app báo) -> thanh tự ẩn */
    private static volatile boolean videoFull = false;
    /** Các màn hình web của app gọi khi bắt đầu / kết thúc xem video toàn màn hình. Hết video thì thanh tự hiện lại. */
    public static void setVideoFull(final boolean on) {
        videoFull = on;
    }
    private final Runnable recheckRecents = new Runnable() { public void run() {
        /* Nếu sau một nhịp ngắn đa nhiệm vẫn còn mở (máy không nhận lệnh đóng) thì bấm Back một lần để thoát */
        if (recentsOpen && barLock) { recentsOpen = false; try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Exception e) { } }
    } };
    public static void saveBarLock(Context c, boolean on) {
        c.getSharedPreferences(PK, 0).edit().putBoolean("barlock", on).apply();
        final LockService s = inst; if (s != null) s.barLock = on;
    }
    public static boolean isBarLock(Context c) { return c.getSharedPreferences(PK, 0).getBoolean("barlock", true); }

    /** Giao diện Desktop Mini đẩy danh sách icon Dock (JSON: id, name, bg, g) sang để vẽ ở Dock nổi. */
    public static void saveData(Context c, String json) {
        c.getSharedPreferences(PK, 0).edit().putString("data", json == null || json.length() == 0 ? "[]" : json).apply();
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.pushDockData(); } });
    }
    public static void saveEnabled(Context c, boolean on) {
        c.getSharedPreferences(PK, 0).edit().putBoolean("on", on).apply();
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.apply(); } });
    }
    public static boolean isEnabled(Context c) { return c.getSharedPreferences(PK, 0).getBoolean("on", true); }
    /* Thanh menu nổi trên cùng: bật/tắt riêng, không phụ thuộc nút Desktop */
    public static void saveMenuBar(Context c, boolean on) {
        c.getSharedPreferences(PK, 0).edit().putBoolean("menubar", on).apply();
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.updateTopBar(); } });
    }
    public static boolean isMenuBar(Context c) { return c.getSharedPreferences(PK, 0).getBoolean("menubar", true); }

    @Override protected void onServiceConnected() {
        inst = this; wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ScreenTime.start(this); /* đo thời gian sáng màn hình ngay cả khi Desktop Mini không hiện */
        barLock = isBarLock(this);
        if (!isEnabled(this)) setImmersivePolicy(false);
        apply();
        updateTopBar();
    }
    @Override public boolean onUnbind(Intent i) { inst = null; tearDown(); return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; tearDown(); super.onDestroy(); }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c); removeTopBar(); updateTopBar(); hideDockBar(); updateDockBar();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pk = e.getPackageName().toString();
        if (pk.equals("com.android.systemui")) {
            /* Làm ngay trong lúc nhận sự kiện (không đẩy sang luồng khác) để đóng nhanh nhất có thể */
            if (barLock) blockSystemUi(e.getClassName());
            return; /* thanh thông báo: không đổi app đang mở */
        }
        recentsOpen = false; /* đã có cửa sổ khác lên trước: đa nhiệm đã đóng */
        if (pk.equals(getPackageName())) {
            CharSequence c = e.getClassName();
            if (c != null && c.toString().endsWith(".MainActivity")) setLauncherFront(true);
            return; /* cửa sổ nổi của chính mình: bỏ qua */
        }
        if (imePackages().contains(pk)) return; /* bàn phím: không đổi app đang mở */
        dismissPop(); fgLabel = labelOf(pk); if (topBar != null) topBar.invalidate();
        videoFull = false; /* app khác lên trước: không còn video của mình ở toàn màn hình */
        setLauncherFront(false);
    }
    @Override public void onInterrupt() { }

    /** Thanh thông báo / đa nhiệm của SystemUI vừa mở: đóng lại ngay. Giữ nguyên âm lượng, hộp thoại, chụp màn hình, khóa màn hình. */
    private void blockSystemUi(CharSequence cls) {
        String lc = cls == null ? "" : cls.toString().toLowerCase(java.util.Locale.ROOT);
        if (lc.contains("volume") || lc.contains("toast") || lc.contains("keyguard") || lc.contains("dialog")
            || lc.contains("screenshot") || lc.contains("usb") || lc.contains("cast")) return;
        boolean recents = lc.contains("recents");
        if (recents) recentsOpen = true;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastClose < 120) return; /* gộp các sự kiện dồn dập khi thanh đang thu lại */
        lastClose = now;
        if (Build.VERSION.SDK_INT < 31) {
            /* Android ≤ 11: lệnh này làm SystemUI thu thanh thông báo và đóng đa nhiệm ngay, nhanh hơn giả lập phím Back */
            try { sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)); }
            catch (Exception ex) { try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Exception e2) { } }
        } else {
            try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Exception ex) { }
        }
        if (recents) { h.removeCallbacks(recheckRecents); h.postDelayed(recheckRecents, 250); }
    }

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
        setImmersivePolicy(false); /* Dock nổi bật: ẩn thanh điều hướng hệ thống cho mọi app (cần WRITE_SECURE_SETTINGS) để Dock nằm sát đáy; không có quyền thì Dock nằm ngay trên thanh điều hướng */
        updateDockBar();
    }
    private void setLauncherFront(boolean b) {
        if (launcherFront == b) return;
        launcherFront = b;
        dismissPop();
        updateTopBar();
        updateDockBar();
        applyDockBase(); /* ở app ngoài: Dock cố định 53%; ở Desktop Mini: theo cỡ người dùng chỉnh */
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private Point realSize() {
        Point p = new Point();
        try { wm.getDefaultDisplay().getRealSize(p); } catch (Exception e) { p.set(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels); }
        return p;
    }

    /** Bấm nút: đưa Desktop Mini lên trước (MainActivity tự thu nhỏ mọi cửa sổ để hiện màn hình Desktop). */
    private void goDesktop() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception e) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    /* ---- Ẩn thanh trạng thái + thanh điều hướng của hệ thống ---- */
    /** Android ≤ 10, cần quyền WRITE_SECURE_SETTINGS cấp qua adb: ẩn thật bằng policy_control. */
    private void setImmersivePolicy(boolean on) {
        if (Build.VERSION.SDK_INT > 29) { policyApplied = false; return; }
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) { policyApplied = false; return; }
            Settings.Global.putString(getContentResolver(), "policy_control", on ? "immersive.full=*" : null);
            policyApplied = on;
        } catch (Exception e) { policyApplied = false; }
    }


    /* ================= Thanh menu nổi (phủ lên thanh trạng thái của hệ thống) =================
     * Cửa sổ riêng, độc lập với nút Desktop: có cờ bật/tắt riêng, tự thêm/bỏ riêng.
     * Chỉ hiện khi đang ở app ngoài (trong Desktop Mini đã có thanh menu HTML), tự ẩn khi app ngoài chạy toàn màn hình. */
    private TopBar topBar;
    private WindowManager.LayoutParams topLp;
    private String fgLabel = "";
    private boolean topHidden = false;
    private final Runnable topTick = new Runnable() { public void run() {
        h.removeCallbacks(topTick);
        if (topBar == null) return;
        boolean fs = videoFull || externalFullscreenTop();
        if (fs != topHidden) setTopHidden(fs);
        topBar.invalidate();
        h.postDelayed(topTick, 1000);
    } };

    private String labelOf(String pk) {
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pk, 0)).toString(); }
        catch (Throwable t) { return ""; }
    }
    private int statusBarHeight() {
        int hh = 0;
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) hh = getResources().getDimensionPixelSize(id);
        } catch (Exception e) { }
        return Math.max(Math.max(hh, dp(24)), dp(barGeomDp));
    }
    /** App ngoài đang phủ tới tận mép trên (không còn thanh trạng thái) = đang toàn màn hình. */
    private boolean externalFullscreenTop() {
        if (launcherFront || wm == null) return false;
        try {
            Point real = realSize();
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !(w.isActive() || w.isFocused())) continue;
                Rect r = new Rect(); w.getBoundsInScreen(r);
                return r.top <= 0 && r.width() >= real.x - dp(2) && r.height() >= real.y - dp(2);
            }
        } catch (Throwable t) { }
        return false;
    }

    /* Trạng thái do giao diện Desktop Mini đẩy sang (tên app đang chọn, chiều cao thanh, đang khóa màn hình...) */
    private static volatile String barApp = "";
    private static volatile boolean barHide = false;
    private static volatile int barGeomDp = 0;
    public static void setBarInfo(String app) {
        barApp = app == null ? "" : app;
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { if (s.topBar != null) s.topBar.invalidate(); } });
    }
    public static void setBarHide(boolean hide) {
        barHide = hide;
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.setTopHidden(barHide || videoFull || s.externalFullscreenTop()); } });
    }
    public static void setBarGeom(int dp) {
        barGeomDp = dp;
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.removeTopBar(); s.updateTopBar(); } });
    }

    /** Đưa Desktop Mini lên trước và bấm nút tương ứng của thanh menu HTML (khi đang ở Desktop Mini thì bấm thẳng, không chuyển màn hình). */
    private void forward(String id) {
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("mbClick", id).putExtra("mbFront", launcherFront));
        } catch (Exception e) { }
    }
    private void openInLauncher(String app) {
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("openApp", app));
        } catch (Exception e) { }
    }
    private void startSafe(Intent i) { try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { } }

    /** Chạm vào thanh: ở Desktop Mini -> menu HTML thật; ở app ngoài -> menu thả xuống gốc, mở thẳng trên app đang dùng. */
    private void menuTap(String id) {
        if (launcherFront) { dismissPop(); forward(id); return; }
        if (id.equals(popId)) { dismissPop(); return; }
        dismissPop();
        if (!showPop(id)) forward(id);
    }

    /* ---------------- Menu thả xuống gốc (cửa sổ nổi riêng) ---------------- */
    private interface SliderSet { void set(int v); }
    private static class PI {
        int type; /* 0 mục bấm, 1 gạch ngăn, 2 chữ (không bấm), 3 thanh trượt */
        String label; Runnable act; int val; SliderSet setter; float y, h;
    }
    private PI pItem(String l, Runnable r) { PI p = new PI(); p.type = 0; p.label = l; p.act = r; p.h = dp(38); return p; }
    private PI pText(String l) { PI p = new PI(); p.type = 2; p.label = l; p.h = dp(30); return p; }
    private PI pSep() { PI p = new PI(); p.type = 1; p.h = dp(9); return p; }
    private PI pSlider(String l, int v, SliderSet st) { PI p = new PI(); p.type = 3; p.label = l; p.val = v; p.setter = st; p.h = dp(56); return p; }

    private PopView pop; private String popId = "";
    private void dismissPop() {
        popId = "";
        if (pop != null) { try { wm.removeView(pop); } catch (Exception e) { } pop = null; }
    }

    private void lockPhone() {
        try {
            android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            android.content.ComponentName cn = new android.content.ComponentName(this, AdminReceiver.class);
            if (dpm.isAdminActive(cn)) { dpm.lockNow(); return; }
            if (Build.VERSION.SDK_INT >= 28) { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN); return; }
            startSafe(new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Cho phép Desktop Mini tắt màn hình khi bạn bấm Tắt máy. Quyền này chỉ dùng để khóa màn hình."));
        } catch (Exception e) { }
    }
    private boolean isDefaultHomeNow() {
        try {
            android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }
    private android.media.AudioManager audio() { return (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE); }

    /** Dựng danh sách mục cho nút `id`; trả false nếu nút này không có menu gốc (sẽ chuyển sang Desktop Mini). */
    private boolean showPop(String id) {
        java.util.ArrayList<PI> L = new java.util.ArrayList<PI>();
        if ("logo".equals(id)) {
            L.add(pItem("Giới thiệu về Desktop Mini", new Runnable() { public void run() { openInLauncher("about"); } }));
            L.add(pSep());
            L.add(pItem("Cài đặt hệ thống…", new Runnable() { public void run() { openInLauncher("settings"); } }));
            L.add(pItem("Buộc thoát…", new Runnable() { public void run() { openInLauncher("forcequit"); } }));
            L.add(pSep());
            if (isDefaultHomeNow()) L.add(pText("Desktop Mini là màn hình chính mặc định ✓"));
            else L.add(pItem("Đặt làm launcher mặc định…", new Runnable() { public void run() { startSafe(new Intent(Settings.ACTION_HOME_SETTINGS)); } }));
            L.add(pItem("Khởi động lại…", new Runnable() { public void run() { performGlobalAction(GLOBAL_ACTION_POWER_DIALOG); } }));
            L.add(pItem("Tắt máy…", new Runnable() { public void run() { lockPhone(); } }));
        } else if ("app".equals(id)) {
            L.add(pText(fgLabel == null || fgLabel.length() == 0 ? "Ứng dụng" : fgLabel));
            L.add(pSep());
            L.add(pItem("Về Desktop", new Runnable() { public void run() { goDesktop(); } }));
            L.add(pItem("Mission Control (đa nhiệm)", new Runnable() { public void run() { forward("mc"); } }));
            L.add(pItem("Launchpad", new Runnable() { public void run() { forward("lp"); } }));
            L.add(pSep());
            L.add(pItem("Quay lại (Back)", new Runnable() { public void run() { performGlobalAction(GLOBAL_ACTION_BACK); } }));
        } else if ("batt".equals(id)) {
            int b = topBar == null ? -1 : topBar.battery();
            L.add(pText("Pin: " + (b >= 0 ? b + "%" : "không rõ")));
            try {
                Intent bi = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int st = bi == null ? -1 : bi.getIntExtra("status", -1);
                L.add(pText(st == 2 || st == 5 ? "Đang sạc" : "Đang dùng pin"));
            } catch (Throwable t) { }
            L.add(pSep());
            L.add(pItem("Cài đặt Pin…", new Runnable() { public void run() { startSafe(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); } }));
        } else if ("wifi".equals(id)) {
            String st = "Không rõ trạng thái";
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                st = ni != null && ni.isConnected() ? "Đã kết nối (" + ni.getTypeName() + ")" : "Không có kết nối";
            } catch (Throwable t) { }
            L.add(pText(st));
            L.add(pSep());
            L.add(pItem("Cài đặt Wi-Fi…", new Runnable() { public void run() { startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS)); } }));
        } else if ("bt".equals(id)) {
            L.add(pText("Bluetooth: Đang bật"));
            L.add(pSep());
            L.add(pItem("Cài đặt Bluetooth…", new Runnable() { public void run() { startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } }));
        } else if ("ccb".equals(id)) {
            int br = 100;
            try { int v = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS); br = Math.max(1, Math.min(100, (int) Math.round(Math.sqrt(v / 255.0) * 100))); } catch (Throwable t) { }
            L.add(pSlider("Độ sáng", br, new SliderSet() { public void set(int v) {
                if (!Settings.System.canWrite(LockService.this)) { startSafe(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); dismissPop(); return; }
                int pc = Math.max(1, Math.min(100, v)), val = Math.max(1, Math.min(255, (int) Math.round(255.0 * (pc / 100.0) * (pc / 100.0))));
                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, val);
                } catch (Throwable t) { }
            } }));
            android.media.AudioManager am = audio();
            int vol = Math.round(am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100f / Math.max(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)));
            L.add(pSlider("Âm lượng", vol, new SliderSet() { public void set(int v) {
                android.media.AudioManager a = audio(); int max = a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                a.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, Math.round(v * max / 100f))), 0);
            } }));
            L.add(pSep());
            boolean rot = false;
            try { rot = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 1; } catch (Throwable t) { }
            final boolean rotOn = rot;
            L.add(pItem("Tự xoay màn hình: " + (rot ? "Bật" : "Tắt"), new Runnable() { public void run() {
                if (!Settings.System.canWrite(LockService.this)) { startSafe(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + getPackageName()))); return; }
                try {
                    if (rotOn) Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, wm.getDefaultDisplay().getRotation());
                    Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, rotOn ? 0 : 1);
                } catch (Throwable t) { }
            } }));
            L.add(pItem("Khóa màn hình", new Runnable() { public void run() { lockPhone(); } }));
        } else if ("clk".equals(id)) {
            /* Thông báo + widget làm thẳng bằng menu gốc, không nhảy về Desktop Mini */
            L.add(pText(new java.text.SimpleDateFormat("EEEE, dd/MM/yyyy", new java.util.Locale("vi", "VN")).format(new java.util.Date())));
            int b = topBar == null ? -1 : topBar.battery();
            L.add(pText("Pin: " + (b >= 0 ? b + "%" : "không rõ") + "   ·   " + (topBar == null ? "" : topBar.ram())));
            long om = ScreenTime.todayMs(this) / 60000;
            L.add(pText("Màn hình đã bật: " + (om / 60 > 0 ? om / 60 + " giờ " : "") + (om % 60) + " phút"));
            L.add(pSep());
            L.add(pText("Thông báo"));
            if (!notifAccessOn()) {
                L.add(pItem("Cấp quyền đọc thông báo…", new Runnable() { public void run() { startSafe(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); } }));
            } else {
                java.util.List<String[]> rc = NotifService.recent();
                if (rc.isEmpty()) L.add(pText("Không có thông báo mới"));
                int n = 0;
                for (final String[] r : rc) {
                    if (n++ >= 7) break;
                    String body = r[2].length() > 0 ? r[2] : r[3];
                    String lb = r[0] + ": " + body;
                    if (lb.length() > 40) lb = lb.substring(0, 39) + "…";
                    L.add(pItem(lb, new Runnable() { public void run() {
                        Intent li = getPackageManager().getLaunchIntentForPackage(r[1]);
                        if (li != null) startSafe(li);
                    } }));
                }
                if (!rc.isEmpty()) {
                    L.add(pSep());
                    L.add(pItem("Xóa danh sách thông báo", new Runnable() { public void run() { NotifService.clearRecent(); } }));
                }
            }
            L.add(pSep());
            L.add(pItem("Cài đặt thông báo…", new Runnable() { public void run() { startSafe(new Intent("android.settings.NOTIFICATION_SETTINGS")); } }));
        } else if ("srch".equals(id)) {
            showSpot();
            return true;
        } else if ("splitb".equals(id)) {
            dismissPop();
            try { performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN); } catch (Throwable t) { }
            return true;
        } else if ("ramb".equals(id)) {
            try {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                ((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
                double g = 1073741824.0;
                L.add(pText(String.format(java.util.Locale.US, "Đang dùng: %.1f GB", (mi.totalMem - mi.availMem) / g).replace('.', ',')));
                L.add(pText(String.format(java.util.Locale.US, "Còn trống: %.1f GB", mi.availMem / g).replace('.', ',')));
                L.add(pText(String.format(java.util.Locale.US, "Tổng: %.1f GB", mi.totalMem / g).replace('.', ',')));
            } catch (Throwable t) { return false; }
        } else if ("ontime".equals(id)) {
            long m = ScreenTime.todayMs(this) / 60000;
            L.add(pText("Màn hình đã bật hôm nay: " + (m / 60 > 0 ? m / 60 + " giờ " : "") + (m % 60) + " phút"));
        } else return false;
        if (topBar == null || topLp == null) return false;
        RectF r = topBar.rect(id);
        final PopView pv = new PopView(this, L, r == null ? 0 : r.left);
        Point sz = realSize();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(sz.x, Math.max(1, sz.y - topLp.height),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = topLp.height;
        try { wm.addView(pv, lp); pop = pv; popId = id; } catch (Exception e) { pop = null; popId = ""; return false; }
        return true;
    }


    private boolean notifAccessOn() {
        try {
            String f = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return f != null && f.contains(getPackageName());
        } catch (Throwable t) { return false; }
    }

    /* ---------------- Tìm kiếm (Spotlight) gốc: ô nhập + kết quả ứng dụng/tệp, mở thẳng trên app đang dùng ---------------- */
    private android.widget.FrameLayout spot;
    private int spotSeq = 0;
    private void dismissSpot() {
        spotSeq++;
        if (spot != null) {
            try { InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE); im.hideSoftInputFromWindow(spot.getWindowToken(), 0); } catch (Throwable t) { }
            try { wm.removeView(spot); } catch (Exception e) { }
            spot = null;
        }
    }
    private android.graphics.drawable.GradientDrawable round(int color, float rdp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(rdp)); return g;
    }
    private void spotRow(android.widget.LinearLayout list, String title, String sub, final Runnable act) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(7), dp(14), dp(7));
        android.widget.TextView a = new android.widget.TextView(this);
        a.setText(title); a.setTextColor(0xFFFFFFFF); a.setTextSize(14); a.setSingleLine(true); a.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(a);
        if (sub != null && sub.length() > 0) {
            android.widget.TextView b = new android.widget.TextView(this);
            b.setText(sub); b.setTextColor(0x99FFFFFF); b.setTextSize(11); b.setSingleLine(true); b.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(b);
        }
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dismissSpot(); try { act.run(); } catch (Throwable t) { } } });
        list.addView(row, new android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
    }
    private void spotHint(android.widget.LinearLayout list, String m) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(m); t.setTextColor(0x88FFFFFF); t.setTextSize(13); t.setPadding(dp(14), dp(10), dp(14), dp(10));
        list.addView(t);
    }
    private void openFileNative(String path) {
        try {
            java.io.File f = new java.io.File(path);
            android.net.Uri u = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(f.getName().replace(" ", "_"));
            String mt = ext == null ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(java.util.Locale.ROOT));
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mt == null ? "*/*" : mt).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startSafe(i);
        } catch (Throwable t) { }
    }
    /** Chạy ở luồng nền: tên ứng dụng khớp + tệp khớp trong MediaStore. */
    private void spotSearch(final String q, final android.widget.LinearLayout list, final int seq) {
        new Thread(new Runnable() { public void run() {
            final java.util.ArrayList<String[]> apps = new java.util.ArrayList<String[]>();
            final java.util.ArrayList<String[]> files = new java.util.ArrayList<String[]>();
            String lq = q.toLowerCase(java.util.Locale.ROOT);
            try {
                PackageManager pm = getPackageManager();
                Intent mi = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(mi, 0)) {
                    String lb = ri.loadLabel(pm).toString();
                    if (lb.toLowerCase(java.util.Locale.ROOT).contains(lq) && apps.size() < 6) apps.add(new String[]{lb, ri.activityInfo.packageName});
                }
            } catch (Throwable t) { }
            if (q.length() >= 2) {
                try {
                    String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                    android.database.Cursor c = getContentResolver().query(android.provider.MediaStore.Files.getContentUri("external"),
                        new String[]{android.provider.MediaStore.MediaColumns.DISPLAY_NAME, android.provider.MediaStore.MediaColumns.DATA},
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? ESCAPE '\\' AND " + android.provider.MediaStore.MediaColumns.MIME_TYPE + " IS NOT NULL",
                        new String[]{like}, android.provider.MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
                    if (c != null) {
                        try {
                            while (c.moveToNext() && files.size() < 15) {
                                String path = c.getString(1);
                                if (path == null || path.contains("/.") || path.contains("/Android/data/") || path.contains("/Android/obb/")) continue;
                                if (new java.io.File(path).isFile()) files.add(new String[]{c.getString(0), path});
                            }
                        } finally { c.close(); }
                    }
                } catch (Throwable t) { }
            }
            h.post(new Runnable() { public void run() {
                if (seq != spotSeq || spot == null) return;
                list.removeAllViews();
                for (final String[] a : apps) spotRow(list, a[0], "Ứng dụng", new Runnable() { public void run() {
                    Intent li = getPackageManager().getLaunchIntentForPackage(a[1]); if (li != null) startSafe(li);
                } });
                for (final String[] f : files) spotRow(list, f[0], f[1], new Runnable() { public void run() { openFileNative(f[1]); } });
                if (apps.isEmpty() && files.isEmpty()) spotHint(list, "Không tìm thấy kết quả");
            } });
        } }).start();
    }
    private void showSpot() {
        if (wm == null) return;
        dismissPop(); dismissSpot();
        final Point sz = realSize();
        final android.widget.FrameLayout root = new android.widget.FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
                if (e.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) { if (e.getAction() == android.view.KeyEvent.ACTION_UP) dismissSpot(); return true; }
                return super.dispatchKeyEvent(e);
            }
        };
        root.setBackgroundColor(0x66000000);
        root.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { dismissSpot(); } });
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackground(round(0xF2232326, 14));
        box.setPadding(dp(10), dp(10), dp(10), dp(8));
        box.setClickable(true);
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Tìm ứng dụng, tệp…"); et.setHintTextColor(0x88FFFFFF); et.setTextColor(0xFFFFFFFF); et.setTextSize(16);
        et.setSingleLine(true); et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setBackground(round(0x22FFFFFF, 10)); et.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.addView(et, new android.widget.LinearLayout.LayoutParams(-1, -2));
        final android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        int svh = Math.min(dp(320), Math.round(sz.y * 0.38f));
        android.widget.LinearLayout.LayoutParams slp = new android.widget.LinearLayout.LayoutParams(-1, svh);
        slp.topMargin = dp(6);
        box.addView(sv, slp);
        spotHint(list, "Gõ để tìm ứng dụng và tệp trong máy");
        int bw = Math.min(sz.x - dp(20), dp(560));
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(bw, -2);
        blp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        blp.topMargin = (topLp == null ? statusBarHeight() : topLp.height) + dp(8);
        root.addView(box, blp);
        et.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) {
                final String q = e.toString().trim();
                final int seq = ++spotSeq;
                if (q.length() == 0) { list.removeAllViews(); spotHint(list, "Gõ để tìm ứng dụng và tệp trong máy"); return; }
                h.postDelayed(new Runnable() { public void run() { if (seq == spotSeq && spot != null) spotSearch(q, list, seq); } }, 250);
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(sz.x, sz.y,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = 0;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        try { wm.addView(root, lp); spot = root; } catch (Exception e) { spot = null; return; }
        h.postDelayed(new Runnable() { public void run() {
            try { et.requestFocus(); ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(et, InputMethodManager.SHOW_IMPLICIT); } catch (Throwable t) { }
        } }, 150);
    }

    private class PopView extends View {
        private final java.util.List<PI> items; private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF panel = new RectF(), rf = new RectF();
        private PI active = null;
        PopView(Context c, java.util.List<PI> it, float anchorX) {
            super(c); items = it;
            t.setTextSize(dp(14)); t.setColor(0xFFFFFFFF);
            float w = dp(230), hh = dp(10);
            for (PI i : items) { if (i.label != null) w = Math.max(w, t.measureText(i.label) + dp(36)); i.y = hh; hh += i.h; }
            hh += dp(6);
            float sw = realSize().x, x = Math.max(dp(6), Math.min(anchorX, sw - w - dp(6)));
            panel.set(x, dp(2), x + w, dp(2) + hh);
        }
        @Override protected void onDraw(Canvas c) {
            p.setStyle(Paint.Style.FILL); p.setColor(0xF2232326); c.drawRoundRect(panel, dp(10), dp(10), p);
            for (PI i : items) {
                float top = panel.top + i.y, cy = top + i.h / 2;
                if (i.type == 1) { p.setColor(0x33FFFFFF); c.drawRect(panel.left + dp(10), cy, panel.right - dp(10), cy + dp(1), p); continue; }
                if (i == active && i.type == 0) { p.setColor(0x553B82F6); rf.set(panel.left + dp(5), top + dp(2), panel.right - dp(5), top + i.h - dp(2)); c.drawRoundRect(rf, dp(6), dp(6), p); }
                t.setColor(i.type == 2 ? 0xAAFFFFFF : 0xFFFFFFFF);
                if (i.type == 3) {
                    c.drawText(i.label, panel.left + dp(16), top + dp(20), t);
                    float x0 = panel.left + dp(16), x1 = panel.right - dp(16), ty = top + dp(40);
                    p.setColor(0x44FFFFFF); rf.set(x0, ty - dp(2.5f), x1, ty + dp(2.5f)); c.drawRoundRect(rf, dp(3), dp(3), p);
                    float kx = x0 + (x1 - x0) * Math.max(0, Math.min(100, i.val)) / 100f;
                    p.setColor(0xFF3B82F6); rf.set(x0, ty - dp(2.5f), kx, ty + dp(2.5f)); c.drawRoundRect(rf, dp(3), dp(3), p);
                    p.setColor(0xFFFFFFFF); c.drawCircle(kx, ty, dp(8), p);
                } else c.drawText(i.label, panel.left + dp(16), cy + dp(5), t);
            }
        }
        private PI hitItem(float x, float y) {
            if (!panel.contains(x, y)) return null;
            for (PI i : items) if (y >= panel.top + i.y && y < panel.top + i.y + i.h) return i;
            return null;
        }
        private void drag(PI i, float x) {
            float x0 = panel.left + dp(16), x1 = panel.right - dp(16);
            int v = Math.round(100f * Math.max(0, Math.min(1, (x - x0) / (x1 - x0))));
            if (v != i.val) { i.val = v; if (i.setter != null) i.setter.set(v); invalidate(); }
        }
        @Override public boolean onTouchEvent(MotionEvent ev) {
            float x = ev.getX(), y = ev.getY();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (!panel.contains(x, y)) { dismissPop(); return true; }
                    active = hitItem(x, y);
                    if (active != null && active.type == 3) drag(active, x);
                    invalidate(); return true;
                }
                case MotionEvent.ACTION_MOVE: if (active != null && active.type == 3) drag(active, x); return true;
                case MotionEvent.ACTION_UP: {
                    PI i = hitItem(x, y), a = active; active = null; invalidate();
                    if (i != null && i == a && i.type == 0) { dismissPop(); if (i.act != null) i.act.run(); }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: active = null; invalidate(); return true;
            }
            return true;
        }
    }

    private class TopBar extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG), ic = new Paint(Paint.ANTI_ALIAS_FLAG), fl = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final java.text.SimpleDateFormat tf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
        private final java.util.LinkedHashMap<String, RectF> hit = new java.util.LinkedHashMap<String, RectF>();
        private final RectF rf = new RectF();
        private String down = null;
        TopBar(Context c) {
            super(c);
            bg.setColor(0xFF111113); bg.setStyle(Paint.Style.FILL);
            tx.setColor(0xFFFFFFFF); tx.setTextSize(dp(13)); tx.setFakeBoldText(true);
            ic.setColor(0xFFFFFFFF); ic.setStyle(Paint.Style.STROKE); ic.setStrokeWidth(dp(1.4f)); ic.setStrokeCap(Paint.Cap.ROUND); ic.setStrokeJoin(Paint.Join.ROUND);
            fl.setColor(0xFFFFFFFF); fl.setStyle(Paint.Style.FILL);
            setContentDescription("Thanh menu");
        }
        private boolean btOn() {
            try {
                android.bluetooth.BluetoothAdapter a = ((android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
                return a != null && a.isEnabled();
            } catch (Throwable t) { return false; }
        }
        private int battery() {
            try {
                Intent i = registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (i == null) return -1;
                int l = i.getIntExtra("level", -1), sc = i.getIntExtra("scale", 100);
                return l < 0 ? -1 : Math.round(l * 100f / Math.max(1, sc));
            } catch (Throwable t) { return -1; }
        }
        private String ram() {
            try {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                ((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
                if (mi.totalMem <= 0) return "";
                double g = 1073741824.0;
                return String.format(java.util.Locale.US, "RAM %.1f/%.1f GB", (mi.totalMem - mi.availMem) / g, mi.totalMem / g).replace('.', ',');
            } catch (Throwable t) { return ""; }
        }
        private String ontime() {
            long m = ScreenTime.todayMs(getContext()) / 60000, hh = m / 60;
            return "OnTime " + (hh > 0 ? hh + "h" + (m % 60 < 10 ? "0" : "") + (m % 60) : m + "m");
        }
        @Override protected void onDraw(Canvas c) {
            if (topHidden) return;
            float w = getWidth(), hh = getHeight(), cy = hh / 2, pad = dp(12), slot = dp(34);
            hit.clear();
            c.drawRect(0, 0, w, hh, bg);
            /* ---- bên trái: logo + tên app ---- */
            float x = pad, r = dp(7);
            android.graphics.Path st = new android.graphics.Path();   /* ngôi sao 4 cánh như logo của thanh menu HTML */
            float cx = x + r, k = r * 0.28f;
            st.moveTo(cx, cy - r); st.lineTo(cx + k, cy - k); st.lineTo(cx + r, cy); st.lineTo(cx + k, cy + k);
            st.lineTo(cx, cy + r); st.lineTo(cx - k, cy + k); st.lineTo(cx - r, cy); st.lineTo(cx - k, cy - k); st.close();
            c.drawPath(st, fl);
            hit.put("logo", new RectF(0, 0, x + r * 2 + dp(8), hh));
            x += r * 2 + dp(12);
            String name = launcherFront ? (barApp.length() > 0 ? barApp : "Desktop") : (fgLabel == null || fgLabel.length() == 0 ? "Desktop" : fgLabel);
            float maxW = w * 0.4f - x;
            while (name.length() > 1 && tx.measureText(name) > maxW) name = name.substring(0, name.length() - 1);
            float nw = tx.measureText(name);
            c.drawText(name, x, cy + dp(4.5f), tx);
            hit.put("app", new RectF(x - dp(6), 0, x + nw + dp(10), hh));
            /* ---- bên phải, từ phải sang trái: giờ, trung tâm điều khiển, tìm kiếm, Wi-Fi, pin, OnTime ---- */
            float rx = w - pad;
            String clock = tf.format(new java.util.Date()); float cw = tx.measureText(clock);
            /* giờ đứng trước (bên trái) ngày tháng: "11:22  T6 16 thg 10" (màn hình hẹp thì bỏ ngày tháng) */
            float dw = 0;
            if (w >= dp(480)) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                String[] dn = {"CN", "T2", "T3", "T4", "T5", "T6", "T7"};
                String dt = dn[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(java.util.Calendar.DAY_OF_MONTH) + " thg " + (cal.get(java.util.Calendar.MONTH) + 1);
                float dtw = tx.measureText(dt);
                dw = dtw + dp(10);
                c.drawText(dt, rx - dtw, cy + dp(4.5f), tx);
            }
            c.drawText(clock, rx - dw - cw, cy + dp(4.5f), tx);
            hit.put("clk", new RectF(rx - cw - dw - dp(8), 0, rx + dp(8), hh)); rx -= cw + dw + dp(14);
            /* trung tâm điều khiển: 2 thanh bo tròn */
            rf.set(rx - dp(15), cy - dp(5.5f), rx, cy - dp(1)); c.drawRoundRect(rf, dp(2.3f), dp(2.3f), ic);
            rf.set(rx - dp(15), cy + dp(1), rx, cy + dp(5.5f)); c.drawRoundRect(rf, dp(2.3f), dp(2.3f), ic);
            hit.put("ccb", new RectF(rx - slot + dp(8), 0, rx + dp(8), hh)); rx -= slot;
            /* tìm kiếm: kính lúp */
            c.drawCircle(rx - dp(9), cy - dp(1.5f), dp(4.8f), ic); c.drawLine(rx - dp(5.6f), cy + dp(1.9f), rx - dp(2), cy + dp(5.5f), ic);
            hit.put("srch", new RectF(rx - slot + dp(8), 0, rx + dp(8), hh)); rx -= slot;
            /* Bluetooth: chỉ hiện khi đang bật, nằm bên phải nút Wi-Fi */
            if (btOn()) {
                float s = dp(0.75f), ox = rx - dp(8) - 8 * s, oy = cy - 8 * s;
                android.graphics.Path bp = new android.graphics.Path();
                bp.moveTo(ox + 4 * s, oy + 4.5f * s); bp.lineTo(ox + 12 * s, oy + 11.5f * s); bp.lineTo(ox + 8 * s, oy + 15 * s);
                bp.lineTo(ox + 8 * s, oy + 1 * s); bp.lineTo(ox + 12 * s, oy + 4.5f * s); bp.lineTo(ox + 4 * s, oy + 11.5f * s);
                c.drawPath(bp, ic);
                hit.put("bt", new RectF(rx - dp(22), 0, rx + dp(8), hh)); rx -= dp(26);
            }
            /* Wi-Fi: 3 cung + chấm */
            float wx = rx - dp(8), wy = cy + dp(5);
            fl.setStyle(Paint.Style.FILL); c.drawCircle(wx, wy, dp(1.3f), fl);
            for (int i = 1; i <= 3; i++) { float rr = dp(3.6f) * i; rf.set(wx - rr, wy - rr, wx + rr, wy + rr); c.drawArc(rf, 225, 90, false, ic); }
            hit.put("wifi", new RectF(rx - slot + dp(6), 0, rx + dp(10), hh)); rx -= slot + dp(2);
            /* pin: icon + % */
            int b = battery(); String bt = b >= 0 ? b + "%" : ""; float bw = tx.measureText(bt);
            if (bt.length() > 0) { c.drawText(bt, rx - bw, cy + dp(4.5f), tx); rx -= bw + dp(5); }
            rf.set(rx - dp(20), cy - dp(5), rx - dp(2), cy + dp(5)); c.drawRoundRect(rf, dp(2.5f), dp(2.5f), ic);
            if (b > 0) { rf.set(rx - dp(18), cy - dp(3), rx - dp(18) + dp(14) * Math.min(100, b) / 100f, cy + dp(3)); c.drawRoundRect(rf, dp(1.2f), dp(1.2f), fl); }
            c.drawRect(rx - dp(1.5f), cy - dp(2), rx, cy + dp(2), fl);
            hit.put("batt", new RectF(rx - dp(24), 0, rx + bw + dp(14), hh)); rx -= dp(30) + bw * 0 + dp(6);
            /* OnTime */
            String ot = ontime(); float ow = tx.measureText(ot), minX = w * 0.42f;
            if (rx - ow > minX) {
                c.drawText(ot, rx - ow, cy + dp(4.5f), tx); hit.put("ontime", new RectF(rx - ow - dp(8), 0, rx + dp(8), hh)); rx -= ow + dp(16);
                /* chia đôi màn hình: 2 hình chữ nhật sát nhau, bên trái OnTime */
                if (rx - slot > minX) {
                    rf.set(rx - dp(15), cy - dp(5), rx - dp(8.5f), cy + dp(5)); c.drawRoundRect(rf, dp(1.6f), dp(1.6f), ic);
                    rf.set(rx - dp(7.5f), cy - dp(5), rx, cy + dp(5)); c.drawRoundRect(rf, dp(1.6f), dp(1.6f), ic);
                    hit.put("splitb", new RectF(rx - slot + dp(8), 0, rx + dp(8), hh)); rx -= slot + dp(2);
                    /* RAM đang dùng / tổng, bên trái nút chia đôi */
                    String ram = ram(); float rw = tx.measureText(ram);
                    if (ram.length() > 0 && rx - rw > minX) { c.drawText(ram, rx - rw, cy + dp(4.5f), tx); hit.put("ramb", new RectF(rx - rw - dp(8), 0, rx + dp(8), hh)); }
                }
            }
        }
        RectF rect(String id) { return hit.get(id); }
        private String at(float px, float py) {
            for (java.util.Map.Entry<String, RectF> e : hit.entrySet()) if (e.getValue().contains(px, py)) return e.getKey();
            return null;
        }
        /* nuốt mọi chạm trong dải này (không kéo được thanh thông báo hệ thống); chạm đúng nút thì mở menu tương ứng */
        @Override public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: down = at(ev.getX(), ev.getY()); return true;
                case MotionEvent.ACTION_UP: { String id = at(ev.getX(), ev.getY()); if (id != null && id.equals(down)) menuTap(id); down = null; return true; }
                case MotionEvent.ACTION_CANCEL: down = null; return true;
            }
            return true;
        }
    }

    private void updateTopBar() {
        boolean want = wm != null && isMenuBar(this);
        if (want) { if (topBar == null) addTopBar(); } else removeTopBar();
    }
    private void addTopBar() {
        if (wm == null) return;
        final TopBar v = new TopBar(this);
        Point s = realSize();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(s.x, statusBarHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = 0;
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try { wm.addView(v, lp); topBar = v; topLp = lp; topHidden = false; h.removeCallbacks(topTick); h.postDelayed(topTick, 400); }
        catch (Exception e) { topBar = null; topLp = null; }
    }
    private void removeTopBar() {
        dismissPop(); dismissSpot(); h.removeCallbacks(topTick); topHidden = false;
        if (topBar != null) { try { wm.removeView(topBar); } catch (Exception e) { } topBar = null; topLp = null; }
    }
    /** Ẩn (chỉ còn dải 1dp, không nhận chạm) khi app ngoài toàn màn hình; hiện lại đủ chiều cao khi hết. */
    private void setTopHidden(boolean b) {
        topHidden = b;
        if (topBar == null || topLp == null || wm == null) return;
        try {
            topLp.height = b ? dp(1) : statusBarHeight();
            if (b) topLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else topLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            wm.updateViewLayout(topBar, topLp); topBar.invalidate();
        } catch (Exception e) { }
    }

    private void tearDown() {
        removeTopBar(); hideDockBar(); hideHot(); h.removeCallbacks(dockTick);
        if (dockWeb != null) { try { dockWeb.destroy(); } catch (Exception e) { } }
        dockWeb = null; dockView = null; dockReady = false; dockLp = null;
        setImmersivePolicy(false);
    }

    /* ================= Dock nổi trên các app khác =================
     * Một thanh sát cạnh dưới (ngay trên thanh điều hướng của hệ thống), giống thanh menu trên cùng:
     * nút AI (trái) – icon Dock (giữa) – nút chụp màn hình (phải). Chỉ hiện khi đang ở app ngoài;
     * tự ẩn khi app chạy toàn màn hình, khi bàn phím mở hoặc lúc đang chụp. Icon vẽ bằng một WebView nhỏ (assets/dock.html). */
    private android.widget.FrameLayout dockView;
    private android.webkit.WebView dockWeb;
    private boolean dockReady, dockShown;
    private long dockQuietUntil = 0;
    private int dockWidthDp = 0;
    private WindowManager.LayoutParams dockLp;
    private static final int DOCK_GAP_DP = 0;
    private static volatile int dockBase = 46;
    /** Cỡ người dùng chỉnh (chỉ áp dụng khi đang ở Desktop Mini). */
    private static volatile int userDockBase = 46;
    /** Khi đang mở app khác, Dock nổi bắt buộc cố định 53% (53% của 45px ≈ 24px), không theo cỡ người dùng. */
    private static final int EXT_DOCK_BASE = 24;
    private int effectiveDockBase() { return launcherFront ? userDockBase : EXT_DOCK_BASE; }
    private static volatile String dockIcs = "round";
    private static volatile boolean dockMag = true, dockAutoHide = false;
    private long dockPeekUntil = 0;
    private boolean dockGrown = false;
    private android.view.View hotView;
    /** Cài đặt Dock trong giao diện (kiểu icon, phóng đại, tự ẩn) áp thẳng lên Dock nổi. */
    public static void setDockPrefs(Context c, String ics, boolean mag, boolean autoHide) {
        dockIcs = ics == null || ics.length() == 0 ? "round" : ics; dockMag = mag; dockAutoHide = autoHide;
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.pushDockPrefs(); s.updateDockBar(); } });
    }
    private void pushDockPrefs() {
        if (dockWeb != null && dockReady)
            dockWeb.evaluateJavascript("window.setIcs&&setIcs('" + dockIcs.replaceAll("[^a-z]", "") + "');window.setMag&&setMag(" + dockMag + ")", null);
    }
    private void peekDock() { dockPeekUntil = android.os.SystemClock.uptimeMillis() + 3500; }
    /** Chiều cao thanh điều hướng của hệ thống (đúng theo hướng màn hình), tối thiểu 40dp để chắc chắn che kín. */
    private int navBarHeight() {
        int h = 0;
        try {
            boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            int id = getResources().getIdentifier(land ? "navigation_bar_height_landscape" : "navigation_bar_height", "dimen", "android");
            if (id <= 0) id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) h = getResources().getDimensionPixelSize(id);
        } catch (Exception e) { }
        return Math.max(h, dp(40));
    }

    /**
     * Chiều cao THẬT của thanh hệ thống/thanh tác vụ ở đáy (Yoga Book có thanh tác vụ cao hơn giá trị navigation_bar_height).
     * Lấy giá trị lớn nhất trong 3 cách đo: tài nguyên hệ thống, phần chênh giữa màn hình thật và vùng app, và cửa sổ SystemUI thật ở đáy.
     */
    private int barMax = 0; /* chiều cao lớn nhất đã đo được: chỉ tăng, không tự thu nhỏ lại (thanh tác vụ lúc hiện lúc ẩn làm số đo nhảy) */
    private int sysBarHeight() {
        int best = navBarHeight(), cap = dp(150);
        Point real = realSize();
        if (Build.VERSION.SDK_INT >= 30) {
            /* Cách chuẩn: phần đệm của thanh điều hướng / thanh tác vụ (taskbar) dù đang hiện hay ẩn */
            try {
                android.graphics.Insets in = wm.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.navigationBars() | android.view.WindowInsets.Type.tappableElement());
                if (in.bottom > best && in.bottom < cap) best = in.bottom;
            } catch (Throwable t) { }
        }
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            int d = real.y - dm.heightPixels;
            if (d > best && d < cap) best = d;
        } catch (Exception e) { }
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                Rect r = new Rect(); w.getBoundsInScreen(r);
                if (r.bottom >= real.y - dp(2) && r.top > real.y / 2 && r.width() >= real.x / 2) {
                    int hh = real.y - r.top;
                    if (hh > best && hh < cap) best = hh;
                }
            }
        } catch (Throwable t) { }
        if (best > barMax) barMax = best;
        return barMax;
    }
    /** Chiều cao cửa sổ Dock (px): ở Desktop = đúng thanh icon; ở app khác = đủ cao để phủ kín thanh điều hướng/thanh tác vụ của ROM. */
    private int dockBarPx() { int bar = dp(dockBase + 18); return launcherFront ? bar : Math.max(bar, sysBarHeight()); }
    private int dockGrowPx() { return dockGrown ? dp(Math.round(dockBase * 0.7f) + 8) : 0; }
    /** Đặt cửa sổ theo toạ độ tuyệt đối từ mép trên của màn hình THẬT (như thanh Desktop cũ) nên đè được lên thanh điều hướng. */
    private void placeDock(WindowManager.LayoutParams lp) {
        Point real = realSize(); int hh = dockBarPx() + dockGrowPx();
        lp.width = real.x; lp.height = hh; lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = real.y - hh + dockYAdj;
    }
    /* Hệ thống có thể tính toạ độ y từ mép trên VÙNG NỘI DUNG (dưới thanh trạng thái) chứ không phải mép trên màn hình thật => cửa sổ bị đẩy xuống.
     * Đo vị trí thật của cửa sổ Dock trên màn hình rồi bù đúng phần lệch (tối đa vài lần mỗi lần hiện, không dao động). */
    private int dockYAdj = 0, calTries = 0;
    private final Runnable calibrateTick = new Runnable() { public void run() { calibrateDock(); } };
    private void calibrateDock() {
        android.view.View v = dockShown ? dockView : hotView; WindowManager.LayoutParams lp = dockShown ? dockLp : null;
        if (v == null || wm == null) return;
        if (lp == null) { /* chỉ có vạch cảm ứng (đang tự ẩn): đo bằng nó */
            try {
                int[] loc = new int[2]; v.getLocationOnScreen(loc);
                int want = realSize().y - dp(10), err = loc[1] - want;
                if (Math.abs(err) > dp(2) && calTries < 6) { calTries++; dockYAdj = Math.max(-dp(120), Math.min(dp(120), dockYAdj - err)); }
            } catch (Throwable t) { }
            return;
        }
        try {
            int[] loc = new int[2]; v.getLocationOnScreen(loc);
            int want = realSize().y - lp.height, err = loc[1] - want;   /* >0: cửa sổ đang thấp hơn mong muốn */
            if (Math.abs(err) > dp(2) && calTries < 6) {
                calTries++;
                dockYAdj = Math.max(-dp(120), Math.min(dp(120), dockYAdj - err));
                placeDock(lp); wm.updateViewLayout(v, lp);
                h.removeCallbacks(calibrateTick); h.postDelayed(calibrateTick, 250);
            }
        } catch (Throwable t) { }
    }
    private static final int DOCK_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    private int dockHDp() { return Math.round(dockBarPx() / getResources().getDisplayMetrics().density); }
    private int dockPadDp() { return 0; }
    private int lastBarPx = -1;
    private void relayoutDock() {
        if (!dockShown || dockLp == null || dockView == null) return;
        int y = dockLp.y, hh = dockLp.height; placeDock(dockLp);
        int bp = dockBarPx();
        if (bp != lastBarPx) { lastBarPx = bp; calTries = 0; h.removeCallbacks(calibrateTick); h.postDelayed(calibrateTick, 300); if (dockWeb != null && dockReady) dockWeb.evaluateJavascript("window.setLayout&&setLayout(" + dockHDp() + ",0)", null); }
        if (y != dockLp.y || hh != dockLp.height) { try { wm.updateViewLayout(dockView, dockLp); } catch (Exception e) { } }
    }
    public static boolean isRunning() { return inst != null; }
    public static void setDockBase(Context c, int base) {
        userDockBase = Math.max(18, Math.min(80, base));
        dockBase = userDockBase;
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.applyDockBase(); } });
    }
    private void applyDockBase() {
        dockBase = effectiveDockBase();
        if (dockWeb != null && dockReady) dockWeb.evaluateJavascript("window.setBase&&setBase(" + dockBase + ");window.setLayout&&setLayout(" + dockHDp() + "," + dockPadDp() + ")", null);
        relayoutDock();
    }
    /** Thu cửa sổ Dock vừa khít viên thuốc, căn giữa; phần còn lại của màn hình chạm xuyên xuống app bên dưới. */
    private void resizeDock() {
        if (!dockShown || dockLp == null || dockView == null || dockWidthDp <= 0) return;
        try {
            Point real = realSize();
            dockLp.width = Math.min(real.x, dp(dockWidthDp));
            dockLp.x = 0; dockLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            wm.updateViewLayout(dockView, dockLp);
        } catch (Exception e) { }
    }
    private final Runnable dockTick = new Runnable() { public void run() {
        h.removeCallbacks(dockTick);
        if (!enabled || wm == null) return;
        relayoutDock(); /* đổi giữa Desktop/app khác, xoay màn hình, hoặc đo được thanh hệ thống cao hơn: chỉnh lại vị trí/chiều cao */
                if (android.os.SystemClock.uptimeMillis() >= dockQuietUntil) {
            boolean peeking = android.os.SystemClock.uptimeMillis() < dockPeekUntil;
            boolean hide = imeShown() || barHide || (dockAutoHide && !peeking); /* Dock luôn nổi; chỉ nhường chỗ cho bàn phím/khóa màn hình, hoặc tự ẩn nếu bật trong Cài đặt */
            if (hide && dockShown) hideDockBar(); else if (!hide && !dockShown) showDockBar();
            if (dockAutoHide && !dockShown && !(imeShown() || barHide)) showHot(); else hideHot();
        }
        h.postDelayed(dockTick, 700);
    } };
    /** Nới cao cửa sổ Dock lúc phóng đại icon (nền thanh vẫn nằm sát đáy, phần nới ra trong suốt). */
    private void growDock(boolean on) {
        dockGrown = on && dockMag; applyDockBase();
    }
    private void showHot() {
        if (hotView != null || wm == null) return;
        hotView = new android.view.View(this);
        android.view.View.OnTouchListener tl = new android.view.View.OnTouchListener() { public boolean onTouch(android.view.View v, MotionEvent e) {
            peekDock(); h.post(dockTick); return false; } };
        hotView.setOnTouchListener(tl);
        hotView.setOnHoverListener(new android.view.View.OnHoverListener() { public boolean onHover(android.view.View v, MotionEvent e) {
            peekDock(); h.post(dockTick); return false; } });
        Point real = realSize();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(real.x, dp(10),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            DOCK_FLAGS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = realSize().y - dp(10) + dockYAdj;
        try { wm.addView(hotView, lp); } catch (Exception e) { hotView = null; }
    }
    private void hideHot() {
        if (hotView != null) { try { wm.removeView(hotView); } catch (Exception e) { } }
        hotView = null;
    }
    private boolean imeShown() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        } catch (Throwable t) { }
        return false;
    }
    private void updateDockBar() {
        if (dockShown) applyDockBase();
        h.removeCallbacks(dockTick);
        if (enabled && wm != null) h.post(dockTick); else { hideDockBar(); hideHot(); }
    }
    /** Chiều cao thanh 3 phím điều hướng (0 nếu dùng cử chỉ), để Dock nằm ngay phía trên nó. */
    private int navInset() {
        try {
            Point real = realSize(), size = new Point();
            wm.getDefaultDisplay().getSize(size);
            return Math.max(0, real.y - size.y);
        } catch (Exception e) { return 0; }
    }
    private class DockJs {
        @JavascriptInterface public void open(final String id) { h.post(new Runnable() { public void run() { openInLauncher(id); } }); }
        @JavascriptInterface public void ai() { h.post(new Runnable() { public void run() { forward("aib"); } }); }
        @JavascriptInterface public void shot() { h.post(new Runnable() { public void run() { dockShot(); } }); }
        @JavascriptInterface public void touch() { peekDock(); }
        @JavascriptInterface public void grow(final boolean on) { h.post(new Runnable() { public void run() { growDock(on); } }); }
        @JavascriptInterface public void size(final int wDp) { h.post(new Runnable() { public void run() { /* thanh dài full chiều ngang: không cần thu cửa sổ */ } }); }
    }
    /** Chụp màn hình app đang dùng (hệ thống tự lưu vào Ảnh chụp màn hình); ẩn Dock một lúc để không lọt vào ảnh. */
    private void dockShot() {
        if (Build.VERSION.SDK_INT < 28) {
            /* Android dưới 9 không có chụp toàn hệ thống cho dịch vụ trợ năng: ở Desktop Mini thì chụp cửa sổ Desktop như nút cũ */
            if (launcherFront) forward("qshot");
            else android.widget.Toast.makeText(this, "Android 7 chỉ chụp được màn hình Desktop Mini. Ở ứng dụng khác hãy dùng phím cứng: Nguồn + Giảm âm lượng.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        dockQuietUntil = android.os.SystemClock.uptimeMillis() + 2000;
        hideDockBar();
        h.postDelayed(new Runnable() { public void run() {
            try { performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); } catch (Throwable t) { }
        } }, 350);
    }
    private void showDockBar() {
        if (wm == null || dockShown) return;
        dockGrown = false;
        if (dockView == null) {
            dockView = new android.widget.FrameLayout(this);
            dockWeb = new android.webkit.WebView(new android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault));
            dockWeb.getSettings().setJavaScriptEnabled(true);
            dockWeb.setBackgroundColor(0x00000000); /* nền thanh do dock.html vẽ; phần nới lúc phóng đại phải trong suốt */
            dockWeb.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            
            dockWeb.setVerticalScrollBarEnabled(false); dockWeb.setHorizontalScrollBarEnabled(false);
            dockWeb.addJavascriptInterface(new DockJs(), "DockApp");
            dockWeb.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(android.webkit.WebView v, String url) { dockReady = true; pushDockData(); }
            });
            dockView.addView(dockWeb, new android.widget.FrameLayout.LayoutParams(-1, -1));
            dockReady = false;
            dockWeb.loadUrl("file:///android_asset/dock.html");
        } else pushDockData();
        Point real = realSize();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(real.x, dockBarPx(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            DOCK_FLAGS,
            PixelFormat.TRANSLUCENT);
        placeDock(lp);
        dockLp = lp;
        try { wm.addView(dockView, lp); dockShown = true; calTries = 0; h.removeCallbacks(calibrateTick); h.postDelayed(calibrateTick, 300); } catch (Throwable e) { dockShown = false; }
    }
    private void hideDockBar() {
        if (dockView != null && dockShown) { try { wm.removeView(dockView); } catch (Exception e) { } }
        dockShown = false;
    }
    private void pushDockData() {
        if (dockWeb == null || !dockReady) return;
        dockBase = effectiveDockBase();
        /* mở rộng cửa sổ hết cỡ trước để viên thuốc đo đúng bề rộng tự nhiên, rồi JS gọi size() để thu lại */
        if (dockShown && dockLp != null && dockView != null) { try { dockLp.width = realSize().x; wm.updateViewLayout(dockView, dockLp); } catch (Exception e) { } }
        String d = getSharedPreferences(PK, 0).getString("data", "[]");
        pushDockPrefs();
        dockWeb.evaluateJavascript("window.setBase&&setBase(" + dockBase + ");window.setLayout&&setLayout(" + dockHDp() + "," + dockPadDp() + ");window.setDock&&setDock(" + d + ");window.setShot&&setShot(true)", null);
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        /* thiếu RAM: giải phóng WebView của Dock khi nó đang ẩn (lần hiện sau tự tạo lại) */
        if ((level >= TRIM_MEMORY_BACKGROUND || level >= TRIM_MEMORY_RUNNING_CRITICAL) && !dockShown && dockWeb != null) {
            try { dockWeb.destroy(); } catch (Exception e) { }
            dockWeb = null; dockView = null; dockReady = false; dockLp = null;
        }
    }
}
