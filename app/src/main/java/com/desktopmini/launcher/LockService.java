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
    private DeskButton btn;
    private WindowManager.LayoutParams btnLp;
    private boolean launcherFront, enabled = true;
    private boolean policyApplied;
    /* Khóa thanh hệ thống: tự đóng thanh thông báo (kéo từ trên xuống) và màn hình đa nhiệm ngay khi chúng vừa mở */
    private volatile boolean barLock = true, recentsOpen = false;
    private long lastClose = 0;
    /* Thanh Desktop: đang có video phát toàn màn hình (do các cửa sổ web của app báo) -> thanh tự ẩn */
    private static volatile boolean videoFull = false;
    private boolean barHidden = false;
    /** Các màn hình web của app gọi khi bắt đầu / kết thúc xem video toàn màn hình. Hết video thì thanh tự hiện lại. */
    public static void setVideoFull(final boolean on) {
        videoFull = on;
        final LockService s = inst;
        if (s != null && !on) s.h.post(new Runnable() { public void run() { s.setBarHidden(false); } });
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

    /** Giữ lại để MainActivity gọi (không còn dùng dữ liệu Dock). */
    public static void saveData(Context c, String json) { }
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
        super.onConfigurationChanged(c); barMax = 0; removeButton(); updateButton(); scheduleRefreshBar(); removeTopBar(); updateTopBar();
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
        videoFull = false; setBarHidden(false); /* app khác lên trước: không còn video của mình ở toàn màn hình */
        setLauncherFront(false);
        scheduleRefreshBar(); /* app khác vừa lên: đo lại để phủ kín thanh đa nhiệm */
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
        if (!enabled) { removeButton(); setImmersivePolicy(false); } else updateButton();
    }
    private void setLauncherFront(boolean b) {
        if (launcherFront == b) return;
        launcherFront = b;
        dismissPop();
        updateButton();
        updateTopBar();
    }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private Point realSize() {
        Point p = new Point();
        try { wm.getDefaultDisplay().getRealSize(p); } catch (Exception e) { p.set(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels); }
        return p;
    }

    /* ---- Nút tròn "Desktop" nổi ---- */
    private void updateButton() {
        boolean want = enabled && !launcherFront && wm != null;
        if (want) {
            setImmersivePolicy(true);
            if (btn == null) addButton();
        } else removeButton();
    }

    /** Thanh "Desktop" cố định sát cạnh dưới màn hình, che thanh điều hướng/đa nhiệm của hệ thống. Chỉ bấm, không kéo. */
    private class DeskButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            ic = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Rect src = new Rect(), dst = new Rect();
        DeskButton(Context c) {
            super(c);
            bg.setColor(0xFF000000); bg.setStyle(Paint.Style.FILL);
            ring.setColor(0x59FFFFFF); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(dp(1));
            ic.setColor(0xFFFFFFFF); ic.setStyle(Paint.Style.STROKE); ic.setStrokeWidth(dp(1.5f));
            ic.setStrokeCap(Paint.Cap.ROUND); ic.setStrokeJoin(Paint.Join.ROUND);
            tx.setColor(0xFFFFFFFF); tx.setTextAlign(Paint.Align.LEFT); tx.setTextSize(dp(13)); tx.setFakeBoldText(true);
            setContentDescription("Desktop");
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight(), cx = w / 2, cy = hh / 2;
            if (barHidden) return; /* thanh đang ẩn: không vẽ gì, cũng không nhận chạm */
            c.drawRect(0, 0, w, hh, bg);
            Bitmap pic = barPic();
            if (pic != null) {
                /* nền = dải cạnh dưới của hình, phủ kín chiều ngang, cắt bớt chiều dọc (căn giữa) */
                int pw = pic.getWidth(), ph = pic.getHeight();
                int sh = Math.min(ph, Math.max(1, Math.round(pw * hh / w)));
                src.set(0, (ph - sh) / 2, pw, (ph - sh) / 2 + sh); dst.set(0, 0, (int) w, (int) hh);
                c.drawBitmap(pic, src, dst, null);
                bg.setColor(0x59000000); c.drawRect(0, 0, w, hh, bg); bg.setColor(0xFF000000); /* lớp tối nhẹ cho chữ trắng dễ đọc */
            }
            c.drawLine(0, 0, w, 0, ring);
            /* biểu tượng màn hình máy tính + chữ "Desktop" nằm giữa thanh */
            float tw = tx.measureText("Desktop"), iw = dp(22), gap = dp(8), x0 = cx - (iw + gap + tw) / 2;
            float ih = dp(15);
            rf.set(x0, cy - ih / 2 - dp(1), x0 + iw, cy + ih / 2 - dp(3));
            c.drawRoundRect(rf, dp(2.5f), dp(2.5f), ic);
            c.drawLine(x0 + iw / 2, cy + ih / 2 - dp(3), x0 + iw / 2, cy + ih / 2, ic);
            c.drawLine(x0 + iw / 2 - dp(4), cy + ih / 2, x0 + iw / 2 + dp(4), cy + ih / 2, ic);
            c.drawText("Desktop", x0 + iw + gap, cy + dp(4.5f), tx);
        }
        private float dX, dY;
        @Override public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = ev.getRawX(); dY = ev.getRawY();
                    if (!barHidden) setAlpha(0.7f);
                    return true;
                case MotionEvent.ACTION_UP: {
                    setAlpha(1f);
                    int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (Math.abs(ev.getRawY() - dY) > slop || Math.abs(ev.getRawX() - dX) > slop) return true; /* kéo/vuốt: không phải chạm */
                    goDesktop(); /* chỉ còn một chức năng: chạm là về Desktop */
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: setAlpha(1f); return true;
            }
            return true;
        }
    }

    /* Ảnh nền thanh: giải mã đúng một lần rồi dùng lại; chỉ giải phóng khi dịch vụ tắt hoặc máy báo thiếu RAM */
    private Bitmap barPic; private boolean barPicTried;
    private Bitmap barPic() {
        if (barPic == null && !barPicTried) {
            barPicTried = true;
            try { barPic = BitmapFactory.decodeResource(getResources(), R.drawable.desk_bar_bg); } catch (Throwable t) { barPic = null; }
        }
        return barPic;
    }
    private void freeBarPic() { barPic = null; barPicTried = false; }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND || level >= TRIM_MEMORY_RUNNING_CRITICAL) freeBarPic();
    }

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
    /** Đo lại và chỉnh chiều cao thanh Desktop cho phủ kín thanh hệ thống (gọi sau khi app khác vừa mở / xoay màn hình). */
    private final Runnable refreshBar = new Runnable() { public void run() {
        if (btn == null || btnLp == null || wm == null || barHidden) return;
        try {
            int bh = sysBarHeight(); Point s = realSize();
            if (bh != btnLp.height || btnLp.width != s.x || btnLp.y != s.y - bh) {
                btnLp.width = s.x; btnLp.height = bh; btnLp.y = s.y - bh;
                wm.updateViewLayout(btn, btnLp); btn.invalidate();
            }
        } catch (Exception e) { }
    } };
    private void scheduleRefreshBar() {
        h.removeCallbacks(refreshBar);
        h.postDelayed(refreshBar, 150); h.postDelayed(refreshBar, 600); h.postDelayed(refreshBar, 1500);
    }

    /* ---- Tự ẩn thanh Desktop khi app ngoài chạy toàn màn hình (xem video...), không cần chạm đúp ---- */
    private final Runnable fsPoll = new Runnable() { public void run() {
        h.removeCallbacks(fsPoll);
        if (btn == null) return;
        boolean fs = videoFull || externalFullscreen();
        if (fs && !barHidden) setBarHidden(true);
        else if (!fs && barHidden) setBarHidden(false);
        h.postDelayed(fsPoll, 500);
    } };
    /** Cửa sổ app đang dùng phủ tới tận mép dưới màn hình (không còn thanh hệ thống) = đang toàn màn hình. */
    private boolean externalFullscreen() {
        if (launcherFront || policyApplied || wm == null) return false; /* policy ẩn thanh hệ thống sẵn: không đo được bằng cách này */
        try {
            Point real = realSize();
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !(w.isActive() || w.isFocused())) continue;
                Rect r = new Rect(); w.getBoundsInScreen(r);
                return r.bottom >= real.y - dp(2) && r.width() >= real.x - dp(2) && r.height() >= real.y - dp(2);
            }
        } catch (Throwable t) { }
        return false;
    }

    private void addButton() {
        if (wm == null) return;
        final DeskButton v = new DeskButton(this);
        Point s = realSize(); int bh = sysBarHeight();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(s.x, bh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = 0; lp.y = s.y - bh; /* cố định sát cạnh dưới, không di chuyển */
        try { wm.addView(v, lp); btn = v; btnLp = lp; scheduleRefreshBar(); h.removeCallbacks(fsPoll); h.postDelayed(fsPoll, 400); } catch (Exception e) { btn = null; btnLp = null; }
    }
    private void removeButton() {
        barHidden = false; h.removeCallbacks(fsPoll);
        if (btn != null) { try { wm.removeView(btn); } catch (Exception e) { } btn = null; btnLp = null; }
    }
    /** Ẩn thanh (chỉ còn dải mỏng ở đáy) hoặc hiện lại đủ chiều cao. */
    private void setBarHidden(boolean b) {
        if (barHidden == b) return;
        barHidden = b;
        if (btn == null || btnLp == null || wm == null) return;
        try {
            btnLp.height = b ? dp(2) : sysBarHeight();
            if (b) btnLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; else btnLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            btnLp.y = realSize().y - btnLp.height;
            wm.updateViewLayout(btn, btnLp);
            btn.invalidate();
        } catch (Exception e) { }
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
            L.add(pText(new java.text.SimpleDateFormat("EEEE, dd/MM/yyyy", new java.util.Locale("vi", "VN")).format(new java.util.Date())));
            L.add(pSep());
            L.add(pItem("Mở thông báo và widget…", new Runnable() { public void run() { forward("clk"); } }));
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
            ic.setColor(0xFFFFFFFF); ic.setStyle(Paint.Style.STROKE); ic.setStrokeWidth(dp(1.4f)); ic.setStrokeCap(Paint.Cap.ROUND);
            fl.setColor(0xFFFFFFFF); fl.setStyle(Paint.Style.FILL);
            setContentDescription("Thanh menu");
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
            c.drawText(clock, rx - cw, cy + dp(4.5f), tx);
            /* ngày tháng bên trái giờ, cùng dạng với thanh cũ: "T6 16 thg 10" (màn hình hẹp thì bỏ) */
            float dw = 0;
            if (w >= dp(480)) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                String[] dn = {"CN", "T2", "T3", "T4", "T5", "T6", "T7"};
                String dt = dn[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1] + " " + cal.get(java.util.Calendar.DAY_OF_MONTH) + " thg " + (cal.get(java.util.Calendar.MONTH) + 1);
                dw = tx.measureText(dt) + dp(10);
                c.drawText(dt, rx - cw - dw, cy + dp(4.5f), tx);
            }
            hit.put("clk", new RectF(rx - cw - dw - dp(8), 0, rx + dp(8), hh)); rx -= cw + dw + dp(14);
            /* trung tâm điều khiển: 2 thanh bo tròn */
            rf.set(rx - dp(15), cy - dp(5.5f), rx, cy - dp(1)); c.drawRoundRect(rf, dp(2.3f), dp(2.3f), ic);
            rf.set(rx - dp(15), cy + dp(1), rx, cy + dp(5.5f)); c.drawRoundRect(rf, dp(2.3f), dp(2.3f), ic);
            hit.put("ccb", new RectF(rx - slot + dp(8), 0, rx + dp(8), hh)); rx -= slot;
            /* tìm kiếm: kính lúp */
            c.drawCircle(rx - dp(9), cy - dp(1.5f), dp(4.8f), ic); c.drawLine(rx - dp(5.6f), cy + dp(1.9f), rx - dp(2), cy + dp(5.5f), ic);
            hit.put("srch", new RectF(rx - slot + dp(8), 0, rx + dp(8), hh)); rx -= slot;
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
        dismissPop(); h.removeCallbacks(topTick); topHidden = false;
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
        h.removeCallbacks(refreshBar); h.removeCallbacks(fsPoll);
        removeButton(); removeTopBar(); freeBarPic(); setImmersivePolicy(false);
    }
}
