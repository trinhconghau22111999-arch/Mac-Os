package com.desktopmini.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
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
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.HashSet;
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

    /** Giữ lại để MainActivity gọi (không còn dùng dữ liệu Dock). */
    public static void saveData(Context c, String json) { }
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
        super.onConfigurationChanged(c); removeButton(); updateButton();
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

    private class DeskButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            ic = new Paint(Paint.ANTI_ALIAS_FLAG), tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private float sx, sy; private int ox, oy; private boolean moved;
        DeskButton(Context c) {
            super(c);
            bg.setColor(0xE024262E); bg.setStyle(Paint.Style.FILL);
            ring.setColor(0x59FFFFFF); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(dp(1));
            ic.setColor(0xFFFFFFFF); ic.setStyle(Paint.Style.STROKE); ic.setStrokeWidth(dp(1.8f));
            ic.setStrokeCap(Paint.Cap.ROUND); ic.setStrokeJoin(Paint.Join.ROUND);
            tx.setColor(0xFFFFFFFF); tx.setTextAlign(Paint.Align.CENTER); tx.setTextSize(dp(8.5f)); tx.setFakeBoldText(true);
            setAlpha(0.88f);
            setContentDescription("Desktop");
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hh = getHeight(), cx = w / 2, cy = hh / 2, r = Math.min(w, hh) / 2 - dp(1);
            c.drawCircle(cx, cy, r, bg); c.drawCircle(cx, cy, r, ring);
            rf.set(cx - dp(12), cy - dp(19), cx + dp(12), cy - dp(6));
            c.drawRoundRect(rf, dp(3), dp(3), ic);
            c.drawLine(cx, cy - dp(6), cx, cy - dp(3), ic);
            c.drawLine(cx - dp(5), cy - dp(3), cx + dp(5), cy - dp(3), ic);
            c.drawText("Desktop", cx, cy + dp(13), tx);
        }
        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (btnLp == null) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ev.getRawX(); sy = ev.getRawY(); ox = btnLp.x; oy = btnLp.y; moved = false; setAlpha(1f); return true;
                case MotionEvent.ACTION_MOVE: {
                    float ddx = ev.getRawX() - sx, ddy = ev.getRawY() - sy;
                    if (!moved && Math.hypot(ddx, ddy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
                    if (moved) {
                        Point s = realSize(); int sz = btnLp.width;
                        btnLp.x = Math.max(0, Math.min(s.x - sz, Math.round(ox + ddx)));
                        btnLp.y = Math.max(0, Math.min(s.y - sz, Math.round(oy + ddy)));
                        try { wm.updateViewLayout(this, btnLp); } catch (Exception e) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    setAlpha(0.88f);
                    if (moved) savePos(); else goDesktop();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setAlpha(0.88f); if (moved) savePos(); return true;
            }
            return true;
        }
    }

    private void addButton() {
        if (wm == null) return;
        final DeskButton v = new DeskButton(this);
        int sz = dp(56);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(sz, sz,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        Point s = realSize();
        SharedPreferences sp = getSharedPreferences(PK, 0);
        float fx = sp.getFloat("fx", 1f), fy = sp.getFloat("fy", 0.62f); /* mặc định: sát mép phải, hơi thấp hơn giữa */
        lp.x = Math.round(Math.max(0f, Math.min(1f, fx)) * Math.max(0, s.x - sz));
        lp.y = Math.round(Math.max(0f, Math.min(1f, fy)) * Math.max(0, s.y - sz));
        try { wm.addView(v, lp); btn = v; btnLp = lp; } catch (Exception e) { btn = null; btnLp = null; }
    }
    private void removeButton() {
        if (btn != null) { try { wm.removeView(btn); } catch (Exception e) { } btn = null; btnLp = null; }
    }
    private void savePos() {
        if (btnLp == null) return;
        Point s = realSize(); int sz = btnLp.width;
        float fx = s.x - sz > 0 ? btnLp.x / (float) (s.x - sz) : 1f, fy = s.y - sz > 0 ? btnLp.y / (float) (s.y - sz) : 0.62f;
        getSharedPreferences(PK, 0).edit().putFloat("fx", fx).putFloat("fy", fy).apply();
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
        removeButton(); setImmersivePolicy(false);
    }
}
