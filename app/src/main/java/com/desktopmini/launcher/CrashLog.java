package com.desktopmini.launcher;

import android.app.Activity;
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
        return appFile(c).getAbsolutePath();
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
                    String st = stack(t); if (st.length() > 1800) st = st.substring(0, 1800) + "\n...";
                    sp.edit().putString("last", t.toString()).putString("lastStack", st).commit();
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
            android.content.SharedPreferences.Editor ed = sp.edit().putLong("exitSeen", newest).remove("stage");
            if (stage != null) ed.putString("stagePrev", stage);
            ed.commit();
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

    /* ================= Báo lỗi hiện lên màn hình sau khi khởi động lại =================
       Android cũ (dưới 11) không cho hỏi hệ thống vì sao app bị tắt, nên ghi "nhịp tim" 20 giây/lần (RAM trống, bộ nhớ app, việc đang làm)
       và cờ "đang chạy"; nếu lần sau mở lên mà cờ vẫn còn = lần trước tắt đột ngột -> hiện hộp báo để chụp màn hình gửi đi. */
    private static final String SPN = "dm_crash";

    /** Ghi nhanh tình trạng RAM / bộ nhớ của app (gọi định kỳ). */
    static void heartbeat(Context c) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            Runtime rt = Runtime.getRuntime();
            String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String s = when + " | RAM trống " + (mi.availMem >> 20) + "/" + (mi.totalMem >> 20) + " MB" + (mi.lowMemory ? " (HỆ THỐNG BÁO THIẾU RAM)" : "")
                + " | Java " + ((rt.totalMemory() - rt.freeMemory()) >> 20) + "/" + (rt.maxMemory() >> 20) + " MB"
                + " | native " + (android.os.Debug.getNativeHeapAllocatedSize() >> 20) + " MB";
            c.getSharedPreferences(SPN, Context.MODE_PRIVATE).edit().putString("hb", s).apply();
        } catch (Throwable t) { }
    }

    private static String trimName(int l) {
        switch (l) {
            case 5: return "RAM hơi thấp";
            case 10: return "RAM thấp";
            case 15: return "RAM NGUY CẤP";
            case 60: return "app ở nền, sắp bị dọn";
            case 80: return "app ở nền, rất dễ bị tắt";
            default: return "";
        }
    }

    /** Ghi lại các lần hệ thống kêu thiếu RAM (giữ 5 lần gần nhất). */
    static void trimEvent(Context c, int level) {
        try {
            if (level != 5 && level != 10 && level != 15 && level != 60 && level != 80) return;
            SharedPreferences sp = c.getSharedPreferences(SPN, Context.MODE_PRIVATE);
            String when = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            java.util.ArrayList<String> l = new java.util.ArrayList<String>();
            String old = sp.getString("trim", "");
            if (old.length() > 0) for (String x : old.split("\n")) l.add(x);
            l.add(when + " mức " + level + " (" + trimName(level) + ")");
            while (l.size() > 5) l.remove(0);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < l.size(); i++) { if (i > 0) b.append('\n'); b.append(l.get(i)); }
            sp.edit().putString("trim", b.toString()).apply();
        } catch (Throwable t) { }
    }

    /** Gọi một lần ở đầu onCreate. Trả true nếu lần chạy trước KHÔNG thoát bình thường (không tính khi vừa cập nhật app hoặc máy vừa khởi động lại). */
    static boolean beginSession(Context c) {
        boolean unclean = false;
        try {
            SharedPreferences sp = c.getSharedPreferences(SPN, Context.MODE_PRIVATE);
            long boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
            long upd = 0;
            try { upd = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime; } catch (Throwable t) { }
            boolean wasAlive = sp.getBoolean("alive", false);
            long oldBoot = sp.getLong("boot", 0), oldUpd = sp.getLong("upd", 0);
            boolean rebooted = oldBoot != 0 && Math.abs(boot - oldBoot) > 120000;
            boolean updated = oldUpd != 0 && oldUpd != upd;
            unclean = wasAlive && !rebooted && !updated;
            sp.edit().putBoolean("alive", true).putLong("boot", boot).putLong("upd", upd).commit();
        } catch (Throwable t) { }
        return unclean;
    }

    /** Thoát bình thường (Activity bị đóng hẳn): xóa cờ "đang chạy". */
    static void endSession(Context c) {
        try { c.getSharedPreferences(SPN, Context.MODE_PRIVATE).edit().putBoolean("alive", false).commit(); } catch (Throwable t) { }
    }

    /** Vài dòng nhật ký hệ thống liên quan tới việc app bị tắt (chỉ thấy được dòng của chính app này). */
    private static String logTail() {
        try {
            final java.util.LinkedList<String> keep = new java.util.LinkedList<String>();
            final Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", "2500"});
            Thread rd = new Thread(new Runnable() { public void run() {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                    String ln;
                    String[] k = {"fatal", "androidruntime", "signal", "sigsegv", "abort", "tombstone", "lowmemory", "lmk", "outofmemory", "oom", "killing", "died", "anr", "backtrace", "crash"};
                    while ((ln = r.readLine()) != null) {
                        String lo = ln.toLowerCase(Locale.ROOT);
                        for (String x : k) if (lo.contains(x)) {
                            keep.add(ln.length() > 200 ? ln.substring(0, 200) : ln);
                            while (keep.size() > 25) keep.removeFirst();
                            break;
                        }
                    }
                } catch (Throwable t) { }
            } }, "crashlog-logcat");
            rd.start(); rd.join(4000);
            try { p.destroy(); } catch (Throwable t) { }
            StringBuilder b = new StringBuilder();
            synchronized (keep) { for (String x : new java.util.ArrayList<String>(keep)) b.append(x).append('\n'); }
            return b.toString();
        } catch (Throwable t) { return ""; }
    }

    /** Nếu lần chạy trước bị lỗi / tắt đột ngột: dựng bản báo lỗi, ghi vào tệp và hiện hộp thoại trên màn hình (không tự tắt) để chụp lại gửi đi. */
    static void reportAsync(final Activity a, String lastExit, String javaCrash, boolean unclean) {
        if (true) return; /* Đã tắt: không hiện hộp báo lỗi nữa (lỗi vẫn ghi vào tệp riêng của app) */
        if (lastExit == null && javaCrash == null && !unclean) return;
        final Context c = a.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences(SPN, Context.MODE_PRIVATE);
        final StringBuilder b = new StringBuilder();
        b.append(javaCrash != null ? "LẦN TRƯỚC APP BỊ LỖI VĂNG (lỗi Java)\n" : "LẦN TRƯỚC APP BỊ TẮT ĐỘT NGỘT\n");
        b.append("(nếu bạn tự vuốt tắt app thì bỏ qua hộp này)\n\n");
        if (javaCrash != null) b.append("Lỗi: ").append(javaCrash).append('\n');
        String st = sp.getString("lastStack", null);
        if (st != null) b.append(st).append('\n');
        if (lastExit != null) b.append("Hệ thống Android báo: ").append(lastExit).append('\n');
        else if (javaCrash == null) b.append("Không có lỗi Java nào được ghi lại: nhiều khả năng là văng ở phần gốc (Gecko), hết RAM bị hệ thống tắt, hoặc bị treo rồi bị tắt.\n");
        String stage = sp.getString("stage", sp.getString("stagePrev", null));
        if (stage != null) b.append("Đang làm lúc đó: ").append(stage).append('\n');
        String hb = sp.getString("hb", null);
        if (hb != null) b.append("Nhịp ghi cuối (RAM): ").append(hb).append('\n');
        String trim = sp.getString("trim", "");
        if (trim.length() > 0) b.append("Lần hệ thống kêu thiếu RAM gần đây:\n").append(trim).append('\n');
        b.append('\n').append(header(c));
        sp.edit().remove("lastStack").remove("stagePrev").remove("trim").apply();
        new Thread(new Runnable() { public void run() {
            String lg = logTail();
            if (lg.length() > 0) b.append("\nNhật ký hệ thống (rút gọn):\n").append(lg);
            b.append("\nTệp đầy đủ: ").append(where(c)).append('\n');
            final String text = b.toString();
            try { write(c, "==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " | BÁO LỖI HIỆN TRÊN MÀN HÌNH ====\n" + text + "\n"); } catch (Throwable t) { }
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() { public void run() { showReport(a, text); } }, 900);
        } }, "crashlog-report").start();
    }

    private static void showReport(final Activity a, final String text) {
        try {
            if (a.isFinishing()) return;
            float d = a.getResources().getDisplayMetrics().density;
            android.widget.TextView tv = new android.widget.TextView(a);
            tv.setText(text); tv.setTextSize(12); tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextIsSelectable(true);
            int pd = Math.round(14 * d); tv.setPadding(pd, pd, pd, pd);
            android.widget.ScrollView sv = new android.widget.ScrollView(a); sv.addView(tv);
            final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(a)
                .setTitle("Báo lỗi — chụp màn hình này gửi mình")
                .setView(sv).setCancelable(false)
                .setPositiveButton("Đóng", null)
                .setNeutralButton("Sao chép", null).create();
            dlg.show();
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new android.view.View.OnClickListener() { public void onClick(android.view.View v) {
                try {
                    ((android.content.ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("loi", text));
                    android.widget.Toast.makeText(a, "Đã sao chép", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable t) { }
            } });
        } catch (Throwable t) { }
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
