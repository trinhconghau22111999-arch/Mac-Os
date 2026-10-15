package com.desktopmini.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.PowerManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Đo thời gian màn hình SÁNG (đang sử dụng máy) trong ngày, tính cho cả lúc dùng app khác, không chỉ lúc Desktop Mini đang hiện.
 * Nghe sự kiện bật/tắt màn hình, cộng dồn vào SharedPreferences "ontime", sang ngày mới thì tính lại từ 0.
 */
final class ScreenTime {
    private static boolean registered = false;

    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences("ontime", 0); }
    private static String today() { return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()); }

    /** Sang ngày mới: về 0; màn hình đang sáng xuyên nửa đêm thì tính tiếp từ lúc này. */
    private static void roll(SharedPreferences p) {
        String d = today();
        if (d.equals(p.getString("day", ""))) return;
        long since = p.getLong("since", 0);
        p.edit().putString("day", d).putLong("ms", 0).putLong("since", since > 0 ? System.currentTimeMillis() : 0).apply();
    }

    static synchronized void screen(Context c, boolean on) {
        SharedPreferences p = sp(c); roll(p);
        long now = System.currentTimeMillis(), since = p.getLong("since", 0);
        if (on) { if (since == 0) p.edit().putLong("since", now).apply(); }
        else if (since > 0) p.edit().putLong("ms", p.getLong("ms", 0) + Math.max(0, now - since)).putLong("since", 0).apply();
    }

    /** Gọi nhiều lần cũng chỉ đăng ký một lần. */
    static synchronized void start(Context c) {
        if (registered) return;
        final Context a = c.getApplicationContext();
        registered = true;
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        a.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) { screen(a, Intent.ACTION_SCREEN_ON.equals(i.getAction())); }
        }, f);
        try {
            boolean on = ((PowerManager) a.getSystemService(Context.POWER_SERVICE)).isInteractive();
            SharedPreferences p = sp(a); roll(p);
            if (on) screen(a, true);
            else if (p.getLong("since", 0) > 0) p.edit().putLong("since", 0).apply(); /* lỡ sự kiện tắt lúc tiến trình không chạy: bỏ phần đang dở */
        } catch (Exception e) { }
    }

    /** Tổng mili giây màn hình đã sáng trong hôm nay. */
    static synchronized long todayMs(Context c) {
        SharedPreferences p = sp(c); roll(p);
        long ms = p.getLong("ms", 0), since = p.getLong("since", 0);
        if (since > 0) ms += Math.max(0, System.currentTimeMillis() - since);
        return ms;
    }
}
