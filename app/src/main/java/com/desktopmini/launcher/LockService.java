package com.desktopmini.launcher;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Dịch vụ trợ năng: chỉ dùng để khóa màn hình và mở menu nguồn thật của Android. */
public class LockService extends AccessibilityService {
    public static LockService inst;
    @Override protected void onServiceConnected() { inst = this; }
    @Override public boolean onUnbind(android.content.Intent i) { inst = null; return super.onUnbind(i); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
}
