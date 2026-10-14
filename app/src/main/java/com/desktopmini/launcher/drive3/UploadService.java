package com.desktopmini.launcher.drive3;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Dịch vụ nền (foreground) chạy suốt lúc đang tải lên. Việc tải thật vẫn do trang Drive
 * trong WebView làm; dịch vụ này chỉ giữ cho process của hồ sơ không bị hệ thống dọn,
 * giữ CPU + Wi-Fi thức khi tắt màn hình, và hiện thông báo tiến độ.
 * Mỗi hồ sơ có 1 lớp con riêng vì phải chạy cùng process với Activity của hồ sơ đó.
 */
public class UploadService extends Service {

    public static class P1 extends UploadService { }
    public static class P2 extends UploadService { }
    public static class P3 extends UploadService { }
    /** Dùng cho hồ sơ Legacy (Android < 9) chạy trong process chính. */
    public static class L extends UploadService { }

    static final String CHANNEL = "drive3_upload";
    private static final long MAX_HOLD_MS = 6L * 60 * 60 * 1000; // tối đa 6 giờ

    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        int id = it != null ? it.getIntExtra("id", 1) : 1;
        String cls = it != null ? it.getStringExtra("cls") : null;
        Notification n = build(this, cls, -1, "Đang chuẩn bị tải lên…", true);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(id, n);
        }
        acquireLocks();
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drive3:upload");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire(MAX_HOLD_MS);
            if (wifi == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                int mode = Build.VERSION.SDK_INT >= 29
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifi = wm.createWifiLock(mode, "drive3:upload");
                wifi.setReferenceCounted(false);
            }
            if (!wifi.isHeld()) wifi.acquire();
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    /** Tạo thông báo tiến độ. pct < 0 => thanh chạy không xác định. */
    static Notification build(Context c, String activityCls, int pct, String text, boolean ongoing) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Tải lên Drive",
                        NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c);
        }
        b.setContentTitle("Drive 3 Hồ Sơ")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_upload
                        : android.R.drawable.stat_sys_upload_done);
        if (ongoing) b.setProgress(100, Math.max(pct, 0), pct < 0);
        if (activityCls != null) {
            Intent open = new Intent();
            open.setClassName(c, activityCls);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            b.setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE));
        }
        return b.build();
    }
}
