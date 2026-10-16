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

    @Override protected void onServiceConnected() {
        inst = this; wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        ScreenTime.start(this); /* đo thời gian sáng màn hình ngay cả khi Desktop Mini không hiện */
        barLock = isBarLock(this);
        if (!isEnabled(this)) setImmersivePolicy(false);
        apply();
    }
    @Override public boolean onUnbind(Intent i) { inst = null; tearDown(); return super.onUnbind(i); }
    @Override public void onDestroy() { inst = null; tearDown(); super.onDestroy(); }
    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c); removeButton(); updateButton(); scheduleRefreshBar();
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
        updateButton();
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
    private int sysBarHeight() {
        int best = navBarHeight(), cap = dp(150);
        Point real = realSize();
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
        return best;
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

    private void tearDown() {
        h.removeCallbacks(refreshBar); h.removeCallbacks(fsPoll);
        removeButton(); freeBarPic(); setImmersivePolicy(false);
    }
}
