package com.desktopmini.launcher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;

/** Chuyển thông báo của các ứng dụng khác vào Trung tâm thông báo. */
public class NotifService extends NotificationListenerService {
    /* Thông báo gần đây, để menu gốc "Thông báo" trên thanh menu nổi hiện được mà không cần mở Desktop Mini */
    private static final java.util.ArrayList<String[]> RECENT = new java.util.ArrayList<String[]>();
    static synchronized void addRecent(String app, String pkg, String t, String b) {
        for (int i = RECENT.size() - 1; i >= 0; i--) { String[] r = RECENT.get(i); if (r[1].equals(pkg) && r[2].equals(t) && r[3].equals(b)) RECENT.remove(i); }
        RECENT.add(0, new String[]{app, pkg, t, b});
        while (RECENT.size() > 20) RECENT.remove(RECENT.size() - 1);
    }
    public static synchronized java.util.List<String[]> recent() { return new java.util.ArrayList<String[]>(RECENT); }
    public static synchronized void clearRecent() { RECENT.clear(); }

    @Override public void onListenerConnected() {
        try {
            StatusBarNotification[] a = getActiveNotifications();
            if (a != null) for (StatusBarNotification s : a) record(s, false);
        } catch (Exception e) { }
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { record(sbn, true); }

    private void record(StatusBarNotification sbn, boolean push) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg.equals(getPackageName())) return;
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE), b = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (t == null && b == null) return;
            JSONObject o = new JSONObject();
            o.put("app", getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString());
            o.put("pkg", pkg); o.put("title", t == null ? "" : t.toString()); o.put("body", b == null ? "" : b.toString());
            o.put("icon", MainActivity.iconOf(this, pkg));
            addRecent(o.getString("app"), pkg, o.getString("title"), o.getString("body"));
            if (push) MainActivity.push("nativeNotif", o.toString());
        } catch (Exception e) { }
    }
}
