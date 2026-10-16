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


    /* ---- Dấu vết: app đang làm gì thì bị tắt (lỗi native của Gecko không đi qua trình bắt lỗi Java) ---- */
    static void mark(Context c, String stage) {
        try {
            String when = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            c.getSharedPreferences("dm_crash", Context.MODE_PRIVATE).edit().putString("stage", when + " " + stage).commit();
        } catch (Throwable t) { }
    }
    static void clearMark(Context c) {
        try { c.getSharedPreferences("dm_crash", Context.MODE_PRIVATE).edit().remove("stage").commit(); } catch (Throwable t) { }
    }

    /** Android 11+: hỏi hệ thống vì sao lần chạy trước bị tắt (văng native, hết RAM, treo...). Ghi vào tệp lỗi; trả tóm tắt nếu bất thường. */
    static String reportLastExit(Context c) {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 8);
            SharedPreferences sp = c.getSharedPreferences("dm_crash", Context.MODE_PRIVATE);
            long seen = sp.getLong("exitSeen", 0), newest = seen;
            String stage = sp.getString("stage", null), sum = null;
            for (android.app.ApplicationExitInfo e : l) {
                if (e.getTimestamp() <= seen) continue;
                newest = Math.max(newest, e.getTimestamp());
                int r = e.getReason();
                if (r == android.app.ApplicationExitInfo.REASON_USER_REQUESTED || r == android.app.ApplicationExitInfo.REASON_USER_STOPPED
                    || r == android.app.ApplicationExitInfo.REASON_EXIT_SELF || r == android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE) continue;
                String name = exitName(r);
                StringBuilder b = new StringBuilder();
                b.append("==== ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(e.getTimestamp()))).append(" | LẦN TRƯỚC BỊ TẮT: ").append(name).append(" ====\n");
                b.append(header(c));
                b.append("Tiến trình: ").append(e.getProcessName()).append(" | status=").append(e.getStatus()).append(" | mô tả hệ thống: ").append(e.getDescription()).append('\n');
                b.append("RAM lúc đó: pss=").append(e.getPss()).append("KB rss=").append(e.getRss()).append("KB\n");
                if (stage != null) b.append("Đang làm: ").append(stage).append('\n');
                if (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || r == android.app.ApplicationExitInfo.REASON_ANR) {
                    try {
                        java.io.InputStream in = e.getTraceInputStream();
                        if (in != null) {
                            byte[] buf = new byte[6000]; int n = in.read(buf); in.close();
                            if (n > 0) b.append("Dấu vết (đầu tệp):\n").append(new String(buf, 0, n, "ISO-8859-1").replaceAll("[^\\x20-\\x7E\\n]", ".")).append('\n');
                        }
                    } catch (Throwable t) { }
                }
                write(c, b.toString() + "\n");
                if (sum == null) sum = name + (stage != null ? " — lúc: " + stage : "");
            }
            sp.edit().putLong("exitSeen", newest).remove("stage").commit();
            return sum;
        } catch (Throwable t) { return null; }
    }

    private static String exitName(int r) {
        switch (r) {
            case 4: return "APP VĂNG (lỗi Java)";
            case 5: return "VĂNG NATIVE (lỗi mã gốc, thường là Gecko)";
            case 6: return "TREO (ANR)";
            case 3: return "HỆ THỐNG TẮT VÌ THIẾU RAM (LMK)";
            case 2: return "BỊ HỆ THỐNG TẮT (tín hiệu)";
            case 7: return "KHỞI TẠO LỖI";
            case 9: return "DÙNG QUÁ NHIỀU TÀI NGUYÊN";
            case 12: return "BỊ ĐÓNG BĂNG";
            default: return "mã " + r;
        }
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
