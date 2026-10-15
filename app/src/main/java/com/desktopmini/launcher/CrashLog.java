package com.desktopmini.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Ghi lỗi của app ra tệp văn bản để gửi đi chẩn đoán (đặc biệt khi trình duyệt không mở được).
 * Ghi 2 nơi: Download/DesktopMini-loi.txt (nếu có quyền) và thư mục riêng của app (Android/data/com.desktopmini.launcher/files/loi.txt).
 */
public final class CrashLog {
    private CrashLog() { }

    static final String NAME = "DesktopMini-loi.txt";
    private static final long MAX = 200 * 1024;
    private static int jsCount = 0;

    static File appFile(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        return new File(d, "loi.txt");
    }

    static File downloadFile() {
        try { return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), NAME); }
        catch (Exception e) { return null; }
    }

    static String where(Context c) {
        File d = downloadFile();
        return d != null ? "Download/" + NAME : appFile(c).getAbsolutePath();
    }

    private static String webViewInfo(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo p = android.webkit.WebView.getCurrentWebViewPackage();
                if (p != null) return p.packageName + " " + p.versionName;
            }
        } catch (Throwable t) { }
        String[] pk = {"com.google.android.webview", "com.android.webview", "com.android.chrome"};
        for (String s : pk) {
            try { return s + " " + c.getPackageManager().getPackageInfo(s, 0).versionName; } catch (Throwable t) { }
        }
        return "không rõ";
    }

    static String header(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            String ver = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            b.append("Phiên bản app: ").append(ver).append('\n');
        } catch (Throwable t) { }
        b.append("Máy: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" | Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("CPU: ").append(java.util.Arrays.toString(Build.SUPPORTED_ABIS)).append(" | tiến trình 64-bit: ");
        try { b.append(Build.VERSION.SDK_INT >= 23 ? String.valueOf(android.os.Process.is64Bit()) : "không rõ"); } catch (Throwable t) { b.append("không rõ"); }
        b.append('\n');
        b.append("WebView: ").append(webViewInfo(c)).append('\n');
        return b.toString();
    }

    private static void append(File f, String text) {
        if (f == null) return;
        try {
            File p = f.getParentFile(); if (p != null && !p.exists()) p.mkdirs();
            if (f.exists() && f.length() > MAX) f.delete();
            FileOutputStream o = new FileOutputStream(f, true);
            o.write(text.getBytes("UTF-8")); o.close();
        } catch (Throwable t) { }
    }

    private static void write(Context c, String text) {
        append(appFile(c), text);
        append(downloadFile(), text);
    }

    static String stack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /** Ghi một lỗi đã bắt được (không làm app dừng). */
    public static synchronized void log(Context c, String where, Throwable t) {
        try {
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            write(c, "==== " + when + " | " + where + " ====\n" + header(c) + stack(t) + "\n");
        } catch (Throwable x) { }
    }

    /** Ghi lỗi JavaScript trong giao diện (tối đa 40 dòng mỗi lần chạy app, tránh đầy tệp). */
    static synchronized void logJs(Context c, String where, String msg) {
        if (jsCount++ >= 40) return;
        try {
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            write(c, "---- " + when + " | JS " + where + ": " + msg + "\n");
        } catch (Throwable x) { }
    }

    /** Bắt mọi lỗi làm app văng: ghi ra tệp rồi để hệ thống xử lý như cũ; lần mở sau sẽ báo cho người dùng. */
    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread th, Throwable t) {
                try {
                    log(app, "APP VĂNG (luồng " + th.getName() + ")", t);
                    SharedPreferences sp = app.getSharedPreferences("dm_crash", Context.MODE_PRIVATE);
                    sp.edit().putString("last", t.toString()).commit();
                } catch (Throwable x) { }
                if (prev != null) prev.uncaughtException(th, t);
            }
        });
    }

    /** Lần mở trước app bị văng? Trả mô tả lỗi rồi xóa dấu. */
    static String takeLastCrash(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences("dm_crash", Context.MODE_PRIVATE);
            String s = sp.getString("last", null);
            if (s != null) sp.edit().remove("last").commit();
            return s;
        } catch (Throwable t) { return null; }
    }
}
