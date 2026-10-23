package com.desktopmini.web;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/** Nút biểu tượng tự vẽ (không dùng ký tự đặc biệt vì font máy cũ có thể thiếu). */
final class Ic extends View {
    static final int MENU = 0, BACK = 1, FWD = 2, RELOAD = 3, STAR = 4, DOWN = 5, PLUS = 6, CLOSE = 7, UP = 8;
    private final int kind;
    private boolean on;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Ic(Context c, int kind, OnClickListener l) {
        super(c);
        this.kind = kind;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(GeckoCore.dp(c, 2));
        setOnClickListener(l);
        setClickable(true);
        setLayoutParams(new LinearLayout.LayoutParams(GeckoCore.dp(c, 42), GeckoCore.dp(c, 44)));
        setBackgroundResource(android.R.drawable.list_selector_background);
    }

    /** Sáng lên (vd. ngôi sao khi đã đánh dấu trang). */
    void setOn(boolean v) { if (on != v) { on = v; invalidate(); } }

    @Override public void setEnabled(boolean e) { super.setEnabled(e); setAlpha(e ? 1f : 0.3f); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, u = Math.min(w, h) / 44f;
        p.setColor(tint);
        p.setStyle(Paint.Style.STROKE);
        switch (kind) {
            case MENU:
                p.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * 7 * u, 2.2f * u, p);
                break;
            case BACK:
                path.reset(); path.moveTo(cx + 4 * u, cy - 8 * u); path.lineTo(cx - 4 * u, cy); path.lineTo(cx + 4 * u, cy + 8 * u); c.drawPath(path, p);
                break;
            case FWD:
                path.reset(); path.moveTo(cx - 4 * u, cy - 8 * u); path.lineTo(cx + 4 * u, cy); path.lineTo(cx - 4 * u, cy + 8 * u); c.drawPath(path, p);
                break;
            case RELOAD: {
                RectF r = new RectF(cx - 8 * u, cy - 8 * u, cx + 8 * u, cy + 8 * u);
                c.drawArc(r, -60, 300, false, p);
                p.setStyle(Paint.Style.FILL);
                path.reset(); path.moveTo(cx + 10 * u, cy - 9 * u); path.lineTo(cx + 10 * u, cy - 1 * u); path.lineTo(cx + 2 * u, cy - 4 * u); path.close(); c.drawPath(path, p);
                break;
            }
            case STAR: {
                path.reset();
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float rr = (i % 2 == 0 ? 10f : 4.4f) * u;
                    float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                if (on) { p.setColor(0xFFFFB300); p.setStyle(Paint.Style.FILL_AND_STROKE); }
                c.drawPath(path, p);
                break;
            }
            case DOWN:
                c.drawLine(cx, cy - 9 * u, cx, cy + 4 * u, p);
                path.reset(); path.moveTo(cx - 6 * u, cy - 2 * u); path.lineTo(cx, cy + 5 * u); path.lineTo(cx + 6 * u, cy - 2 * u); c.drawPath(path, p);
                c.drawLine(cx - 8 * u, cy + 10 * u, cx + 8 * u, cy + 10 * u, p);
                break;
            case UP:
                c.drawLine(cx, cy + 9 * u, cx, cy - 4 * u, p);
                path.reset(); path.moveTo(cx - 6 * u, cy + 2 * u); path.lineTo(cx, cy - 5 * u); path.lineTo(cx + 6 * u, cy + 2 * u); c.drawPath(path, p);
                c.drawLine(cx - 8 * u, cy + 10 * u, cx + 8 * u, cy + 10 * u, p);
                break;
            case PLUS:
                c.drawLine(cx - 8 * u, cy, cx + 8 * u, cy, p); c.drawLine(cx, cy - 8 * u, cx, cy + 8 * u, p);
                break;
            case CLOSE:
                c.drawLine(cx - 7 * u, cy - 7 * u, cx + 7 * u, cy + 7 * u, p); c.drawLine(cx + 7 * u, cy - 7 * u, cx - 7 * u, cy + 7 * u, p);
                break;
            default: break;
        }
    }

    int tint = 0xFF3A3A3C;
    static Ic mkW(Context c, int kind, OnClickListener l) { Ic i = make(c, kind, l); i.tint = 0xFFFFFFFF; return i; }
    static Ic make(Context c, int kind, OnClickListener l) { Ic i = new Ic(c, kind, l); i.setLayoutParams(new LinearLayout.LayoutParams(GeckoCore.dp(c, 42), GeckoCore.dp(c, 44))); return i; }
}
