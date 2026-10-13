package com.desktopmini.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * Phóng to / thu nhỏ trang web nhúng trong cửa sổ:
 *  - 2 ngón chụm / giãn, nhấn đúp (zoom có sẵn của WebView, đã mở khoá cả trang cấm zoom)
 *  - Ctrl + lăn chuột, Ctrl + (+ / - / 0)
 * và NHỚ mức zoom theo từng ứng dụng (key), tắt app mở lại vẫn áp dụng.
 *
 * Mức zoom lưu dưới dạng tỉ lệ so với cỡ mặc định của trang (1 = mặc định) nên vẫn đúng
 * khi đổi cỡ cửa sổ hoặc mở trang khác.
 */
public final class ZoomHelper {
    private static final float MIN = 0.3f, MAX = 6f, STEP = 1.1f;
    private static final String PREFS = "dm_zoom";

    /** Gỡ cấm zoom (user-scalable=no, maximum-scale) của trang để chụm 2 ngón luôn được. */
    static final String VIEWPORT_JS =
        "(function(){if(window.__dmz)return;window.__dmz=1;" +
        "function fx(){try{var ms=document.querySelectorAll('meta[name=viewport]');" +
        "for(var i=0;i<ms.length;i++){var m=ms[i],c=m.getAttribute('content')||'';" +
        "if(/user-scalable\\s*=\\s*(no|0)/i.test(c)||/maximum-scale\\s*=\\s*[0-9.]+/i.test(c)){" +
        "c=c.replace(/(^|[,;\\s])(user-scalable|maximum-scale|minimum-scale)\\s*=\\s*[^,;\\s]+/ig,'$1')" +
        ".replace(/[,;\\s]+$/,'').replace(/^[,;\\s]+/,'');" +
        "m.setAttribute('content',(c?c+',':'')+'user-scalable=yes,maximum-scale=10')}}}catch(e){}}" +
        "fx();document.addEventListener('DOMContentLoaded',fx);window.addEventListener('load',fx);" +
        "setTimeout(fx,800);setTimeout(fx,2500)})();";

    private final WebView v;
    private final SharedPreferences sp;
    private final String pk;
    private final Handler h = new Handler(Looper.getMainLooper());

    private float saved;        // mức zoom người dùng chọn so với mặc định (1 = mặc định)
    private float base = 0;     // scale mặc định của trang hiện tại (theo bố cục hiện tại)
    private boolean fresh = true;   // true từ lúc bắt đầu tải trang đến khi đã áp mức zoom đã lưu
    private long userAt = 0;        // lần cuối người dùng thao tác zoom
    private long applyUntil = 0;    // đang tự áp zoom: bỏ qua các sự kiện scale do chính mình gây ra

    private final Runnable applyRun = new Runnable() { @Override public void run() { apply(); } };
    private final Runnable saveRun = new Runnable() {
        @Override public void run() { sp.edit().putFloat(pk, saved).apply(); }
    };

    public static ZoomHelper attach(Context ctx, WebView v, String key) {
        return new ZoomHelper(ctx, v, key);
    }

