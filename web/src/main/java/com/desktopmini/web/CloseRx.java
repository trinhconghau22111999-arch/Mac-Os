package com.desktopmini.web;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Nhận lệnh "Đóng cửa sổ" từ Dock nổi của Desktop Mini để tắt đúng màn hình (Trình duyệt hoặc Ổ mềm) mà không tắt cả app phụ. */
final class CloseRx extends BroadcastReceiver {
    static final String ACTION = "com.desktopmini.web.CLOSE";
    private final Activity a;
    private CloseRx(Activity a) { this.a = a; }
    static CloseRx reg(Activity a) {
        CloseRx r = new CloseRx(a);
        try { a.registerReceiver(r, new IntentFilter(ACTION)); } catch (Throwable t) { return null; }
        return r;
    }
    static void unreg(Activity a, CloseRx r) { if (r != null) { try { a.unregisterReceiver(r); } catch (Throwable t) { } } }
    @Override public void onReceive(Context c, Intent i) {
        String cls = i == null ? null : i.getStringExtra("cls");
        if (cls != null && cls.equals(a.getClass().getSimpleName())) a.finish();
    }
}
