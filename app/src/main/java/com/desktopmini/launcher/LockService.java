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
    /** Ghi nhớ app ngoài vừa mở từ Dock nổi (mới nhất đứng đầu) để thẻ đa nhiệm của Desktop Mini hiện đủ. */
    public static void noteExt(Context c, String pk) {
        if (pk == null || pk.equals(c.getPackageName())) return;
        try {
            SharedPreferences sp = c.getSharedPreferences(PK, 0);
            org.json.JSONArray old = new org.json.JSONArray(sp.getString("extlaunched", "[]"));
            org.json.JSONArray n = new org.json.JSONArray(); n.put(pk);
            for (int i = 0; i < old.length() && n.length() < 30; i++) if (!pk.equals(old.optString(i))) n.put(old.optString(i));
            sp.edit().putString("extlaunched", n.toString()).apply();
        } catch (Throwable t) { }
    }
    public static String takeExtLaunched(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PK, 0);
        String s = sp.getString("extlaunched", "[]");
        sp.edit().remove("extlaunched").apply();
        return s;
    }
    public static boolean isBarLock(Context c) { return c.getSharedPreferences(PK, 0).getBoolean("barlock", true); }

    /** Giao diện Desktop Mini đẩy danh sách icon Dock (JSON: id, name, bg, g) sang để vẽ ở Dock nổi. */
    public static void saveData(Context c, String json) {
        c.getSharedPreferences(PK, 0).edit().putString("data", json == null || json.length() == 0 ? "[]" : json).apply();
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.pushDockData(); } });
    }
    /** Thứ tự icon đã ghim trên Dock (JSON mảng id) do người dùng sắp xếp; lưu cố định nên khởi động lại vẫn giữ nguyên. Rỗng = chưa tùy chỉnh. */
    public static String getDockOrder(Context c) { return c.getSharedPreferences(PK, 0).getString("dockorder", ""); }
    public static void setDockOrder(Context c, String json) {
        if (json == null || json.length() == 0) return;
        c.getSharedPreferences(PK, 0).edit().putString("dockorder", json).apply();
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
        loadWallFiles();
        frontGateUntil = android.os.SystemClock.uptimeMillis() + 800; /* chờ biết đang ở Desktop hay app khác rồi mới vẽ Dock: khỏi đổi cỡ nhiều lần lúc mới vào */
        if (!isEnabled(this)) setImmersivePolicy(false);
        apply();
        updateTopBar();
    }
    /* ---- Khởi động lại máy: mở menu nguồn của Android rồi tự bấm mục "Khởi động lại" (và nút xác nhận nếu có) ---- */
    private boolean clickByTexts(String[] texts) {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            for (AccessibilityWindowInfo w : ws) {
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                try {
                    if (getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue; /* bỏ cửa sổ của chính mình */
                    for (String t : texts) {
                        List<android.view.accessibility.AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(t);
                        if (found == null) continue;
                        for (android.view.accessibility.AccessibilityNodeInfo n : found) {
                            android.view.accessibility.AccessibilityNodeInfo c = n; int up = 0;
                            while (c != null && !c.isClickable() && up++ < 6) c = c.getParent();
                            if (c != null && c.isClickable() && c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
                        }
                    }
                } finally { try { root.recycle(); } catch (Throwable x) { } }
            }
        } catch (Throwable t) { }
        return false;
    }
    public boolean powerRestart() {
        return powerPick(new String[]{"Khởi động lại", "Restart", "Reboot"},
            new String[]{"OK", "Đồng ý", "Yes", "Khởi động lại", "Restart", "Reboot"});
    }
    /* Tắt nguồn thật: mở menu nguồn Android rồi tự bấm "Tắt nguồn" (và nút xác nhận nếu có) */
    public boolean powerOff() {
        return powerPick(new String[]{"Tắt nguồn", "Power off", "Shut down"},
            new String[]{"OK", "Đồng ý", "Yes", "Tắt nguồn", "Power off", "Shut down"});
    }
    private boolean powerPick(final String[] item, final String[] confirm) {
        if (!performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)) return false;
        final long start = android.os.SystemClock.uptimeMillis();
        final boolean[] clicked = {false};
        final long[] clickAt = {0};
        h.postDelayed(new Runnable() { public void run() {
            long now = android.os.SystemClock.uptimeMillis();
            if (!clicked[0]) {
                if (clickByTexts(item)) { clicked[0] = true; clickAt[0] = now; }
                else if (now - start > 4000) return; /* không tìm thấy: để nguyên menu cho người dùng tự bấm */
            } else {
                if (now - clickAt[0] > 2500) return;
                if (now - clickAt[0] > 400 && clickByTexts(confirm)) return; /* có hộp xác nhận thì bấm đồng ý */
            }
            h.postDelayed(this, 200);
        } }, 500);
        return true;
    }
    @Override public boolean onUnbind(Intent i) { inst = null; tearDown(); return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; tearDown(); super.onDestroy(); }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c); removeTopBar(); updateTopBar(); hideDockBar();
        /* Xoay màn hình: thanh hệ thống và gốc toạ độ cửa sổ đổi => đo/hiệu chuẩn lại từ đầu (chỉ lúc này) */
        barMax = 0; barCand = 0; yAdjLocked = false; dockYAdj = 0; calTries = 0; lastBarPx = -1;
        rebuildWall();
        updateDockBar();
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
        trackExt(pk);
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
    private boolean frontWant;
    private final Runnable frontCommit = new Runnable() { public void run() { commitLauncherFront(frontWant); } };
    /** Sự kiện cửa sổ dồn dập khi vuốt về Home/đa nhiệm (Desktop -> launcher hệ thống -> Desktop...): chờ ổn định rồi mới đổi, để Dock không giật. */
    private boolean frontKnown = false; private long frontGateUntil = 0;
    private void setLauncherFront(boolean b) {
        frontKnown = true;
        frontWant = b;
        h.removeCallbacks(frontCommit);
        if (launcherFront == b) return;
        h.postDelayed(frontCommit, 220);
    }
    private void commitLauncherFront(boolean b) {
        if (launcherFront == b) return;
        launcherFront = b; frontKnown = true;
        /* KHÔNG đặt lại dockYAdj: độ lệch gốc toạ độ là hằng số của màn hình, đặt lại mỗi lần đổi trạng thái làm Dock nhảy lên xuống */
        dismissPop();
        updateTopBar();
        updateDockBar();
        applyDockBase(); /* ở app ngoài: Dock cố định 53%; ở Desktop Mini: theo cỡ người dùng chỉnh */
    }
    /** MainActivity báo đã lên trước: chắc chắn hơn chờ sự kiện trợ năng (lúc mới mở app launcherFront còn mặc định false làm Dock đo sai cỡ). */
    public static void launcherResumed() {
        final LockService s = inst; if (s == null) return;
        s.h.post(new Runnable() { public void run() { s.frontKnown = true; s.frontWant = true; s.h.removeCallbacks(s.frontCommit); s.commitLauncherFront(true); } });
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private Point realSize() {
        Point p = new Point();
        try { wm.getDefaultDisplay().getRealSize(p); } catch (Exception e) { p.set(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels); }
        return p;
    }

    /** Bấm nút: đưa Desktop Mini lên trước (MainActivity tự thu nhỏ mọi cửa sổ để hiện màn hình Desktop). */
    private void goDesktop() {
        MainActivity.leaveFullscreen(); videoFull = false;
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
        if (fs != topHidden && android.os.SystemClock.uptimeMillis() >= topPeekUntil) setTopHidden(fs); /* đang hiện tạm (kéo từ trên xuống) thì để bộ đếm 3,5 giây tự ẩn */
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
    /** Chạm 2 lần chỗ trống trên Dock nổi: đưa Desktop Mini lên trước và mở đa nhiệm (giống chạm 2 lần chỗ trống Desktop). */
    private void openMultitask() {
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("openMS", true));
        } catch (Exception e) { }
    }
    private void forward(String id) {
        MainActivity.leaveFullscreen(); videoFull = false;
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("mbClick", id).putExtra("mbFront", launcherFront));
        } catch (Exception e) { }
    }
    private void goHome() {
        MainActivity.leaveFullscreen(); videoFull = false;
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception e) { }
    }
    private void openInLauncher(String app) { openInLauncher(app, false); }
    /** toggle = bấm icon trên Dock nổi: nếu Desktop Mini đang ở trước và app đó đang mở ở trên cùng thì thu xuống (như nút −). */
    private void openInLauncher(String app, boolean toggle) {
        MainActivity.leaveFullscreen(); videoFull = false;
        try {
            startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("openApp", app).putExtra("openToggle", toggle && launcherFront));
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
        /** Đặt khung menu nằm sát đáy cửa sổ (dùng cho menu của Dock, mở lên phía trên). */
        void bottomAlign(float winH) { panel.offsetTo(panel.left, Math.max(dp(2), winH - panel.height() - dp(8))); }
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
        private final Paint tintP = new Paint(); private float tintH = -1;
        TopBar(Context c) {
            super(c);
            bg.setColor(0xFF111113); bg.setStyle(Paint.Style.FILL);
            tx.setColor(0xFFFFFFFF); tx.setTextSize(dp(13)); tx.setFakeBoldText(true); tx.setShadowLayer(dp(2), 0, dp(1), 0x66000000); /* chữ vẫn dễ đọc khi nền là hình nền sáng */
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
            if (wallTop != null) {
                /* một hình liền với Desktop: lát phía trên của hình nền + lớp phủ tối nhẹ cho chữ dễ đọc */
                float sh = Math.min(wallTop.getHeight(), hh);
                c.drawBitmap(wallTop, new Rect(0, 0, wallTop.getWidth(), (int) sh), new RectF(0, 0, w, sh), null);
                /* phủ tối nhẹ giảm dần về mép dưới (mép dưới = 0) để thanh menu hoà liền vào Desktop, không lộ đường ranh */
                if (tintH != hh) { tintH = hh; tintP.setShader(new android.graphics.LinearGradient(0, 0, 0, hh, 0x38000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP)); }
                c.drawRect(0, 0, w, hh, tintP);
            } else c.drawRect(0, 0, w, hh, bg);
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
                float s = dp(0.9108f), ox = rx - dp(9) - 8 * s, oy = cy - 8 * s; /* to thêm 15% so với bản trước (0.792 -> 0.9108), độ dày nét giữ nguyên */ /* cao ~14dp, cân với biểu tượng Wi-Fi */
                android.graphics.Path bp = new android.graphics.Path();
                bp.moveTo(ox + 4 * s, oy + 4.5f * s); bp.lineTo(ox + 12 * s, oy + 11.5f * s); bp.lineTo(ox + 8 * s, oy + 15 * s);
                bp.lineTo(ox + 8 * s, oy + 1 * s); bp.lineTo(ox + 12 * s, oy + 4.5f * s); bp.lineTo(ox + 4 * s, oy + 11.5f * s);
                Paint bpn = new Paint(ic); bpn.setStrokeWidth(ic.getStrokeWidth() * 1.2f);
                c.drawPath(bp, bpn);
                hit.put("bt", new RectF(rx - dp(24), 0, rx + dp(8), hh)); rx -= dp(28);
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
                case MotionEvent.ACTION_UP: { String id = at(ev.getX(), ev.getY()); if (id != null && id.equals(down)) { menuTap(id); topTapDone(); } down = null; return true; }
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
        dismissPop(); dismissSpot(); h.removeCallbacks(topTick); h.removeCallbacks(topPeekEnd); topPeekUntil = 0; hideTopHot(); topHidden = false;
        if (topBar != null) { try { wm.removeView(topBar); } catch (Exception e) { } topBar = null; topLp = null; }
    }
    /* ---- Đang xem toàn màn hình: kéo từ mép trên xuống thì thanh menu hiện ngay, giữ 3,5 giây rồi tự ẩn (hoặc ẩn sau khi bấm xong) ---- */
    private android.view.View topHot;
    private long topPeekUntil = 0;
    private final Runnable topPeekEnd = new Runnable() { public void run() {
        h.removeCallbacks(topPeekEnd);
        if (!popId.isEmpty()) { topPeekUntil = android.os.SystemClock.uptimeMillis() + 1000; h.postDelayed(topPeekEnd, 1000); return; } /* menu đang mở: chờ đóng rồi mới ẩn */
        topPeekUntil = 0;
        if (topBar != null && (videoFull || barHide || externalFullscreenTop())) setTopHidden(true);
    } };
    private void peekTop() {
        if (topBar == null || topLp == null) return;
        topPeekUntil = android.os.SystemClock.uptimeMillis() + 3500;
        setTopHidden(false);
        h.removeCallbacks(topPeekEnd); h.postDelayed(topPeekEnd, 3500);
    }
    /** Vừa bấm xong một nút trên thanh đang hiện tạm: tính lại 3,5 giây từ lúc bấm rồi mới ẩn. */
    private void topTapDone() {
        if (android.os.SystemClock.uptimeMillis() >= topPeekUntil) return;
        topPeekUntil = android.os.SystemClock.uptimeMillis() + 3500;
        h.removeCallbacks(topPeekEnd); h.postDelayed(topPeekEnd, 3500); /* bấm xong thì 3,5 giây sau mới ẩn */
    }
    private void showTopHot() {
        if (topHot != null || wm == null || topBar == null || barHide) return;
        final android.view.View v = new android.view.View(this);
        v.setOnTouchListener(new android.view.View.OnTouchListener() {
            float y0 = -1; boolean fired;
            public boolean onTouch(android.view.View x, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: y0 = e.getRawY(); fired = false; break;
                    case MotionEvent.ACTION_MOVE: if (!fired && y0 >= 0 && e.getRawY() - y0 > dp(3)) { fired = true; peekTop(); } break;
                    case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: y0 = -1; break;
                }
                return true;
            }
        });
        Point real = realSize();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(real.x, dp(28),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = 0;
        try { wm.addView(v, lp); topHot = v; } catch (Exception e) { topHot = null; }
    }
    private void hideTopHot() {
        if (topHot != null) { try { wm.removeView(topHot); } catch (Exception e) { } }
        topHot = null;
    }
    /** Ẩn (chỉ còn dải 1dp, không nhận chạm) khi app ngoài toàn màn hình; hiện lại đủ chiều cao khi hết. */
    private void setTopHidden(boolean b) {
        topHidden = b;
        if (b) showTopHot(); else hideTopHot();
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
    private WindowManager.LayoutParams dockLp;
    private static final int DOCK_GAP_DP = 0;
    private static volatile int dockBase = 46;
    /** Cỡ người dùng chỉnh (chỉ áp dụng khi đang ở Desktop Mini). */
    private static volatile int userDockBase = 30; /* Dock ở màn hình chính cố định 66% (66% của 45px ≈ 30px), không còn chỉnh được */
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
    private int barCand = 0; private long barCandAt = 0;
    private int barMax = 0; /* chiều cao lớn nhất đã đo được: chỉ tăng, không tự thu nhỏ lại (thanh tác vụ lúc hiện lúc ẩn làm số đo nhảy) */
    private int sysBarHeight() {
        int best = navBarHeight(), cap = dp(96); /* thanh điều hướng/tác vụ thật không cao quá ~96dp; số lớn hơn là cửa sổ tạm lúc vuốt */
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
        if (best > barMax) {
            if (recentsOpen) return barMax; /* đang đa nhiệm: số đo tạm thời, bỏ qua */
            long now = android.os.SystemClock.uptimeMillis();
            if (barMax == 0) { barMax = best; barCand = 0; }       /* lần đo đầu: nhận ngay */
            else if (best != barCand) { barCand = best; barCandAt = now; } /* số lớn hơn: phải giữ nguyên ≥600ms mới nhận */
            else if (now - barCandAt >= 600) { barMax = best; barCand = 0; }
        } else barCand = 0;
        return barMax;
    }
    /** Chiều cao cửa sổ Dock (px): ở Desktop = đúng thanh icon; ở app khác = đủ cao để phủ kín thanh điều hướng/thanh tác vụ của ROM. */
    /** Cố định theo số đo thật (Yoga Book, cả ngang lẫn dọc): thanh Dock cao 48dp, sát đáy, không lệch, không hở. Không còn đo lại. */
    static final int DOCK_BAR_DP = 48;
    private int dockBarPx() { return dp(DOCK_BAR_DP); }
    /** Phần màn hình thật nằm dưới vùng Desktop (thanh điều hướng còn hiện...): Dock kéo dài phủ kín, icon nâng lên bằng đúng phần đó nên không còn khoảng trống. */
    private volatile int desktopGapPx = 0;
    public static void setDesktopGap(final int px) { /* gap dưới Desktop đo được luôn 0: cố định, bỏ qua */ }
    private int dockGrowPx() { return 0; } /* không còn phóng đại nên cửa sổ Dock không bao giờ đổi cao */
    /** Đặt cửa sổ theo toạ độ tuyệt đối từ mép trên của màn hình THẬT (như thanh Desktop cũ) nên đè được lên thanh điều hướng. */
    private void placeDock(WindowManager.LayoutParams lp) {
        Point real = realSize(); int hh = dockBarPx() + dockGrowPx();
        lp.width = real.x; lp.height = hh; lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = real.y - hh + dockYAdj; int ime = imeTop(); if (ime > 0 && ime < real.y) lp.y = Math.min(lp.y, ime - hh); /* Dock nổi ngay trên bàn phím, không che phím */
    }
    /* Hệ thống có thể tính toạ độ y từ mép trên VÙNG NỘI DUNG (dưới thanh trạng thái) chứ không phải mép trên màn hình thật => cửa sổ bị đẩy xuống.
     * Đo vị trí thật của cửa sổ Dock trên màn hình rồi bù đúng phần lệch (tối đa vài lần mỗi lần hiện, không dao động). */
    private int dockYAdj = 0, calTries = 0;
    private boolean yAdjLocked = false; /* đã hiệu chuẩn xong: không đo/nhích lại nữa */
    private final Runnable calibrateTick = new Runnable() { public void run() { calibrateDock(); } };
    private void calibrateDock() { /* đã cố định dockYAdj = 0 theo số đo thật: không hiệu chuẩn nữa */ yAdjLocked = true; }
    private static final int DOCK_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
    private int dockHDp() { return Math.round(dockBarPx() / getResources().getDisplayMetrics().density); }
    private int dockPadDp() { return launcherFront ? Math.round(desktopGapPx / getResources().getDisplayMetrics().density) : 0; }
    private int lastBarPx = -1;
    private void relayoutDock() {
        if (!dockShown || dockLp == null || dockView == null) return;
        int y = dockLp.y, hh = dockLp.height; placeDock(dockLp);
        int bp = dockBarPx();
        if (bp != lastBarPx) { lastBarPx = bp; if (!yAdjLocked) { calTries = 0; h.removeCallbacks(calibrateTick); h.postDelayed(calibrateTick, 300); } if (dockWeb != null && dockReady) dockWeb.evaluateJavascript("window.setLayout&&setLayout(" + dockHDp() + "," + dockPadDp() + ")", null); }
        if (y != dockLp.y || hh != dockLp.height) { try { wm.updateViewLayout(dockView, dockLp); } catch (Exception e) { } h.postDelayed(new Runnable() { public void run() { MainActivity.dockMoved(); } }, 300); }
    }
    public static boolean isRunning() { return inst != null; }
    /** Toạ độ y thật (px) của mép trên Dock nổi trên màn hình; -1 nếu Dock đang ẩn. */
    public static int dockTopOnScreen() {
        final LockService s = inst;
        try {
            if (s == null || !s.dockShown || s.dockView == null || s.dockView.getHeight() <= 0 || !s.dockView.isAttachedToWindow()) return -1;
            int[] loc = new int[2]; s.dockView.getLocationOnScreen(loc); return loc[1];
        } catch (Throwable t) { return -1; }
    }
    public static void setDockBase(Context c, int base) {
        /* cỡ Dock đã cố định 66%: bỏ qua giá trị giao diện gửi sang */
    }
    private int pushedBase = -1, pushedH = -1;
    private void applyDockBase() {
        dockBase = effectiveDockBase();
        int hdp = dockHDp();
        if (dockWeb != null && dockReady && (dockBase != pushedBase || hdp != pushedH)) { pushedBase = dockBase; pushedH = hdp; dockWeb.evaluateJavascript("window.setBase&&setBase(" + dockBase + ");window.setLayout&&setLayout(" + dockHDp() + "," + dockPadDp() + ")", null); }
        relayoutDock();
    }
    private final Runnable dockTick = new Runnable() { public void run() {
        h.removeCallbacks(dockTick);
        if (!enabled || wm == null) return;
        relayoutDock(); /* đổi giữa Desktop/app khác, xoay màn hình, hoặc đo được thanh hệ thống cao hơn: chỉnh lại vị trí/chiều cao */
                if (android.os.SystemClock.uptimeMillis() >= dockQuietUntil) {
            boolean peeking = android.os.SystemClock.uptimeMillis() < dockPeekUntil;
            boolean hide = barHide || (dockAutoHide && !peeking); /* Dock luôn nổi; chỉ nhường chỗ cho bàn phím/khóa màn hình, hoặc tự ẩn nếu bật trong Cài đặt */
            boolean gate = !frontKnown && android.os.SystemClock.uptimeMillis() < frontGateUntil;
            if (hide && dockShown) hideDockBar(); else if (!hide && !dockShown && !gate) showDockBar();
            if (dockAutoHide && !dockShown && !barHide) showHot(); else hideHot();
        }
        h.postDelayed(dockTick, (!frontKnown && android.os.SystemClock.uptimeMillis() < frontGateUntil) ? 150 : 700);
    } };
    /** Nới cao cửa sổ Dock lúc phóng đại icon (nền thanh vẫn nằm sát đáy, phần nới ra trong suốt). */
    private void growDock(boolean on) {
        /* bỏ phóng đại: không nới cửa sổ nữa (nguyên nhân Dock giật lên xuống khi chạm giữ) */
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
    /** Mép trên của bàn phím đang hiện trên màn hình (-1 nếu không có bàn phím). */
    private int imeTop() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                Rect r = new Rect(); w.getBoundsInScreen(r); return r.top;
            }
        } catch (Throwable t) { }
        return -1;
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
        @JavascriptInterface public void open(final String id) { h.post(new Runnable() { public void run() { dismissPop(); openInLauncher(id, true); } }); }
        /** Chạm chỗ trống trên Dock: về màn hình Desktop, thu hết app đang mở (MainActivity.onNewIntent không kèm extra -> nativeHome). */
        @JavascriptInterface public void home() { h.post(new Runnable() { public void run() { dismissPop(); goHome(); } }); }
        @JavascriptInterface public void multitask() { h.post(new Runnable() { public void run() { openMultitask(); } }); }
        /** Chạm 2 lần vùng trống bên trái Dock: Back (quay lại như phím Back hệ thống). */
        @JavascriptInterface public void back() { h.post(new Runnable() { public void run() { dismissPop(); try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Exception e) { } } }); }
        @JavascriptInterface public void ai() { h.post(new Runnable() { public void run() { forward("aib"); } }); }
        @JavascriptInterface public void shot() { h.post(new Runnable() { public void run() { dockShot(); } }); }
        @JavascriptInterface public void shotRegion() { h.post(new Runnable() { public void run() { dockShotRegion(); } }); }
        @JavascriptInterface public void openExt(final String pk) { h.post(new Runnable() { public void run() { openExternal(pk); } }); }
        @JavascriptInterface public void touch() { peekDock(); }
        /** Chuột phải / nhấn giữ icon trên Dock: hiện menu (Mở, Thoát, Xóa khỏi Dock / Giữ trong Dock) phía trên Dock. */
        @JavascriptInterface public void menu(final String id, final String name, final float xCss, final boolean pinned, final boolean running) { h.post(new Runnable() { public void run() { showDockMenu(id, name, xCss, pinned, running); } }); }
        /** Người dùng kéo đổi chỗ icon: JSON mảng id theo thứ tự mới (các icon đã ghim). */
        @JavascriptInterface public void reorder(final String json) { h.post(new Runnable() { public void run() { dockReorder(json); } }); }
        @JavascriptInterface public void grow(final boolean on) { h.post(new Runnable() { public void run() { growDock(on); } }); }
    }
    /* ================= Menu và sắp xếp icon trên Dock ================= */
    private org.json.JSONArray readDockData() {
        try { return new org.json.JSONArray(getSharedPreferences(PK, 0).getString("data", "[]")); } catch (Throwable t) { return new org.json.JSONArray(); }
    }
    /** Các id đã ghim, theo đúng thứ tự hiện tại. */
    private java.util.ArrayList<String> pinnedIds() {
        java.util.ArrayList<String> o = new java.util.ArrayList<String>();
        org.json.JSONArray d = readDockData();
        for (int i = 0; i < d.length(); i++) { org.json.JSONObject j = d.optJSONObject(i); if (j != null && j.optInt("p", 1) != 0) o.add(j.optString("id")); }
        String s = getSharedPreferences(PK, 0).getString("dockorder", "");
        if (s.length() > 0) {
            try {
                org.json.JSONArray a = new org.json.JSONArray(s); java.util.ArrayList<String> r = new java.util.ArrayList<String>();
                for (int i = 0; i < a.length(); i++) { String id = a.optString(i); if (id.length() > 0 && !r.contains(id)) r.add(id); }
                if (!r.isEmpty()) return r;
            } catch (Throwable t) { }
        }
        return o;
    }
    /** Lưu thứ tự mới, sắp lại dữ liệu Dock, vẽ lại và báo cho giao diện Desktop (đang chạy) cập nhật theo. */
    private void commitDockOrder(java.util.List<String> ord) {
        try {
            org.json.JSONArray oa = new org.json.JSONArray(); for (String s : ord) oa.put(s);
            org.json.JSONArray d = readDockData(), nd = new org.json.JSONArray();
            for (String id : ord) for (int i = 0; i < d.length(); i++) { org.json.JSONObject o = d.optJSONObject(i); if (o != null && id.equals(o.optString("id"))) { o.put("p", 1); nd.put(o); break; } }
            for (int i = 0; i < d.length(); i++) {
                org.json.JSONObject o = d.optJSONObject(i); if (o == null || ord.contains(o.optString("id"))) continue;
                if (o.optInt("r", 0) == 1) { o.put("p", 0); nd.put(o); } /* app đang chạy mà bị bỏ ghim: vẫn hiện tạm tới khi thoát */
            }
            getSharedPreferences(PK, 0).edit().putString("dockorder", oa.toString()).putString("data", nd.toString()).apply();
            pushDockData();
            MainActivity.push("nativeDockOrder", oa.toString());
        } catch (Throwable t) { }
    }
    private void dockReorder(String json) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(json); java.util.ArrayList<String> o = new java.util.ArrayList<String>();
            for (int i = 0; i < a.length(); i++) { String id = a.optString(i); if (id.length() > 0 && !o.contains(id)) o.add(id); }
            if (!o.isEmpty()) commitDockOrder(o);
        } catch (Throwable t) { }
    }
    private void dockRemove(String id) {
        if (id.equals("launchpad")) return;
        java.util.ArrayList<String> o = pinnedIds(); o.remove(id); commitDockOrder(o);
    }
    private void dockKeep(String id) {
        java.util.ArrayList<String> o = pinnedIds(); if (!o.contains(id)) o.add(id); commitDockOrder(o);
    }
    private void showDockMenu(final String id, String name, float xCss, boolean pinned, boolean running) {
        if (wm == null || dockLp == null || !dockShown) return;
        dismissPop();
        java.util.List<PI> L = new java.util.ArrayList<PI>();
        if (name != null && name.length() > 0) L.add(pText(name));
        L.add(pItem("Mở", new Runnable() { public void run() { openInLauncher(id); } }));
        if (running) L.add(pItem("Đóng cửa sổ", new Runnable() { public void run() { MainActivity.push("nativeCloseApp", org.json.JSONObject.quote(id)); } }));
        if (!id.equals("launchpad")) {
            L.add(pSep());
            if (pinned) L.add(pItem("Xóa khỏi Dock", new Runnable() { public void run() { dockRemove(id); } }));
            else L.add(pItem("Giữ trong Dock", new Runnable() { public void run() { dockKeep(id); } }));
        }
        float d = getResources().getDisplayMetrics().density;
        final PopView pv = new PopView(this, L, xCss * d - dp(115));
        int y0 = topLp != null ? topLp.height : 0, wh = Math.max(dp(60), dockLp.y - y0);
        pv.bottomAlign(wh);
        Point sz = realSize();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(sz.x, wh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = y0;
        try { wm.addView(pv, lp); pop = pv; popId = "dock:" + id; } catch (Exception e) { pop = null; popId = ""; }
    }
    /* ================= App ngoài trên Dock =================
     * App nào vừa lên trước (Zalo, Chrome...) thì icon thật của nó hiện ngay sau các icon Dock, có chấm bên dưới; bấm vào để mở lại. */
    private final java.util.LinkedList<String> extPk = new java.util.LinkedList<String>();
    private final java.util.HashMap<String, String> extIcons = new java.util.HashMap<String, String>();
    private Set<String> homePk;
    private void trackExt(String pk) {
        try {
            if (pk.equals("android") || pk.equals(getPackageName())) return;
            if (homePk == null) {
                homePk = new HashSet<String>();
                Intent hi = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
                for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(hi, 0)) {
                    /* Chỉ coi là trình khởi chạy khi chính activity HOME đó cũng là icon LAUNCHER. App như Cài đặt hay Google có mục HOME phụ nhưng không phải launcher, nên vẫn hiện trên Dock. */
                    Intent li = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(r.activityInfo.packageName);
                    for (android.content.pm.ResolveInfo l : getPackageManager().queryIntentActivities(li, 0)) {
                        if (l.activityInfo.name.equals(r.activityInfo.name)) { homePk.add(r.activityInfo.packageName); break; }
                    }
                }
            }
            if (homePk.contains(pk)) return;
            if (getPackageManager().getLaunchIntentForPackage(pk) == null) return;
        } catch (Throwable t) { return; }
        if (!extPk.isEmpty() && extPk.getFirst().equals(pk)) return;
        extPk.remove(pk); extPk.addFirst(pk);
        while (extPk.size() > 4) extPk.removeLast();
        pushExt();
    }
    private String extIconUrl(String p) {
        String u = extIcons.get(p); if (u != null) return u;
        try {
            android.graphics.drawable.Drawable d = getPackageManager().getApplicationIcon(p);
            int sz = dp(48);
            Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, sz, sz); d.draw(new Canvas(b));
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo); b.recycle();
            u = "data:image/png;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
            extIcons.put(p, u); return u;
        } catch (Throwable t) { return null; }
    }
    /** App ngoài đã bị tắt/xóa khỏi đa nhiệm: gỡ icon + chấm của nó khỏi Dock nổi ngay (trước đây danh sách chỉ thêm, không bao giờ bớt). */
    public static void forgetExt(final String pk) {
        final LockService s = inst; if (s == null || pk == null) return;
        s.h.post(new Runnable() { public void run() {
            if (s.extPk.remove(pk)) s.pushExt();
            try { s.getSharedPreferences(PK, 0).edit().remove("extlaunched").apply(); } catch (Throwable t) { }
        } });
    }
    /** "Xóa tất cả" ở đa nhiệm: gỡ mọi icon app ngoài khỏi Dock nổi (kể cả app không có thẻ trong đa nhiệm). */
    public static void forgetAllExt() {
        final LockService s = inst; if (s == null) return;
        s.h.post(new Runnable() { public void run() {
            s.extPk.clear(); s.pushExt();
            try { s.getSharedPreferences(PK, 0).edit().remove("extlaunched").apply(); } catch (Throwable t) { }
        } });
    }
    private void pushExt() {
        if (dockWeb == null || !dockReady) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (String p : extPk) {
                String u = extIconUrl(p); if (u == null) continue;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("p", p); o.put("n", labelOf(p)); o.put("u", u); arr.put(o);
            }
            dockWeb.evaluateJavascript("window.setExt&&setExt(" + arr.toString() + ")", null);
        } catch (Throwable t) { }
    }
    private void openExternal(String pk) {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pk);
            if (i != null) { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); noteExt(this, pk); }
        } catch (Throwable t) { }
    }

    /* ================= Chụp màn hình =================
     * Bấm 1 lần: chụp cả màn hình (app ngoài lẫn app trong). Bấm 2 lần: nhấn giữ và kéo để chọn vùng, chỉ chụp đúng vùng đó. */
    /** Ẩn Dock một lúc (ShotService gọi sau khi hộp thoại xin quyền đóng). */
    public static void quietDock(final long ms) {
        final LockService s = inst; if (s == null) return;
        /* Không còn ẩn Dock khi chụp màn hình (theo yêu cầu): giữ hàm cho nơi gọi cũ nhưng không làm gì */
    }
    private void toastShot(String m) { try { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable t) { } }
    /** Lấy ảnh toàn màn hình (Android 11+: API trợ năng; máy cũ như Android 7: MediaProjection, xin quyền một lần). */
    private void grabScreen(final ShotService.Cb cb) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                takeScreenshot(android.view.Display.DEFAULT_DISPLAY, getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult r) {
                        Bitmap out = null;
                        try {
                            Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                            out = hw.copy(Bitmap.Config.ARGB_8888, false);
                            r.getHardwareBuffer().close();
                        } catch (Throwable t) { out = null; }
                        cb.done(out);
                    }
                    @Override public void onFailure(int code) { cb.done(null); }
                });
                return;
            } catch (Throwable t) { /* rơi xuống MediaProjection */ }
        }
        ShotService.capture(this, cb);
    }
    private void dockShot() {
        /* Không ẩn/đụng gì tới Dock khi chụp toàn màn hình: Dock giữ nguyên và có trong ảnh chụp */
        h.postDelayed(new Runnable() { public void run() {
            if (Build.VERSION.SDK_INT >= 28 && Build.VERSION.SDK_INT < 30) {
                try { performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT); } catch (Throwable t) { }
                return;
            }
            grabScreen(new ShotService.Cb() { public void done(Bitmap b) {
                if (b == null) { toastShot("Không chụp được màn hình"); return; }
                toastShot(ShotService.save(LockService.this, b) ? "Đã lưu ảnh chụp màn hình" : "Không lưu được ảnh");
            } });
        } }, 350);
    }
    private android.view.View selView;
    private void dockShotRegion() {
        if (selView != null) return;
        final Point real = realSize(); /* Dock vẫn hiện nguyên trong lúc chọn vùng và có trong ảnh chụp */
        final float dens = getResources().getDisplayMetrics().density;
        final RectF sel = new RectF(); final boolean[] has = {false};
        final Paint dim = new Paint(); dim.setColor(0x80000000);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG); edge.setColor(0xFFFFFFFF); edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(2 * dens);
        final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG); txt.setColor(0xFFFFFFFF); txt.setTextSize(16 * dens); txt.setTextAlign(Paint.Align.CENTER); txt.setShadowLayer(3 * dens, 0, 1, 0xFF000000);
        final View v = new View(this) {
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), hh = getHeight();
                if (!has[0]) { c.drawRect(0, 0, w, hh, dim); c.drawText("Kéo để chọn vùng chụp", w / 2f, hh / 2f, txt); return; }
                c.drawRect(0, 0, w, sel.top, dim); c.drawRect(0, sel.bottom, w, hh, dim);
                c.drawRect(0, sel.top, sel.left, sel.bottom, dim); c.drawRect(sel.right, sel.top, w, sel.bottom, dim);
                c.drawRect(sel, edge);
            }
        };
        final Runnable[] kill = new Runnable[1];
        kill[0] = new Runnable() { public void run() { closeSel(); } };
        v.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: x0 = e.getX(); y0 = e.getY(); has[0] = true; sel.set(x0, y0, x0, y0); h.removeCallbacks(kill[0]); view.invalidate(); return true;
                    case MotionEvent.ACTION_MOVE:
                        sel.set(Math.min(x0, e.getX()), Math.min(y0, e.getY()), Math.max(x0, e.getX()), Math.max(y0, e.getY())); view.invalidate(); return true;
                    case MotionEvent.ACTION_UP: {
                        sel.set(Math.min(x0, e.getX()), Math.min(y0, e.getY()), Math.max(x0, e.getX()), Math.max(y0, e.getY()));
                        final RectF r = new RectF(sel); int[] loc = new int[2]; view.getLocationOnScreen(loc);
                        r.offset(loc[0], loc[1]); /* toạ độ trong cửa sổ -> toạ độ màn hình thật */
                        closeSel();
                        if (r.width() < 16 * dens || r.height() < 16 * dens) return true;
                        h.postDelayed(new Runnable() { public void run() {
                            grabScreen(new ShotService.Cb() { public void done(Bitmap b) {
                                if (b == null) { toastShot("Không chụp được màn hình"); return; }
                                try {
                                    float sx = b.getWidth() / (float) real.x, sy = b.getHeight() / (float) real.y;
                                    int l = Math.max(0, Math.round(r.left * sx)), t = Math.max(0, Math.round(r.top * sy));
                                    int rr = Math.min(b.getWidth(), Math.round(r.right * sx)), bb = Math.min(b.getHeight(), Math.round(r.bottom * sy));
                                    Bitmap crop = Bitmap.createBitmap(b, l, t, Math.max(1, rr - l), Math.max(1, bb - t));
                                    toastShot(ShotService.save(LockService.this, crop) ? "Đã lưu ảnh vùng chọn" : "Không lưu được ảnh");
                                } catch (Throwable ex) { toastShot("Không cắt được vùng chọn"); }
                            } });
                        } }, 250);
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL: closeSel(); return true;
                }
                return true;
            }
        });
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(real.x, real.y,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT; lp.x = 0; lp.y = 0;
        try { wm.addView(v, lp); selView = v; h.postDelayed(kill[0], 15000); } catch (Throwable t) { }
    }
    private void closeSel() {
        if (selView != null) { try { wm.removeView(selView); } catch (Throwable t) { } selView = null; }
    }
    private void showDockBar() {
        if (wm == null || dockShown) return;
        dockGrown = false; dockBase = effectiveDockBase();
        if (dockView == null) {
            dockView = new android.widget.FrameLayout(this);
            dockWeb = new android.webkit.WebView(new android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault));
            dockWeb.getSettings().setJavaScriptEnabled(true);
            dockWeb.setBackgroundColor(0x00000000); /* nền thanh do dock.html vẽ; phần nới lúc phóng đại phải trong suốt */
            dockWeb.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            
            dockWeb.setVerticalScrollBarEnabled(false); dockWeb.setHorizontalScrollBarEnabled(false);
            dockWeb.addJavascriptInterface(new DockJs(), "DockApp");
            dockWeb.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public void onPageFinished(android.webkit.WebView v, String url) { dockReady = true; wallPushed = null; pushDockData(); }
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
        try { wm.addView(dockView, lp); dockShown = true; h.postDelayed(new Runnable() { public void run() { MainActivity.dockMoved(); } }, 500); if (!yAdjLocked) { calTries = 0; h.removeCallbacks(calibrateTick); h.postDelayed(calibrateTick, 300); } } catch (Throwable e) { dockShown = false; }
    }
    private void hideDockBar() {
        if (dockView != null && dockShown) { try { wm.removeView(dockView); } catch (Exception e) { } }
        dockShown = false;
    }
    /* ================= Hình nền liền một khối: thanh menu trên – Desktop – Dock dưới =================
     * Giao diện Desktop gửi nguồn hình nền (data URL / "asset"); dịch vụ cắt lát phía trên và lát phía dưới theo đúng kiểu cover
     * của Desktop rồi vẽ làm nền cho thanh menu nổi và Dock nổi, nên cả ba nhìn như một hình duy nhất, ở cả Desktop lẫn app khác. */
    private static volatile String wallSrc = null; /* null = Desktop chưa gửi lần nào: lát nạp từ file chỉ là bản tạm cho tới khi Desktop xác nhận */
    private volatile Bitmap wallTop;
    private volatile String wallDockUrl = "";
    private volatile int wallGen = 0;
    private final Object wallLock = new Object();
    private String wallPushed = null; /* data URL đã gửi cho WebView Dock: không gửi lại khi không đổi */
    private static final int WALL_TOP_DP = 64, WALL_DOCK_DP = 200;
    /** Hình nền toàn màn hình (đã phủ đúng cỡ màn hình) để Desktop hiển thị khớp với thanh menu và Dock. */
    public static volatile String deskWallUrl = "";
    public static void setWall(String src) {
        String n = src == null ? "" : src;
        if (n.equals(wallSrc)) return;
        wallSrc = n;
        final LockService s = inst; if (s != null) s.h.post(new Runnable() { public void run() { s.rebuildWall(); } });
    }
    private byte[] wallBytes(String src) throws Exception {
        if (src.startsWith("data:")) return android.util.Base64.decode(src.substring(src.indexOf(',') + 1), android.util.Base64.DEFAULT);
        if (src.equals("asset")) {
            java.io.InputStream in = getAssets().open("wallpaper.jpg");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close(); return bo.toByteArray();
        }
        return null;
    }
    private Bitmap wallStrip(Bitmap bm, float s, float x0, float y0, int w, int yScreen, int hPx) {
        Bitmap out = Bitmap.createBitmap(w, hPx, Bitmap.Config.ARGB_8888);
        /* kẹp trong biên ảnh: làm tròn có thể vượt 1px */
        Rect src = new Rect(Math.max(0, Math.round(x0 / s)), Math.max(0, Math.round((yScreen + y0) / s)),
            Math.min(bm.getWidth(), Math.round((x0 + w) / s)), Math.min(bm.getHeight(), Math.round((yScreen + y0 + hPx) / s)));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(out).drawBitmap(bm, src, new Rect(0, 0, w, hPx), p);
        return out;
    }
    private void rebuildWall() {
        if (wallSrc == null) return;   /* chưa có nguồn từ Desktop: giữ nguyên lát đã nạp từ file */
        final String src = wallSrc; final int gen = ++wallGen; final Point real = realSize();
        final int topPx = dp(WALL_TOP_DP), dockPx = Math.min(dp(WALL_DOCK_DP), real.y / 2);
        new Thread(new Runnable() { public void run() {
            Bitmap top = null; String url = ""; String deskU = "";
            try {
                byte[] b = src.length() == 0 ? null : wallBytes(src);
                Bitmap bm = null;
                if (b != null) {
                    BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    int ss = 1; while (o.outWidth / (ss * 2) >= real.x) ss *= 2;
                    o = new BitmapFactory.Options(); o.inSampleSize = ss;
                    bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                }
                if (bm != null) {
                    /* cùng cách phủ "cover, căn giữa" như hình nền của Desktop */
                    float s = Math.max((float) real.x / bm.getWidth(), (float) real.y / bm.getHeight());
                    float x0 = (bm.getWidth() * s - real.x) / 2f, y0 = (bm.getHeight() * s - real.y) / 2f;
                    Bitmap full = wallStrip(bm, s, x0, y0, real.x, 0, real.y);
                    java.io.ByteArrayOutputStream fo = new java.io.ByteArrayOutputStream();
                    full.compress(Bitmap.CompressFormat.JPEG, 85, fo); full.recycle();
                    deskU = "data:image/jpeg;base64," + android.util.Base64.encodeToString(fo.toByteArray(), android.util.Base64.NO_WRAP);
                    top = wallStrip(bm, s, x0, y0, real.x, 0, topPx);
                    Bitmap bot = wallStrip(bm, s, x0, y0, real.x, real.y - dockPx, dockPx);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    bot.compress(Bitmap.CompressFormat.JPEG, 88, bo);
                    byte[] db = bo.toByteArray();
                    url = "data:image/jpeg;base64," + android.util.Base64.encodeToString(db, android.util.Base64.NO_WRAP);
                    synchronized (wallLock) { if (gen == wallGen) saveWallFiles(top, db, real.x); }   /* bản build cũ không được ghi đè bản mới */
                    bm.recycle(); bot.recycle();
                } else synchronized (wallLock) { if (gen == wallGen) clearWallFiles(); }   /* hình chuyển màu hoặc không đọc được: xoá lát cũ */
            } catch (Throwable t) { top = null; url = ""; synchronized (wallLock) { if (gen == wallGen) clearWallFiles(); } }
            final Bitmap ft = top; final String fu = url; final String fdesk = deskU;
            h.post(new Runnable() { public void run() {
                if (gen != wallGen) return;
                wallTop = ft; wallDockUrl = fu;
                deskWallUrl = fdesk; pushDeskWall();
                if (topBar != null) topBar.invalidate();
                pushDockWall();
            } });
        } }).start();
    }
    private void saveWallFiles(Bitmap top, byte[] dockJpg, int w) {
        try {
            java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "wall_top.jpg"));
            top.compress(Bitmap.CompressFormat.JPEG, 88, f); f.close();
            f = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "wall_dock.jpg")); f.write(dockJpg); f.close();
            getSharedPreferences(PK, 0).edit().putInt("wallW", w).apply();
        } catch (Throwable t) { }
    }
    private void clearWallFiles() {
        try { new java.io.File(getFilesDir(), "wall_top.jpg").delete(); new java.io.File(getFilesDir(), "wall_dock.jpg").delete(); getSharedPreferences(PK, 0).edit().remove("wallW").apply(); } catch (Throwable t) { }
    }
    /** Dịch vụ khởi động khi Desktop chưa chạy: nạp lát hình nền đã lưu lần trước (nếu cùng bề rộng màn hình). */
    private void loadWallFiles() {
        try {
            if (getSharedPreferences(PK, 0).getInt("wallW", -1) != realSize().x) return;
            java.io.File ft = new java.io.File(getFilesDir(), "wall_top.jpg"), fd = new java.io.File(getFilesDir(), "wall_dock.jpg");
            if (!ft.exists() || !fd.exists()) return;
            Bitmap t = BitmapFactory.decodeFile(ft.getAbsolutePath());
            java.io.FileInputStream in = new java.io.FileInputStream(fd); java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close();
            if (t != null) { wallTop = t; wallDockUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP); }
        } catch (Throwable t) { }
    }
    private void pushDeskWall() {
        MainActivity.push("setDeskWall", org.json.JSONObject.quote(deskWallUrl));
    }
    private void pushDockWall() {
        if (dockWeb == null || !dockReady) return;
        String u = wallDockUrl;
        if (u.equals(wallPushed)) return;   /* pushDockData chạy mỗi lần mở/đóng app: không gửi lại cả data URL khi hình không đổi */
        wallPushed = u;
        float d = getResources().getDisplayMetrics().density;
        dockWeb.evaluateJavascript(u.length() == 0 ? "window.setWall&&setWall('',0)" : "window.setWall&&setWall('" + u + "'," + Math.round(Math.min(dp(WALL_DOCK_DP), realSize().y / 2) / d) + ")", null);
    }
    private void pushDockData() {
        if (dockWeb == null || !dockReady) return;
        dockBase = effectiveDockBase(); pushedBase = dockBase; pushedH = dockHDp();
        String d = getSharedPreferences(PK, 0).getString("data", "[]");
        pushDockPrefs(); pushDockWall();
        dockWeb.evaluateJavascript("window.setBase&&setBase(" + dockBase + ");window.setLayout&&setLayout(" + dockHDp() + "," + dockPadDp() + ");window.setDock&&setDock(" + d + ");window.setShot&&setShot(true)", null);
        pushExt();
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
