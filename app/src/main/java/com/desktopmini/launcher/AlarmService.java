package com.desktopmini.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;

/** Phát nhạc báo thức (lặp lại tối đa 1 phút) kèm thông báo có nút Tắt. */
public class AlarmService extends Service {
    private MediaPlayer mp;
    private final Handler hd = new Handler();
    private final Runnable auto = new Runnable() { public void run() { halt(); } };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && "stop".equals(i.getAction())) { halt(); return START_NOT_STICKY; }
        String label = i == null ? "" : i.getStringExtra("label");
        int h = i == null ? 0 : i.getIntExtra("h", 0), m = i == null ? 0 : i.getIntExtra("m", 0);
        String uri = i == null ? null : i.getStringExtra("uri");
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel("alarm", "Báo thức", NotificationManager.IMPORTANCE_HIGH));
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), fl);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, AlarmService.class).setAction("stop"), fl);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "alarm") : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Báo thức " + String.format("%02d:%02d", h, m))
          .setContentText(label == null || label.length() == 0 ? "Đến giờ rồi" : label).setContentIntent(open).setFullScreenIntent(open, true)
          .setCategory(Notification.CATEGORY_ALARM).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt", stop);
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(77, nb.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(77, nb.build());
        } catch (Exception e) { }
        try {
            if (mp != null) mp.release();
            mp = new MediaPlayer();
            Uri u = (uri == null || uri.length() == 0) ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) : Uri.parse(uri);
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            mp.setDataSource(this, u); mp.setLooping(true); mp.prepare(); mp.start();
        } catch (Exception e) {
            try { if (mp != null) mp.release(); mp = null; } catch (Exception x) { }
        }
        MainActivity.push("nativeAlarm", "{\"h\":" + h + ",\"m\":" + m + "}");
        hd.removeCallbacks(auto); hd.postDelayed(auto, 60000);
        return START_NOT_STICKY;
    }

    private void halt() {
        hd.removeCallbacks(auto);
        try { if (mp != null) { mp.stop(); mp.release(); } } catch (Exception e) { }
        mp = null;
        stopForeground(true); stopSelf();
    }

    @Override public void onDestroy() { try { if (mp != null) mp.release(); } catch (Exception e) { } super.onDestroy(); }
}
