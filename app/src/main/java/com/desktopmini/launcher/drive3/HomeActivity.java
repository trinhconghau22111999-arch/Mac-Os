package com.desktopmini.launcher.drive3;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public class HomeActivity extends Activity {

    private static final int BG1 = 0xFF0E1015;
    private static final int BG2 = 0xFF1B2030;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG1);
        getWindow().setNavigationBarColor(BG2);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{BG1, BG2}));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        // Android < 9 dùng bản Legacy (chung process, đổi cookie); Android 9+ dùng process riêng từng hồ sơ
        root.addView(card("🖼", "Ảnh", 0xFF4FC3F7, 0xFF1565C0, LegacyActivities.photo()));
        root.addView(card("🎬", "Video", 0xFFFF8A80, 0xFFC62828, LegacyActivities.video()));
        root.addView(card("📁", "Tệp", 0xFF81C784, 0xFF2E7D32, LegacyActivities.file()));

        setContentView(root);
    }

    private View card(String icon, String title, int c1, int c2, final Class<?> target) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(12), dp(12), dp(12), dp(12));

        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{c1, c2});
        fill.setCornerRadius(dp(28));
        c.setBackground(ripple(fill, 0x33FFFFFF, 28));
        c.setElevation(dp(8));
        c.setClickable(true);
        c.setOnClickListener(v -> open(target, false));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        c.setLayoutParams(lp);

        TextView badge = new TextView(this);
        badge.setText(icon);
        badge.setTextSize(40);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x33FFFFFF);
        badge.setBackground(circle);
        c.addView(badge, new LinearLayout.LayoutParams(dp(84), dp(84)));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.04f);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(14);
        c.addView(t, tp);

        TextView sync = new TextView(this);
        sync.setText("⬆  Tải lên");
        sync.setTextColor(c2);
        sync.setTextSize(15);
        sync.setTypeface(Typeface.DEFAULT_BOLD);
        sync.setGravity(Gravity.CENTER);
        sync.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(26));
        sync.setBackground(ripple(pill, 0x22000000, 26));
        sync.setClickable(true);
        sync.setOnClickListener(v -> open(target, true));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(dp(14), dp(22), dp(14), 0);
        c.addView(sync, bp);
        return c;
    }

    private Drawable ripple(Drawable content, int rippleColor, int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    private void open(Class<?> target, boolean sync) {
        Intent it = new Intent(this, target);
        it.putExtra("sync", sync);
        startActivity(it);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
