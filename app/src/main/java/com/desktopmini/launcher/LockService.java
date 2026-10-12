package com.desktopmini.launcher;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Dịch vụ trợ năng. Dùng để:
 *  - khóa màn hình, mở menu nguồn thật của Android;
 *  - chia đôi màn hình (Android 7+): ứng dụng khác ở nửa trên, Desktop Mini ở nửa dưới;
 *  - biết ứng dụng nào đang hiện trên cửa sổ (chỉ đọc tên gói, không đọc nội dung) để hiện icon ở Dock.
 */
public class LockService extends AccessibilityService {
    public static LockService inst;
    /** Thời điểm (ms) trước đó đang chạy chuỗi chia đôi/thu xuống: không tự thoát chia đôi. */
    static volatile long busyUntil = 0;
    static volatile boolean divider = false;
    private final Handler h = new Handler(Looper.getMainLooper());
    private String lastSig = "";
    private int emptyChecks = 0;

    private final Runnable scan = new Runnable() { @Override public void run() { scanWindows(); } };

    @Override protected void onServiceConnected() { inst = this; h.postDelayed(scan, 300); }
    @Override public boolean onUnbind(android.content.Intent i) { inst = null; divider = false; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        h.removeCallbacks(scan); h.postDelayed(scan, 180);
    }
    @Override public void onInterrupt() { }

    /** Bấm nút Home của Android (khi đang chia đôi, màn hình chính hiện ở nửa còn lại). */
    boolean home() { return performGlobalAction(GLOBAL_ACTION_HOME); }
    /** Bật chia đôi (đưa ứng dụng đang ở trên cùng lên nửa trên) hoặc thoát chia đôi (ứng dụng trên rơi xuống nền đa nhiệm). */
    boolean toggleSplit() {
        if (Build.VERSION.SDK_INT < 24) return false;
        return performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }

    private void scanWindows() {
        if (Build.VERSION.SDK_INT < 24) return;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return;
            boolean div = false; int others = 0, minTop = Integer.MAX_VALUE; String top = "";
            JSONArray pk = new JSONArray(); Rect r = new Rect();
            for (AccessibilityWindowInfo w : ws) {
                int t = w.getType();
                if (t == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) { div = true; continue; }
                if (t != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                String p = "";
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) { CharSequence cs = root.getPackageName(); p = cs == null ? "" : cs.toString(); root.recycle(); }
                if (p.equals(getPackageName()) || p.equals("com.android.systemui")) continue;
                others++;
                w.getBoundsInScreen(r);
                if (p.length() > 0) {
                    pk.put(p);
                    int key = r.top * 4000 + r.left;
                    if (key < minTop) { minTop = key; top = p; }
                }
            }
            divider = div;
            JSONObject o = new JSONObject();
            o.put("top", top); o.put("split", div); o.put("pkgs", pk);
            String sig = o.toString();
            if (!sig.equals(lastSig)) { lastSig = sig; MainActivity.push("nativeWins", sig); }
            /* Đang chia đôi mà nửa trên không còn ứng dụng nào (đã đóng): tự thoát chia đôi để trả lại màn hình đầy đủ */
            if (div && others == 0 && System.currentTimeMillis() > busyUntil) {
                if (++emptyChecks >= 2) { emptyChecks = 0; busyUntil = System.currentTimeMillis() + 2500; toggleSplit(); }
                else { h.removeCallbacks(scan); h.postDelayed(scan, 900); }
            } else emptyChecks = 0;
        } catch (Throwable e) { }
    }
}