    private ZoomHelper(Context ctx, final WebView v, String key) {
        this.v = v;
        this.sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.pk = "z_" + key;
        this.saved = clamp(sp.getFloat(pk, 1f));

        WebSettings s = v.getSettings();
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT))
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(v, VIEWPORT_JS, java.util.Collections.singleton("*"));
        } catch (Throwable e) { }

        /* 2 ngón / nhấn đúp: chỉ đánh dấu "người dùng đang zoom", không chặn sự kiện của WebView */
        final GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { markUser(); return false; }
        });
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (e.getPointerCount() >= 2) markUser();
                gd.onTouchEvent(e);
                return false;
            }
        });

        /* Ctrl + lăn chuột */
        v.setOnGenericMotionListener(new View.OnGenericMotionListener() {
            @Override public boolean onGenericMotion(View view, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_SCROLL || (e.getMetaState() & KeyEvent.META_CTRL_ON) == 0) return false;
                float dy = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (dy == 0) return false;
                step((float) Math.pow(STEP, dy));   // lăn lên = phóng to
                return true;
            }
        });

        /* Ctrl + / Ctrl - / Ctrl 0 */
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int code, KeyEvent e) {
                if (!e.isCtrlPressed()) return false;
                boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                switch (code) {
                    case KeyEvent.KEYCODE_PLUS: case KeyEvent.KEYCODE_EQUALS: case KeyEvent.KEYCODE_NUMPAD_ADD:
                        if (down) step(STEP); return true;
                    case KeyEvent.KEYCODE_MINUS: case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                        if (down) step(1f / STEP); return true;
                    case KeyEvent.KEYCODE_0: case KeyEvent.KEYCODE_NUMPAD_0:
                        if (down) reset(); return true;
                    default: return false;
                }
            }
        });
    }

    private static float clamp(float f) { return f < MIN ? MIN : (f > MAX ? MAX : f); }

    @SuppressWarnings("deprecation")
    private float scaleNow() { try { return v.getScale(); } catch (Throwable e) { return 0f; } }

    private void markUser() { userAt = SystemClock.uptimeMillis(); }

    private void persistSoon() { h.removeCallbacks(saveRun); h.postDelayed(saveRun, 400); }

    private void schedApply(long delay) { h.removeCallbacks(applyRun); h.postDelayed(applyRun, delay); }

    /** Bước zoom bằng chuột / bàn phím. */
    private void step(float factor) {
        markUser();
        float s = scaleNow();
        if (base <= 0 && s > 0) base = s;
        if (base > 0 && s > 0) {
            float r = (s / base) * factor;
            if (r < MIN || r > MAX) return;
        }
        try { v.zoomBy(factor); } catch (Throwable e) { }
    }

    /** Ctrl+0: về cỡ mặc định. */
    private void reset() {
        markUser();
        float s = scaleNow();
        if (base > 0 && s > 0) { try { v.zoomBy(base / s); } catch (Throwable e) { } }
        saved = 1f;
        persistSoon();
    }

    /** WebViewClient.onScaleChanged */
    public void onScaleChanged(float oldS, float newS) {
        if (newS <= 0) return;
        long now = SystemClock.uptimeMillis();
        if (now < applyUntil) return;                       // do chính mình áp zoom
        if (now - userAt < 1500) {                          // người dùng zoom
            if (base <= 0) base = oldS > 0 ? oldS : newS;
            saved = clamp(newS / base);
            persistSoon();
            return;
        }
        /* thay đổi do bố cục (tải trang, đổi cỡ cửa sổ) */
        if (fresh) { base = newS; schedApply(350); }
        else if (base > 0 && oldS > 0) { base = base * newS / oldS; }   // giữ nguyên tỉ lệ đã zoom
    }

    /** WebViewClient.onPageStarted */
    public void onPageStarted() {
        fresh = true; base = 0; userAt = 0; applyUntil = 0;
        h.removeCallbacks(applyRun);
    }

    /** WebViewClient.onPageFinished */
    public void onPageFinished(WebView view) {
        try { view.evaluateJavascript(VIEWPORT_JS, null); } catch (Throwable e) { }
        schedApply(450);
    }

    /** Cửa sổ vừa được hiện lại / đổi cỡ: áp lại mức zoom nếu lệch. */
    public void onShown() { schedApply(500); }

    private void apply() {
        float s = scaleNow();
        if (s <= 0) return;
        if (base <= 0) base = s;
        fresh = false;
        float r = s / base;
        if (Math.abs(r - saved) / saved > 0.03f) {
            applyUntil = SystemClock.uptimeMillis() + 1000;
            try { v.zoomBy(clamp(saved) / r); } catch (Throwable e) { }
        }
    }
}
