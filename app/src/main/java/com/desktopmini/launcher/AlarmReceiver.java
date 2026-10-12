package com.desktopmini.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;

/** Nhận tín hiệu báo thức từ AlarmManager, bật AlarmService phát nhạc; đặt lại lịch sau khi khởi động máy. */
public class AlarmReceiver extends BroadcastReceiver {
    static final String ACT = "com.desktopmini.launcher.ALARM";

    private static PendingIntent pi(Context c, int id, String uri, boolean rep, String label, int h, int m) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(ACT).setData(android.net.Uri.parse("alarm://" + id))
            .putExtra("id", id).putExtra("uri", uri).putExtra("rep", rep).putExtra("label", label).putExtra("h", h).putExtra("m", m);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, id, i, fl);
    }

    static volatile String lastError = "";

    static boolean schedule(Context c, int id, int h, int m, String uri, boolean rep, String label, boolean save) {
        try {
            Calendar t = Calendar.getInstance();
            t.set(Calendar.HOUR_OF_DAY, h); t.set(Calendar.MINUTE, m); t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0);
            if (t.getTimeInMillis() <= System.currentTimeMillis() + 1000) t.add(Calendar.DAY_OF_YEAR, 1);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent show = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(t.getTimeInMillis(), show), pi(c, id, uri, rep, label, h, m));
            if (save) {
                JSONObject o = new JSONObject().put("h", h).put("m", m).put("uri", uri).put("rep", rep).put("label", label == null ? "" : label);
                c.getSharedPreferences("alarms", 0).edit().putString(String.valueOf(id), o.toString()).apply();
            }
            lastError = "";
            return true;
        } catch (Exception e) { lastError = e.getClass().getSimpleName() + ": " + e.getMessage(); return false; }
    }

    static void cancel(Context c, int id) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(pi(c, id, "", false, "", 0, 0));
            c.getSharedPreferences("alarms", 0).edit().remove(String.valueOf(id)).apply();
        } catch (Exception e) { }
    }

    @Override public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            SharedPreferences sp = c.getSharedPreferences("alarms", 0);
            for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                try {
                    JSONObject o = new JSONObject(String.valueOf(e.getValue()));
                    schedule(c, Integer.parseInt(e.getKey()), o.getInt("h"), o.getInt("m"), o.getString("uri"), o.getBoolean("rep"), o.optString("label"), false);
                } catch (Exception x) { }
            }
            return;
        }
        if (!ACT.equals(a)) return;
        int id = i.getIntExtra("id", 0), h = i.getIntExtra("h", 0), m = i.getIntExtra("m", 0);
        String uri = i.getStringExtra("uri"), label = i.getStringExtra("label");
        boolean rep = i.getBooleanExtra("rep", false);
        if (rep) schedule(c, id, h, m, uri, true, label, false);
        else c.getSharedPreferences("alarms", 0).edit().remove(String.valueOf(id)).apply();
        Intent s = new Intent(c, AlarmService.class).putExtra("id", id).putExtra("uri", uri).putExtra("label", label).putExtra("h", h).putExtra("m", m);
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s); } catch (Exception e) { }
    }
}
