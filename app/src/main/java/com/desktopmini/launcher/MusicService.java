package com.desktopmini.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Giữ ứng dụng sống khi phát nhạc nền và hiện thông báo điều khiển. */
public class MusicService extends Service {
    static final String CH = "music";
    @Override public IBinder onBind(Intent i) { return null; }

    private PendingIntent act(String cmd, int code) {
        Intent i = new Intent("com.desktopmini.launcher.MEDIA").setPackage(getPackageName()).putExtra("cmd", cmd);
        return PendingIntent.getBroadcast(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override public int onStartCommand(Intent in, int flags, int id) {
        if (in == null || "stop".equals(in.getAction())) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "Phát nhạc", NotificationManager.IMPORTANCE_LOW));
        boolean playing = in.getBooleanExtra("playing", true);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Notification.Builder b = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(in.getStringExtra("title")).setContentText(in.getStringExtra("artist"))
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(playing).setOnlyAlertOnce(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Trước", act("prev", 1)).build())
            .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Dừng" : "Phát", act("toggle", 2)).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Sau", act("next", 3)).build())
            .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2));
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(77, n);
        return START_NOT_STICKY;
    }
}
