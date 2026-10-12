package com.desktopmini.launcher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONObject;

/** Chuyển thông báo của các ứng dụng khác vào Trung tâm thông báo. */
public class NotifService extends NotificationListenerService {
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
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
            MainActivity.push("nativeNotif", o.toString());
        } catch (Exception e) { }
    }
}
