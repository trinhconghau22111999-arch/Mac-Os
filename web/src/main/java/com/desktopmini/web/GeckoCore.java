package com.desktopmini.web;

import android.content.Context;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;

/** Nhân Gecko dùng chung cho Trình duyệt và Ổ mềm (tạo một lần cho cả tiến trình app phụ). */
final class GeckoCore {
    private GeckoCore() { }

    static final String HOME = "https://www.google.com/";
    private static final String SEARCH = "https://www.google.com/search?q=";
    private static GeckoRuntime rt;
    private static float zoom = 1f;

    static synchronized GeckoRuntime rt(Context c) {
        if (rt == null) {
            Context app = c.getApplicationContext();
            rt = GeckoRuntime.create(app);
            setPref("apz.overscroll.enabled", false);   /* kéo tới đầu/cuối trang là dừng hẳn, không "võng" lộ hình nền */
            setPref("dom.ipc.processCount", 1);          /* giảm RAM cho máy 32-bit */
            setPref("dom.ipc.processPrelaunch.enabled", false);
            setPref("browser.cache.memory.capacity", 16384);
            setPref("image.mem.max_decoded_image_kb", 40960);
            /* cho phép 2 ngón zoom cả trang cấm zoom */
            try { GeckoRuntimeSettings.class.getMethod("setForceUserScalableEnabled", boolean.class).invoke(rt.getSettings(), true); } catch (Throwable t) { }
            zoom = app.getSharedPreferences("web_zoom", Context.MODE_PRIVATE).getFloat("gecko", 1f);
            if (Math.abs(zoom - 1f) > 0.01f) applyZoom();
        }
        return rt;
    }

    /** Đặt cờ Gecko bằng reflection (khác bản Gecko thì chữ ký khác nhau; sai thì bỏ qua, không làm vỡ build). */
    private static void setPref(String name, Object val) {
        try {
            Class<?> c = Class.forName("org.mozilla.geckoview.GeckoPreferenceController");
            Class<?> want = val instanceof Boolean ? boolean.class : int.class;
            for (java.lang.reflect.Method m : c.getMethods()) {
                if (!"setGeckoPref".equals(m.getName()) || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] t = m.getParameterTypes();
                if (t.length == 3 && t[0] == String.class && t[1] == want && t[2] == int.class) {
                    for (int br = 0; br <= 1; br++) { try { m.invoke(null, name, val, br); } catch (Throwable x) { } }
                } else if (t.length == 2 && t[0] == String.class && t[1] == want) {
                    try { m.invoke(null, name, val); } catch (Throwable x) { }
                }
            }
        } catch (Throwable t) { }
    }

    private static void applyZoom() {
        try {
            rt.getSettings().setAutomaticFontSizeAdjustment(false);
            rt.getSettings().setFontSizeFactor(zoom);
        } catch (Throwable t) { }
    }

    static void zoomStep(Context c, float f) {
        zoom = Math.max(0.5f, Math.min(3f, zoom * f));
        applyZoom();
        c.getApplicationContext().getSharedPreferences("web_zoom", Context.MODE_PRIVATE).edit().putFloat("gecko", zoom).apply();
    }

    /** Ctrl + lăn chuột / Ctrl + - 0: đổi cỡ chữ cả trang (lưu lại). 2 ngón dùng zoom gốc của Gecko. */
    static void attachZoomKeys(final Context c, View v) {
        v.setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override public boolean onGenericMotion(View view, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_SCROLL || (e.getMetaState() & KeyEvent.META_CTRL_ON) == 0) return false;
                float dy = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (dy == 0) return false;
                zoomStep(c, (float) Math.pow(1.1, dy)); return true;
            }
        });
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int code, KeyEvent e) {
                if (!e.isCtrlPressed()) return false;
                boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                if (code == KeyEvent.KEYCODE_PLUS || code == KeyEvent.KEYCODE_EQUALS || code == KeyEvent.KEYCODE_NUMPAD_ADD) { if (down) zoomStep(c, 1.1f); return true; }
                if (code == KeyEvent.KEYCODE_MINUS || code == KeyEvent.KEYCODE_NUMPAD_SUBTRACT) { if (down) zoomStep(c, 1f / 1.1f); return true; }
                if (code == KeyEvent.KEYCODE_0 || code == KeyEvent.KEYCODE_NUMPAD_0) { if (down) zoomStep(c, 1f / zoom); return true; }
                return false;
            }
        });
    }

    /** Gõ chữ thường thì tìm Google, có dấu chấm không dấu cách thì coi là địa chỉ. */
    static String toUrl(String text) {
        if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("file://") || text.startsWith("about:")) return text;
        if (!text.contains(" ") && text.contains(".")) return "https://" + text;
        return SEARCH + Uri.encode(text);
    }

    /** Địa chỉ có nên mở lại khi vào lại app không: bỏ link chuyển hướng của Google (dùng được đúng một lần) và địa chỉ quá dài. */
    static boolean restorable(String u) {
        try {
            if (u == null || u.length() > 2000) return false;
            Uri x = Uri.parse(u);
            String h = x.getHost() == null ? "" : x.getHost().toLowerCase(java.util.Locale.ROOT);
            String path = x.getPath() == null ? "" : x.getPath();
            if ((h.equals("google.com") || h.endsWith(".google.com")) && (path.equals("/url") || path.equals("/aclk") || path.startsWith("/imgres"))) return false;
            return true;
        } catch (Throwable t) { return false; }
    }

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
}
