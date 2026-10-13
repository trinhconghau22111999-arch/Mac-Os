package com.desktopmini.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Size;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class MainActivity extends Activity {
    private static MainActivity inst;
    private FrameLayout root;
    private boolean askingRuntime = false;
    private WebView web;
    private final HashMap<String, WebView> ext = new HashMap<String, WebView>();
    /** Gmail / Drive / Zalo / Facebook web: giao diện máy tính. */
    private static final String UA_DESK = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    /** Đẩy sự kiện từ service nền vào giao diện HTML. */
    static void push(final String fn, final String json) {
        final MainActivity a = inst;
        if (a == null || a.web == null) return;
        a.runOnUiThread(new Runnable() { public void run() { a.web.evaluateJavascript("window." + fn + "&&window." + fn + "(" + json + ")", null); } });
    }

    static String iconOf(Context c, String pkg) {
        try { return icon(c.getPackageManager().getApplicationIcon(pkg), 64); } catch (Exception e) { return ""; }
    }

    private final BroadcastReceiver media = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String cmd = i.getStringExtra("cmd");
            if (cmd != null && web != null) web.evaluateJavascript("window.nativeMedia&&window.nativeMedia('" + cmd + "')", null);
        }
    };

    private final BroadcastReceiver scr = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (web != null) web.evaluateJavascript("window.nativeScreenOff&&window.nativeScreenOff()", null);
        }
    };
    private android.media.Ringtone previewTone;
    /* ---- Bluetooth: thiết bị tìm thấy xung quanh + proxy hồ sơ để biết thiết bị nào đang kết nối ---- */
    private final java.util.LinkedHashMap<String, JSONObject> btFound = new java.util.LinkedHashMap<String, JSONObject>();
    private final HashMap<Integer, BluetoothProfile> btProx = new HashMap<Integer, BluetoothProfile>();
    private boolean btRecv = false, btProxReq = false;
    private final BroadcastReceiver btRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try {
                if (!BluetoothDevice.ACTION_FOUND.equals(i.getAction())) return;
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null || d.getBondState() == BluetoothDevice.BOND_BONDED) return;
                String nm = d.getName(); if (nm == null || nm.length() == 0) return;
                JSONObject o = new JSONObject(); o.put("name", nm); o.put("addr", d.getAddress());
                synchronized (btFound) { btFound.put(d.getAddress(), o); }
            } catch (Exception e) { }
        }
    };


    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        web.setBackgroundColor(0xFF000000);
        web.setFocusable(true); web.setFocusableInTouchMode(true); web.requestFocus();
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onPermissionRequest(final android.webkit.PermissionRequest q) {
                runOnUiThread(new Runnable() { public void run() {
                    ArrayList<String> miss = new ArrayList<String>(); boolean known = false;
                    for (String rs : q.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(rs)) { known = true; if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.RECORD_AUDIO); }
                        else if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(rs)) { known = true; if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) miss.add(Manifest.permission.CAMERA); }
                    }
                    if (!known) { q.deny(); return; }
                    if (miss.isEmpty()) { answerWeb(q); return; }
                    if (micReq != null) micReq.deny();
                    micReq = q; askingRuntime = true;
                    requestPermissions(miss.toArray(new String[0]), 77);
                } });
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidLauncher");
        web.loadUrl("file:///android_asset/index.html");
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        IntentFilter f = new IntentFilter("com.desktopmini.launcher.MEDIA");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(media, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(media, f);
        IntentFilter sf = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(scr, sf, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(scr, sf);
        trackIme();
        immersive();
        askPermissions();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(media); } catch (Exception e) { }
        try { unregisterReceiver(scr); } catch (Exception e) { }
        if (btRecv) { try { unregisterReceiver(btRx); } catch (Exception e) { } }
        if (inst == this) inst = null;
        super.onDestroy();
    }

    /** Bàn phím đang mở? Khi mở thì không ép ẩn thanh hệ thống, vì làm vậy sẽ đóng bàn phím (ô nhập bị văng). */
    private boolean imeUp = false;
    private android.webkit.PermissionRequest micReq = null;
    /** Trả lời yêu cầu micro/camera của trang web: chỉ cho phần mà Android đã cấp. */
    private void answerWeb(android.webkit.PermissionRequest q) {
        ArrayList<String> ok = new ArrayList<String>();
        for (String rs : q.getResources()) {
            boolean g = Build.VERSION.SDK_INT < 23;
            if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(rs)) g = g || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            else if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(rs)) g = g || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
            if (g) ok.add(rs);
        }
        if (ok.isEmpty()) q.deny(); else q.grant(ok.toArray(new String[0]));
    }
    private void trackIme() {
        final View d = getWindow().getDecorView();
        d.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                boolean up;
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsets wi = d.getRootWindowInsets();
                    up = wi != null && wi.isVisible(WindowInsets.Type.ime());
                } else {
                    android.graphics.Rect r = new android.graphics.Rect();
                    d.getWindowVisibleDisplayFrame(r);
                    int h = d.getRootView().getHeight();
                    up = h > 0 && (h - r.bottom) > h * 0.15f;
                }
                if (up != imeUp) { imeUp = up; if (!up) immersive(); }
            }
        });
    }

    private void immersive() {
        if (imeUp) return;
        if (Build.VERSION.SDK_INT >= 30) {
            /* Android 11+: cờ SYSTEM_UI_FLAG cũ hay bị bỏ qua, dùng WindowInsetsController để ẩn thanh điều hướng */
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
            }
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Xin các quyền thường ngay lần mở đầu tiên sau khi cài. */
    private void askPermissions() {
        ArrayList<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_AUDIO); need.add(Manifest.permission.READ_MEDIA_IMAGES); need.add(Manifest.permission.READ_MEDIA_VIDEO); need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        need.add(Manifest.permission.RECORD_AUDIO);
        need.add(Manifest.permission.CAMERA);
        need.add(Manifest.permission.READ_CALENDAR);
        need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        ArrayList<String> miss = new ArrayList<String>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        if (!miss.isEmpty()) { askingRuntime = true; requestPermissions(miss.toArray(new String[0]), 7); }
    }

    private boolean listed(String setting) {
        String v = Settings.Secure.getString(getContentResolver(), setting);
        return v != null && v.contains(getPackageName());
    }
    private boolean notifAccess() { return listed("enabled_notification_listeners"); }
    private boolean a11yOn() { return listed(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); }
    private Uri mediaUri(String kind) {
        if ("vid".equals(kind)) return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        if ("aud".equals(kind)) return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    }

    /** Desktop Mini có đang là màn hình chính mặc định của Android không. */
    private boolean isDefaultHome() {
        try {
            ResolveInfo r = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        } catch (Exception e) { return false; }
    }

    private boolean filesOk() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Mỗi quyền đặc biệt chỉ hỏi một lần; lần lượt từng quyền mỗi khi quay lại app. */
    private void setupNext() {
        SharedPreferences sp = getSharedPreferences("setup", 0);
        if (!Settings.System.canWrite(this) && !sp.getBoolean("ws", false)) {
            sp.edit().putBoolean("ws", true).apply();
            go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); return;
        }
        if (!notifAccess() && !sp.getBoolean("nl", false)) {
            sp.edit().putBoolean("nl", true).apply();
            go(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); return;
        }
        if (!a11yOn() && !sp.getBoolean("ax", false)) {
            sp.edit().putBoolean("ax", true).apply();
            go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return;
        }
        if (Build.VERSION.SDK_INT <= 29 && !hasSecureWrite() && !sp.getBoolean("ss", false)) {
            sp.edit().putBoolean("ss", true).apply();
            askSecureWrite(); return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted() && !sp.getBoolean("dnd", false)) {
            sp.edit().putBoolean("dnd", true).apply();
            go(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return;
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !sp.getBoolean("af", false)) {
            sp.edit().putBoolean("af", true).apply();
            go(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); return;
        }
    }


    private static final String SS_CMD = "pm grant com.desktopmini.launcher android.permission.WRITE_SECURE_SETTINGS";
    private boolean hasSecureWrite() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }
    /** Android 10 trở xuống: hỏi quyền để ẩn hẳn thanh trạng thái / thanh điều hướng khi mở app khác. */
    private void askSecureWrite() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Ẩn thanh trạng thái và thanh điều hướng")
            .setMessage("Để ẩn hẳn thanh thông báo và thanh điều hướng của hệ thống khi mở app khác, Desktop Mini cần quyền đặc biệt WRITE_SECURE_SETTINGS.\n\n"
                + "Android không có hộp xin quyền này, nên phải cấp bằng một trong hai cách:\n"
                + "• Máy đã root: bấm \"Cấp bằng root\".\n"
                + "• Máy tính: nối adb rồi chạy\nadb shell " + SS_CMD)
            .setPositiveButton("Cấp bằng root", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { grantByRoot(); } })
            .setNeutralButton("Sao chép lệnh adb", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    try {
                        ((android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(android.content.ClipData.newPlainText("adb", "adb shell " + SS_CMD));
                        android.widget.Toast.makeText(MainActivity.this, "Đã sao chép lệnh", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Exception e) { }
                } })
            .setNegativeButton("Để sau", null)
            .show();
    }
    private void grantByRoot() {
        new Thread(new Runnable() { public void run() {
            boolean ok = false;
            try {
                Process p = Runtime.getRuntime().exec(new String[] { "su", "-c", SS_CMD });
                p.waitFor();
            } catch (Exception e) { }
            ok = hasSecureWrite();
            final boolean res = ok;
            runOnUiThread(new Runnable() { public void run() {
                android.widget.Toast.makeText(MainActivity.this, res ? "Đã cấp quyền, thanh hệ thống sẽ được ẩn khi mở app khác"
                    : "Không cấp được (máy chưa root?). Hãy dùng lệnh adb.", android.widget.Toast.LENGTH_LONG).show();
            } });
        } }).start();
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        askingRuntime = false;
        if (c == 77) {
            final android.webkit.PermissionRequest q = micReq; micReq = null;
            if (q != null) answerWeb(q);
            return;
        }
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        root.post(new Runnable() { public void run() { setupNext(); } });
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 91) push("nativeDelResult", res == RESULT_OK ? "1" : "0");
    }
    /** Ẩn thanh điều hướng ngay và thêm một lần sau 0,4 giây (một số máy tự hiện lại thanh khi app vừa quay về). */
    private void hideBars() {
        immersive();
        getWindow().getDecorView().postDelayed(new Runnable() { public void run() { immersive(); } }, 400);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    @Override protected void onResume() {
        super.onResume();
        imeUp = false; hideBars();
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        if (!askingRuntime) root.post(new Runnable() { public void run() { setupNext(); } });
    }
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String oa = i == null ? null : i.getStringExtra("openApp");
        if (oa != null) web.evaluateJavascript("window.nativeOpenApp&&window.nativeOpenApp(" + JSONObject.quote(oa) + ")", null);
        else web.evaluateJavascript("window.nativeHome&&window.nativeHome()", null);
    }
    @Override public void onBackPressed() { web.evaluateJavascript("window.nativeBack&&window.nativeBack()", null); }

    private void go(Intent i) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(i); } catch (Exception e) { } }

    private WebView extGet(final String key, String url) {
        WebView v = ext.get(key);
        if (v == null) {
            v = new WebView(this);
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
            s.setUserAgentString(("gmail".equals(key) || "drive".equals(key) || "zalo".equals(key) || "facebook".equals(key)) ? UA_DESK : UA);
            CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(v, true);
            v.setWebViewClient(new WebViewClient() {
                @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                    if (req.isForMainFrame() && web != null) web.evaluateJavascript("window.nativeExtError&&window.nativeExtError(" + JSONObject.quote(key) + ")", null);
                }
            });
            v.setVisibility(View.GONE);
            root.addView(v, new FrameLayout.LayoutParams(1, 1));
            v.loadUrl(url);
            ext.put(key, v);
        }
        return v;
    }


    /* ================= Trình duyệt Gecko nhúng trong cửa sổ (chép từ repo dang-nhap-github) ================= */
    private static final String GK_HOME = "https://www.google.com/";
    private static final String GK_SEARCH = "https://duckduckgo.com/?q=";
    private static org.mozilla.geckoview.GeckoRuntime gkRuntime;
    private org.mozilla.geckoview.GeckoSession gkSession;
    private org.mozilla.geckoview.GeckoView gkView;
    private boolean gkBackOk, gkFwdOk;
    private String gkUrl = "";
    private int gkProg = 0;

    private void gkPush() {
        if (web == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("url", gkUrl); o.put("back", gkBackOk); o.put("fwd", gkFwdOk); o.put("p", gkProg);
            web.evaluateJavascript("window.nativeGk&&window.nativeGk(" + o.toString() + ")", null);
        } catch (Exception e) { }
    }
    private void gkToast(final String msg) {
        runOnUiThread(new Runnable() { public void run() { android.widget.Toast.makeText(MainActivity.this, msg, android.widget.Toast.LENGTH_LONG).show(); } });
    }
    private String gkHeader(org.mozilla.geckoview.WebResponse r, String key) {
        if (r.headers == null) return null;
        for (java.util.Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }
    private int gkDlSeq = 0;
    /** Báo tiến độ tải xuống cho giao diện (trang Tải xuống trong cửa sổ trình duyệt). */
    private void gkDl(final int id, final String name, final String state, final long got, final long total, final String msg) {
        runOnUiThread(new Runnable() { public void run() {
            if (web == null) return;
            try {
                JSONObject o = new JSONObject();
                o.put("id", id); o.put("name", name); o.put("state", state); o.put("got", got); o.put("total", total); o.put("msg", msg == null ? "" : msg);
                web.evaluateJavascript("window.nativeGkDl&&window.nativeGkDl(" + o.toString() + ")", null);
            } catch (Exception e) { }
        } });
    }
    private void gkDownload(final org.mozilla.geckoview.WebResponse r) {
        final String name0 = android.webkit.URLUtil.guessFileName(r.uri, gkHeader(r, "content-disposition"), gkHeader(r, "content-type"));
        String ct = gkHeader(r, "content-type");
        if (ct != null) { int sc = ct.indexOf(';'); if (sc >= 0) ct = ct.substring(0, sc); ct = ct.trim(); }
        final String mime = (ct == null || ct.length() == 0) ? "application/octet-stream" : ct;
        long tl = -1;
        try { String cl = gkHeader(r, "content-length"); if (cl != null) tl = Long.parseLong(cl.trim()); } catch (Exception e) { tl = -1; }
        final long total = tl;
        final int id = ++gkDlSeq;
        gkDl(id, name0, "run", 0, total, "");
        new Thread(new Runnable() { public void run() {
            Uri mu = null; OutputStream out = null; String finalName = name0, legacyPath = null;
            try {
                InputStream in = r.body;
                if (in == null) throw new java.io.IOException("không có dữ liệu");
                if (Build.VERSION.SDK_INT >= 29) {
                    /* Android 10+: ghi vào Download qua MediaStore (không cần quyền riêng) */
                    android.content.ContentValues v = new android.content.ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name0);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    mu = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (mu == null) throw new java.io.IOException("không tạo được tệp");
                    out = getContentResolver().openOutputStream(mu);
                    Cursor c = getContentResolver().query(mu, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
                    if (c != null) { try { if (c.moveToFirst() && c.getString(0) != null) finalName = c.getString(0); } finally { c.close(); } }
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!(dir.exists() || dir.mkdirs()) || !dir.canWrite()) dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, name0);
                    int i = 1; String base = name0, ex = "";
                    int dot = name0.lastIndexOf('.');
                    if (dot > 0) { base = name0.substring(0, dot); ex = name0.substring(dot); }
                    while (f.exists()) f = new File(dir, base + " (" + (i++) + ")" + ex);
                    finalName = f.getName();
                    out = new FileOutputStream(f);
                    legacyPath = f.getAbsolutePath();
                }
                if (out == null) throw new java.io.IOException("không ghi được tệp");
                long got = 0, lastPush = 0;
                try {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); got += n;
                        long now = System.currentTimeMillis();
                        if (now - lastPush > 250) { lastPush = now; gkDl(id, finalName, "run", got, total, ""); }
                    }
                } finally { out.close(); }
                in.close();
                if (mu != null) {
                    android.content.ContentValues v2 = new android.content.ContentValues();
                    v2.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(mu, v2, null, null);
                } else if (legacyPath != null) {
                    android.media.MediaScannerConnection.scanFile(MainActivity.this, new String[]{legacyPath}, null, null);
                }
                gkDl(id, finalName, "ok", got, got, "");
            } catch (Exception e) {
                if (mu != null) { try { getContentResolver().delete(mu, null, null); } catch (Exception x) { } }
                gkDl(id, finalName, "err", 0, total, String.valueOf(e.getMessage()));
            }
        } }).start();
    }
    private String gkToUrl(String text) {
        if (text.startsWith("http://") || text.startsWith("https://") || text.startsWith("file://") || text.startsWith("about:")) return text;
        if (!text.contains(" ") && text.contains(".")) return "https://" + text;
        return GK_SEARCH + Uri.encode(text);
    }
    private void gkEnsure() {
        if (gkView != null) return;
        if (gkRuntime == null) gkRuntime = org.mozilla.geckoview.GeckoRuntime.create(getApplicationContext());
        gkUrl = GK_HOME; gkBackOk = false; gkFwdOk = false; gkProg = 0;
        gkView = new org.mozilla.geckoview.GeckoView(this);
        /* TextureView để lớp nổi nằm đúng thứ tự trên giao diện HTML */
        gkView.setViewBackend(org.mozilla.geckoview.GeckoView.BACKEND_TEXTURE_VIEW);
        final org.mozilla.geckoview.GeckoSession se = new org.mozilla.geckoview.GeckoSession();
        gkSession = se;
        se.setContentDelegate(new org.mozilla.geckoview.GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(org.mozilla.geckoview.GeckoSession s, org.mozilla.geckoview.WebResponse response) { gkDownload(response); }
        });
        se.setProgressDelegate(new org.mozilla.geckoview.GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(org.mozilla.geckoview.GeckoSession s, String url) { if (url != null) gkUrl = url; gkProg = 5; gkPush(); }
            @Override public void onProgressChange(org.mozilla.geckoview.GeckoSession s, int p) { gkProg = p; gkPush(); }
            @Override public void onPageStop(org.mozilla.geckoview.GeckoSession s, boolean success) { gkProg = 0; gkPush(); }
        });
        se.setNavigationDelegate(new org.mozilla.geckoview.GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(org.mozilla.geckoview.GeckoSession s, boolean can) { gkBackOk = can; gkPush(); }
            @Override public void onCanGoForward(org.mozilla.geckoview.GeckoSession s, boolean can) { gkFwdOk = can; gkPush(); }
            @Override public org.mozilla.geckoview.GeckoResult<org.mozilla.geckoview.GeckoSession> onNewSession(org.mozilla.geckoview.GeckoSession s, String uri) {
                /* Link mở tab mới -> mở ngay trong tab hiện tại */
                se.loadUri(uri); return null;
            }
        });
        se.open(gkRuntime);
        gkView.setSession(se);
        gkView.setVisibility(View.GONE);
        root.addView(gkView, new FrameLayout.LayoutParams(1, 1));
        se.loadUri(GK_HOME);
    }
    private void gkHideNow() { if (gkView != null) gkView.setVisibility(View.GONE); }

    private static String icon(Drawable d, int n) {
        Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm); d.setBounds(0, 0, n, n); d.draw(c);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 90, os);
        return "data:image/png;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
    }

    /** Cầu nối: giao diện HTML điều khiển cài đặt và dữ liệu thật của máy. */
    public class Bridge {
        private AudioManager am() { return (AudioManager) getSystemService(Context.AUDIO_SERVICE); }

        /* ---- Âm lượng, độ sáng, kết nối ---- */
        @JavascriptInterface public int getVolume() {
            AudioManager a = am(); return Math.round(a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / a.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        }
        @JavascriptInterface public void setVolume(int p) {
            AudioManager a = am(); int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            a.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, Math.round(p * max / 100f))), 0);
        }
        private int streamOf(String k) {
            if ("alarm".equals(k)) return AudioManager.STREAM_ALARM;
            if ("ring".equals(k)) return AudioManager.STREAM_RING;
            return AudioManager.STREAM_NOTIFICATION;
        }
        /** Âm lượng báo thức / chuông cuộc gọi / thông báo (0-100). */
        @JavascriptInterface public int getStreamVol(String kind) {
            try { AudioManager a = am(); int st = streamOf(kind); return Math.round(a.getStreamVolume(st) * 100f / Math.max(1, a.getStreamMaxVolume(st))); }
            catch (Exception e) { return -1; }
        }
        /** Trả về false nếu bị chặn (thường do chế độ Không làm phiền). */
        @JavascriptInterface public boolean setStreamVol(String kind, int p) {
            try {
                AudioManager a = am(); int st = streamOf(kind), max = a.getStreamMaxVolume(st);
                a.setStreamVolume(st, Math.max(0, Math.min(max, Math.round(p * max / 100f))), 0);
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void playSound(String kind) {
            try {
                int type = "alarm".equals(kind) ? android.media.RingtoneManager.TYPE_ALARM : "ring".equals(kind) ? android.media.RingtoneManager.TYPE_RINGTONE : android.media.RingtoneManager.TYPE_NOTIFICATION;
                Uri u = android.media.RingtoneManager.getDefaultUri(type);
                final android.media.Ringtone r = android.media.RingtoneManager.getRingtone(MainActivity.this, u);
                if (r == null) return;
                if (Build.VERSION.SDK_INT >= 21) r.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage("alarm".equals(kind) ? android.media.AudioAttributes.USAGE_ALARM : "ring".equals(kind) ? android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE : android.media.AudioAttributes.USAGE_NOTIFICATION).build());
                r.play();
                web.postDelayed(new Runnable() { public void run() { try { r.stop(); } catch (Exception e) { } } }, 1500);
            } catch (Exception e) { }
        }
        @JavascriptInterface public int getBrightness() {
            try {
                float w = getWindow().getAttributes().screenBrightness;
                int raw = (w >= 0f && !Settings.System.canWrite(MainActivity.this)) ? Math.round(w * 255f)
                        : Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                raw = Math.max(1, Math.min(255, raw));
                return Math.max(1, Math.round((float) (100.0 * Math.pow(raw / 255.0, 0.5))));
            }
            catch (Exception e) { return 60; }
        }
        @JavascriptInterface public boolean canWriteSettings() { return Settings.System.canWrite(MainActivity.this); }
        @JavascriptInterface public void requestWriteSettings() {
            go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
        @JavascriptInterface public void setBrightness(final int p) {
            final int pc = Math.max(1, Math.min(100, p));
            // Độ sáng cảm nhận không tuyến tính: dùng đường cong bình phương để thanh trượt về thấp là thật sự tối
            final int v = Math.max(1, Math.min(255, (int) Math.round(255.0 * (pc / 100.0) * (pc / 100.0))));
            if (Settings.System.canWrite(MainActivity.this)) {
                try {
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
                } catch (Exception ignored) { }
            }
            // Luôn đặt độ sáng theo cửa sổ để có hiệu lực ngay và xuống được mức tối nhất
            runOnUiThread(new Runnable() { public void run() {
                WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = Math.max(0.004f, v / 255f); getWindow().setAttributes(lp);
            }});
        }
        @JavascriptInterface public void openWallpaper() {
            go(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Hình nền"));
        }
        @JavascriptInterface public void openSystemSettings() {
            go(new Intent(Settings.ACTION_SETTINGS));
        }
        @JavascriptInterface public String getWallpaper() {
            try {
                Drawable d = android.app.WallpaperManager.getInstance(MainActivity.this).getDrawable();
                if (d == null) return "";
                int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
                float k = Math.min(1f, 1080f / Math.max(w, h));
                int tw = Math.max(1, Math.round(w * k)), th = Math.max(1, Math.round(h * k));
                Bitmap bm = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bm); d.setBounds(0, 0, tw, th); d.draw(c);
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 80, os);
                return "data:image/jpeg;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
            } catch (Throwable e) { return ""; }
        }
        @JavascriptInterface public boolean isWifiOn() {
            try { return ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).isWifiEnabled(); } catch (Exception e) { return true; }
        }
        @JavascriptInterface public boolean isBluetoothOn() {
            try { BluetoothAdapter a = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter(); return a != null && a.isEnabled(); }
            catch (Exception e) { return false; }
        }
        @JavascriptInterface public boolean isOnline() {
            try { ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE); return cm.getActiveNetwork() != null; }
            catch (Exception e) { return true; }
        }
        /* ---- Wi-Fi / Bluetooth thật: danh sách mạng, thiết bị đã kết nối và chưa kết nối ---- */
        private boolean locOk() {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        private String cleanSsid(String s) {
            if (s == null) return "";
            if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            return "<unknown ssid>".equals(s) ? "" : s;
        }
        private WifiManager wifiMgr() { return (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE); }
        @JavascriptInterface public void wifiScan() { try { wifiMgr().startScan(); } catch (Exception e) { } }
        @JavascriptInterface public String getWifiInfo() {
            JSONObject res = new JSONObject(); JSONArray arr = new JSONArray();
            try {
                WifiManager wm = wifiMgr();
                boolean on = wm.isWifiEnabled(); res.put("on", on); res.put("loc", locOk());
                String cur = ""; boolean conn = false; int curLvl = 0;
                WifiInfo wi = wm.getConnectionInfo();
                if (on && wi != null && wi.getNetworkId() != -1 && wi.getSupplicantState() == SupplicantState.COMPLETED) {
                    conn = true; cur = cleanSsid(wi.getSSID()); curLvl = WifiManager.calculateSignalLevel(wi.getRssi(), 4);
                }
                java.util.HashSet<String> saved = new java.util.HashSet<String>();
                try { List<WifiConfiguration> cf = wm.getConfiguredNetworks(); if (cf != null) for (WifiConfiguration c : cf) saved.add(cleanSsid(c.SSID)); } catch (Exception e) { }
                java.util.LinkedHashMap<String, JSONObject> best = new java.util.LinkedHashMap<String, JSONObject>();
                if (on && locOk()) {
                    List<ScanResult> sr = wm.getScanResults();
                    if (sr != null) for (ScanResult r : sr) {
                        String ss = r.SSID; if (ss == null || ss.length() == 0) continue;
                        int lv = WifiManager.calculateSignalLevel(r.level, 4);
                        JSONObject o = best.get(ss);
                        if (o != null && o.getInt("level") >= lv) continue;
                        o = new JSONObject(); o.put("ssid", ss); o.put("level", lv);
                        String cp = r.capabilities == null ? "" : r.capabilities;
                        o.put("secure", cp.contains("WPA") || cp.contains("WEP") || cp.contains("PSK") || cp.contains("EAP"));
                        best.put(ss, o);
                    }
                }
                if (conn) {
                    JSONObject o = best.remove(cur);
                    if (o == null) { o = new JSONObject(); o.put("secure", false); }
                    o.put("ssid", cur.length() > 0 ? cur : "Mạng đang kết nối"); o.put("level", curLvl); o.put("connected", true); o.put("saved", true);
                    arr.put(o);
                }
                ArrayList<JSONObject> rest = new ArrayList<JSONObject>(best.values());
                Collections.sort(rest, new Comparator<JSONObject>() { public int compare(JSONObject a, JSONObject b) { return b.optInt("level") - a.optInt("level"); } });
                for (JSONObject o : rest) { o.put("saved", saved.contains(o.getString("ssid"))); o.put("connected", false); arr.put(o); }
                res.put("list", arr);
            } catch (Exception e) { }
            return res.toString();
        }
        /** Kết nối lại mạng đã lưu; mạng chưa lưu thì trả về false để mở trang Wi-Fi của hệ thống. */
        @JavascriptInterface public boolean connectWifi(String ssid) {
            try {
                WifiManager wm = wifiMgr();
                List<WifiConfiguration> cf = wm.getConfiguredNetworks();
                if (cf != null) for (WifiConfiguration c : cf) if (cleanSsid(c.SSID).equals(ssid)) {
                    wm.disconnect(); wm.enableNetwork(c.networkId, true); wm.reconnect(); return true;
                }
            } catch (Exception e) { }
            return false;
        }
        /** Android 10+ không cho ứng dụng tự bật/tắt Wi-Fi: trả về false để mở bảng hệ thống. */
        @JavascriptInterface public boolean setWifi(boolean on) {
            if (Build.VERSION.SDK_INT >= 29) return false;
            try { return wifiMgr().setWifiEnabled(on); } catch (Exception e) { return false; }
        }
        private BluetoothAdapter btAdapter() {
            try { return ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter(); } catch (Exception e) { return null; }
        }
        @JavascriptInterface public boolean setBluetooth(boolean on) {
            if (Build.VERSION.SDK_INT >= 33) return false;
            try { BluetoothAdapter a = btAdapter(); if (a == null) return false; return on ? a.enable() : a.disable(); } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void btScan() {
            try {
                BluetoothAdapter a = btAdapter(); if (a == null || !a.isEnabled()) return;
                if (!btRecv) {
                    IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                    if (Build.VERSION.SDK_INT >= 33) registerReceiver(btRx, f, Context.RECEIVER_EXPORTED); else registerReceiver(btRx, f);
                    btRecv = true;
                }
                synchronized (btFound) { btFound.clear(); }
                if (a.isDiscovering()) a.cancelDiscovery();
                a.startDiscovery();
            } catch (Exception e) { }
        }
        private void btProxies(BluetoothAdapter a) {
            if (btProxReq) return; btProxReq = true;
            BluetoothProfile.ServiceListener l = new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int p, BluetoothProfile proxy) { synchronized (btProx) { btProx.put(p, proxy); } }
                public void onServiceDisconnected(int p) { synchronized (btProx) { btProx.remove(p); } }
            };
            try { a.getProfileProxy(MainActivity.this, l, BluetoothProfile.A2DP); } catch (Exception e) { }
            try { a.getProfileProxy(MainActivity.this, l, BluetoothProfile.HEADSET); } catch (Exception e) { }
        }
        @JavascriptInterface public String getBtInfo() {
            JSONObject res = new JSONObject();
            try {
                BluetoothAdapter a = btAdapter();
                if (a == null) { res.put("none", true); return res.toString(); }
                boolean on = a.isEnabled(); res.put("on", on); res.put("loc", locOk());
                JSONArray paired = new JSONArray(), found = new JSONArray();
                if (on) {
                    btProxies(a);
                    java.util.HashSet<String> cs = new java.util.HashSet<String>();
                    synchronized (btProx) { for (BluetoothProfile p : btProx.values()) { try { for (BluetoothDevice d : p.getConnectedDevices()) cs.add(d.getAddress()); } catch (Exception e) { } } }
                    java.util.HashSet<String> bonded = new java.util.HashSet<String>();
                    ArrayList<JSONObject> up = new ArrayList<JSONObject>(), down = new ArrayList<JSONObject>();
                    try {
                        for (BluetoothDevice d : a.getBondedDevices()) {
                            bonded.add(d.getAddress());
                            boolean c = cs.contains(d.getAddress());
                            if (!c) { try { c = (Boolean) BluetoothDevice.class.getMethod("isConnected").invoke(d); } catch (Throwable t) { } }
                            JSONObject o = new JSONObject(); String nm = d.getName();
                            o.put("name", nm == null || nm.length() == 0 ? d.getAddress() : nm); o.put("addr", d.getAddress()); o.put("connected", c);
                            (c ? up : down).add(o);
                        }
                    } catch (SecurityException se) { res.put("perm", false); }
                    for (JSONObject o : up) paired.put(o);
                    for (JSONObject o : down) paired.put(o);
                    synchronized (btFound) { for (JSONObject o : btFound.values()) if (!bonded.contains(o.optString("addr"))) found.put(o); }
                }
                res.put("paired", paired); res.put("found", found);
            } catch (Exception e) { }
            return res.toString();
        }

        /** Android 10+ không cho ứng dụng tự bật/tắt Wi-Fi, Bluetooth: mở bảng hệ thống để người dùng gạt. */
        @JavascriptInterface public void openPanel(String kind) {
            Intent i;
            if ("wifi".equals(kind)) i = new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIFI_SETTINGS);
            else if ("bt".equals(kind)) i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            else if ("volume".equals(kind) && Build.VERSION.SDK_INT >= 29) i = new Intent(Settings.Panel.ACTION_VOLUME);
            else if ("display".equals(kind)) i = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
            else if ("battery".equals(kind)) i = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY);
            else i = new Intent(Settings.ACTION_SETTINGS);
            go(i);
        }
        @JavascriptInterface public String getInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("model", Build.MANUFACTURER + " " + Build.MODEL); o.put("android", Build.VERSION.RELEASE); o.put("sdk", Build.VERSION.SDK_INT);
                o.put("brand", Build.MANUFACTURER); o.put("device", Build.MODEL);
                if (Build.VERSION.SDK_INT >= 23) o.put("patch", Build.VERSION.SECURITY_PATCH);
                String soc = Build.HARDWARE;
                if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null && Build.SOC_MODEL.length() > 0 && !"unknown".equalsIgnoreCase(Build.SOC_MODEL)) soc = Build.SOC_MODEL;
                o.put("soc", soc);
                o.put("abi", Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
                o.put("cores", Runtime.getRuntime().availableProcessors());
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                o.put("sw", Math.min(dm.widthPixels, dm.heightPixels));
                o.put("sh", Math.max(dm.widthPixels, dm.heightPixels)); o.put("dpi", dm.densityDpi);
                try {
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    ((android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
                    o.put("ram", mi.totalMem); o.put("ramFree", mi.availMem);
                } catch (Exception e) { }
                try {
                    android.os.StatFs sf = new android.os.StatFs(Environment.getDataDirectory().getAbsolutePath());
                    o.put("disk", sf.getTotalBytes()); o.put("diskFree", sf.getAvailableBytes());
                } catch (Exception e) { }
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        /** Chế độ tiết kiệm pin có đang bật không. */
        @JavascriptInterface public boolean isPowerSave() {
            try { return ((android.os.PowerManager) getSystemService(Context.POWER_SERVICE)).isPowerSaveMode(); } catch (Exception e) { return false; }
        }
        /**
         * Bật/tắt tiết kiệm pin. Android chỉ cho ghi cài đặt này khi ứng dụng có quyền WRITE_SECURE_SETTINGS
         * (cấp một lần bằng adb); chưa có thì mở trang Tiết kiệm pin của hệ thống và trả về false.
         */
        @JavascriptInterface public boolean setPowerSave(boolean on) {
            try {
                Settings.Global.putInt(getContentResolver(), "low_power", on ? 1 : 0);
                return true;
            } catch (Exception e) { }
            try { go(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)); } catch (Exception e) { go(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); }
            return false;
        }
        @JavascriptInterface public String getBattery() {
            try {
                Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100), st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                JSONObject o = new JSONObject(); o.put("level", lv / (float) sc);
                o.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL); return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        /** Khoảng đệm an toàn (lỗ camera, thanh điều hướng) tính bằng dp. */
        @JavascriptInterface public String getInsets() {
            try {
                float d = getResources().getDisplayMetrics().density; int top = 0, bottom = 0;
                WindowInsets w = getWindow().getDecorView().getRootWindowInsets();
                if (w != null) {
                    top = w.getSystemWindowInsetTop(); bottom = w.getSystemWindowInsetBottom();
                    /* Thanh trạng thái đã ẩn: giao diện tràn lên sát mép trên, không chừa vùng tai thỏ/lỗ camera */
                }
                JSONObject o = new JSONObject(); o.put("top", Math.round(top / d)); o.put("bottom", Math.round(bottom / d)); return o.toString();
            } catch (Exception e) { return "{}"; }
        }



        /* ---- Ảnh: chia sẻ, lưu bản sao, xóa ---- */
        private File cacheImg(String dataUrl) throws Exception {
            byte[] by = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
            File dir = new File(getCacheDir(), "shared"); dir.mkdirs();
            File[] old = dir.listFiles(); if (old != null) for (File o : old) o.delete();
            File f = new File(dir, "anh_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream os = new FileOutputStream(f); os.write(by); os.close();
            return f;
        }
        /** Chia sẻ bản ghi âm (dataUrl base64) qua bảng chọn ứng dụng của Android. */
        @JavascriptInterface public boolean shareAudio(String dataUrl, String name, String mime) {
            try {
                byte[] by = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
                File dir = new File(getCacheDir(), "shared"); dir.mkdirs();
                File[] old = dir.listFiles(); if (old != null) for (File o : old) o.delete();
                String base = (name == null || name.length() == 0) ? "ghi_am" : name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
                String type = (mime == null || mime.length() == 0) ? "audio/*" : mime.split(";")[0];
                File f = new File(dir, base + (type.contains("mp4") ? ".m4a" : ".webm"));
                FileOutputStream os = new FileOutputStream(f); os.write(by); os.close();
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                i.setClipData(android.content.ClipData.newRawUri("", u));
                go(Intent.createChooser(i, "Chia sẻ bản ghi").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                return true;
            } catch (Exception e) { return false; }
        }
        /** pkg rỗng = mở bảng chọn ứng dụng của Android. dataUrl rỗng = chia sẻ ảnh gốc theo id. */
        @JavascriptInterface public boolean shareImage(String pkg, long id, String dataUrl) {
            try {
                Uri u;
                if (dataUrl != null && dataUrl.length() > 0) u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", cacheImg(dataUrl));
                else u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                Intent i = new Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                i.setClipData(android.content.ClipData.newRawUri("", u));
                if (pkg != null && pkg.length() > 0) {
                    i.setPackage(pkg);
                    try { startActivity(i); return true; } catch (Exception e) { i.setPackage(null); }
                }
                go(Intent.createChooser(i, "Chia sẻ ảnh").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public boolean saveImage(String dataUrl) {
            try {
                byte[] by = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "DesktopMini_" + System.currentTimeMillis() + ".jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DesktopMini");
                Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                OutputStream os = getContentResolver().openOutputStream(u); os.write(by); os.close();
                return true;
            } catch (Exception e) { return false; }
        }
        /** 1 = đã xóa, 2 = đang chờ người dùng xác nhận trong hộp thoại hệ thống, 0 = thất bại. */
        @JavascriptInterface public int deletePhoto(long id) {
            final Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            try { return getContentResolver().delete(u, null, null) > 0 ? 1 : 0; }
            catch (final SecurityException se) {
                if (Build.VERSION.SDK_INT >= 30) {
                    runOnUiThread(new Runnable() { public void run() {
                        try {
                            android.app.PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(u));
                            startIntentSenderForResult(pi.getIntentSender(), 91, null, 0, 0, 0);
                        } catch (Exception e) { push("nativeDelResult", "0"); }
                    }});
                    return 2;
                }
                if (Build.VERSION.SDK_INT >= 29 && se instanceof android.app.RecoverableSecurityException) {
                    final android.app.RecoverableSecurityException rse = (android.app.RecoverableSecurityException) se;
                    runOnUiThread(new Runnable() { public void run() {
                        try { startIntentSenderForResult(rse.getUserAction().getActionIntent().getIntentSender(), 91, null, 0, 0, 0); }
                        catch (Exception e) { push("nativeDelResult", "0"); }
                    }});
                    return 2;
                }
                return 0;
            } catch (Exception e) { return 0; }
        }

        /* Xoá nhiều ảnh/video/nhạc cùng lúc: 1 = đã xoá hết, 2 = chờ người dùng xác nhận (kết quả qua nativeDelResult), 0 = lỗi */
        @JavascriptInterface public int deleteMedia(String kind, String ids) {
            final List<Uri> pend = new ArrayList<Uri>(); int fail = 0;
            for (String t : ids.split(",")) {
                t = t.trim(); if (t.length() == 0) continue;
                long id; try { id = Long.parseLong(t); } catch (Exception e) { continue; }
                Uri u = ContentUris.withAppendedId(mediaUri(kind), id);
                try { if (getContentResolver().delete(u, null, null) <= 0) fail++; }
                catch (SecurityException se) { pend.add(u); }
                catch (Exception e) { fail++; }
            }
            if (pend.isEmpty()) return fail == 0 ? 1 : 0;
            if (Build.VERSION.SDK_INT >= 30) {
                runOnUiThread(new Runnable() { public void run() {
                    try {
                        android.app.PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), pend);
                        startIntentSenderForResult(pi.getIntentSender(), 91, null, 0, 0, 0);
                    } catch (Exception e) { push("nativeDelResult", "0"); }
                }});
                return 2;
            }
            return 0;
        }

        /* ---- Tự xoay màn hình (cài đặt thật của máy) ---- */
        @JavascriptInterface public boolean isAutoRotate() {
            try { return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 1; } catch (Exception e) { return false; }
        }
        /** Cần quyền "Sửa cài đặt hệ thống"; chưa có thì mở trang cấp quyền và trả về false. */
        @JavascriptInterface public boolean setAutoRotate(boolean on) {
            if (!Settings.System.canWrite(MainActivity.this)) {
                go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName()))); return false;
            }
            try {
                if (!on) {
                    int r = getWindowManager().getDefaultDisplay().getRotation();
                    Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, r);
                }
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
                return true;
            } catch (Exception e) { return false; }
        }

        /* ---- Báo thức thật (chạy cả khi app đóng) ---- */
        @JavascriptInterface public String getAlarmSounds() {
            JSONArray arr = new JSONArray();
            try {
                JSONObject d = new JSONObject(); d.put("name", "Mặc định");
                d.put("uri", android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM).toString()); arr.put(d);
                android.media.RingtoneManager rm = new android.media.RingtoneManager(MainActivity.this);
                rm.setType(android.media.RingtoneManager.TYPE_ALARM);
                Cursor c = rm.getCursor();
                if (c != null) {
                    while (c.moveToNext()) {
                        JSONObject o = new JSONObject();
                        o.put("name", c.getString(android.media.RingtoneManager.TITLE_COLUMN_INDEX));
                        o.put("uri", rm.getRingtoneUri(c.getPosition()).toString());
                        arr.put(o);
                    }
                }
            } catch (Exception e) { }
            return arr.toString();
        }
        @JavascriptInterface public void previewSound(final String uri) {
            runOnUiThread(new Runnable() { public void run() {
                try {
                    if (previewTone != null) previewTone.stop();
                    previewTone = android.media.RingtoneManager.getRingtone(MainActivity.this, Uri.parse(uri));
                    if (previewTone == null) return;
                    if (Build.VERSION.SDK_INT >= 21) previewTone.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
                    previewTone.play();
                    web.postDelayed(new Runnable() { public void run() { try { if (previewTone != null) previewTone.stop(); } catch (Exception e) { } } }, 4000);
                } catch (Exception e) { }
            }});
        }
        @JavascriptInterface public void stopPreview() {
            runOnUiThread(new Runnable() { public void run() { try { if (previewTone != null) previewTone.stop(); } catch (Exception e) { } } });
        }
        @JavascriptInterface public boolean setAlarm(int id, int hour, int min, String uri, boolean repeat, String label) {
            return AlarmReceiver.schedule(MainActivity.this, id, hour, min, uri, repeat, label, true);
        }
        @JavascriptInterface public String getAlarmError() { return AlarmReceiver.lastError; }
        @JavascriptInterface public void cancelAlarm(int id) { AlarmReceiver.cancel(MainActivity.this, id); }
        @JavascriptInterface public void stopAlarmRinging() {
            try { startService(new Intent(MainActivity.this, AlarmService.class).setAction("stop")); } catch (Exception e) { }
        }

        /* ---- Khóa màn hình, nguồn (qua dịch vụ trợ năng) ---- */
        /** Tắt màn hình ngay. Dùng quyền quản trị thiết bị (chỉ "khóa màn hình"); Android 9+ có thể dùng Trợ năng thay thế. */
        @JavascriptInterface public boolean lockScreen() {
            try {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName cn = new android.content.ComponentName(MainActivity.this, AdminReceiver.class);
                if (dpm.isAdminActive(cn)) { dpm.lockNow(); return true; }
                if (LockService.inst != null && Build.VERSION.SDK_INT >= 28) return LockService.inst.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                go(new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Cho phép Desktop Mini tắt màn hình khi bạn bấm Tắt máy. Quyền này chỉ dùng để khóa màn hình."));
            } catch (Exception e) { }
            return false;
        }
        @JavascriptInterface public boolean powerMenu() {
            if (LockService.inst != null) return LockService.inst.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
            go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return false;
        }
        /* ---- Dock nổi trên app khác (qua dịch vụ trợ năng) ---- */
        @JavascriptInterface public void setDockData(String json) { LockService.saveData(MainActivity.this, json); }
        @JavascriptInterface public void setDockOverlay(boolean on) { LockService.saveEnabled(MainActivity.this, on); }
        @JavascriptInterface public boolean isDockOverlay() { return LockService.isEnabled(MainActivity.this); }
        @JavascriptInterface public boolean isHomeDefault() { return isDefaultHome(); }
        @JavascriptInterface public String getStatus() {
            try {
                JSONObject o = new JSONObject();
                o.put("write", Settings.System.canWrite(MainActivity.this)); o.put("notif", notifAccess()); o.put("a11y", a11yOn()); o.put("files", filesOk()); o.put("home", isDefaultHome());
                o.put("calendar", checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED);
                o.put("dnd", Build.VERSION.SDK_INT < 23 || ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void openSetup(String kind) {
            if ("notif".equals(kind)) go(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            else if ("a11y".equals(kind)) go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            else if ("dnd".equals(kind)) go(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            else if ("home".equals(kind)) go(new Intent(Settings.ACTION_HOME_SETTINGS));
            else if ("write".equals(kind)) go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
            else if ("files".equals(kind) && Build.VERSION.SDK_INT >= 30) go(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            else go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }

        /* ---- Ứng dụng đã cài ---- */
        @JavascriptInterface public String getApps() {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
            Collections.sort(l, new ResolveInfo.DisplayNameComparator(pm));
            JSONArray arr = new JSONArray();
            for (ResolveInfo r : l) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("label", r.loadLabel(pm).toString()); o.put("pkg", pkg); o.put("icon", icon(r.loadIcon(pm), 96));
                    arr.put(o);
                } catch (Exception e) { }
            }
            return arr.toString();
        }
        @JavascriptInterface public String getBrowsers() {
            PackageManager pm = getPackageManager();
            JSONArray arr = new JSONArray();
            try {
                Intent q = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
                List<ResolveInfo> l = pm.queryIntentActivities(q, PackageManager.MATCH_ALL);
                Collections.sort(l, new ResolveInfo.DisplayNameComparator(pm));
                java.util.HashSet<String> seen = new java.util.HashSet<String>();
                for (ResolveInfo r : l) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                    if (pm.getLaunchIntentForPackage(pkg) == null) continue;
                    try {
                        JSONObject o = new JSONObject();
                        o.put("label", r.loadLabel(pm).toString()); o.put("pkg", pkg); o.put("icon", icon(r.loadIcon(pm), 96));
                        arr.put(o);
                    } catch (Exception e) { }
                }
            } catch (Exception e) { }
            return arr.toString();
        }
        /** App con "Ổ mềm": giao diện HTML gọi lệnh, kết quả (JSON) trả lại qua window.omemCb(id, json). */
        @JavascriptInterface public void omem(final String op, final String arg, final int cb) {
            vn.omem.OmemApi.call(MainActivity.this, op, arg, new vn.omem.OmemCb() {
                @Override public void done(final String json) {
                    runOnUiThread(new Runnable() { public void run() {
                        if (web != null) web.evaluateJavascript("window.omemCb&&window.omemCb(" + cb + "," + JSONObject.quote(json) + ")", null);
                    } });
                }
            });
        }
        @JavascriptInterface public boolean hasPackage(String pkg) {
            try { return getPackageManager().getLaunchIntentForPackage(pkg) != null; } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void launchApp(String pkg) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) go(i);
        }
        /** Tên + biểu tượng của một ứng dụng đã cài (cho thẻ đa nhiệm). */
        @JavascriptInterface public String appInfo(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                JSONObject o = new JSONObject();
                o.put("label", pm.getApplicationLabel(ai).toString()); o.put("pkg", pkg); o.put("icon", icon(pm.getApplicationIcon(ai), 96));
                return o.toString();
            } catch (Exception e) { return ""; }
        }
        /** Tắt tiến trình nền của ứng dụng ngoài (cần quyền KILL_BACKGROUND_PROCESSES). */
        @JavascriptInterface public boolean killApp(String pkg) {
            if (pkg == null || pkg.equals(getPackageName())) return false;
            try {
                ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
                return true;
            } catch (Exception e) { return false; }
        }

        /* ---- Nhạc thật trong máy ---- */
        private MediaPlayer mp; private boolean ended = false;
        @JavascriptInterface public String getTracks() {
            JSONArray arr = new JSONArray();
            try {
                Cursor c = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION},
                    MediaStore.Audio.Media.IS_MUSIC + "!=0", null, MediaStore.Audio.Media.TITLE + " COLLATE NOCASE");
                if (c != null) { int n = 0;
                    while (c.moveToNext() && n++ < 3000) {
                        JSONObject o = new JSONObject(); o.put("id", c.getLong(0)); o.put("t", c.getString(1));
                        String a = c.getString(2); o.put("a", (a == null || a.equals("<unknown>")) ? "Không rõ nghệ sĩ" : a); o.put("dur", c.getLong(3) / 1000);
                        arr.put(o);
                    }
                    c.close(); }
            } catch (Exception e) { }
            return arr.toString();
        }
        @JavascriptInterface public synchronized boolean playTrack(long id) {
            try {
                if (mp != null) { mp.release(); mp = null; }
                ended = false; mp = new MediaPlayer();
                mp.setDataSource(MainActivity.this, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id));
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() { public void onCompletion(MediaPlayer m) { MainActivity.push("nativeEnded", "1"); } });
                mp.prepare(); mp.start(); return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public synchronized void pauseMusic() { try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Exception e) { } }
        @JavascriptInterface public synchronized void resumeMusic() { try { if (mp != null) mp.start(); } catch (Exception e) { } }
        @JavascriptInterface public synchronized void seekTo(int ms) { try { if (mp != null) mp.seekTo(ms); } catch (Exception e) { } }
        @JavascriptInterface public synchronized void stopMusic() { try { if (mp != null) { mp.release(); mp = null; } } catch (Exception e) { } }
        @JavascriptInterface public synchronized String getPlayerState() {
            try {
                JSONObject o = new JSONObject();
                if (mp != null) { o.put("playing", mp.isPlaying()); o.put("pos", mp.getCurrentPosition()); o.put("dur", mp.getDuration()); }
                o.put("ended", ended); ended = false; return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        /** Thông báo điều khiển nhạc + giữ ứng dụng sống khi phát nền. */
        @JavascriptInterface public void mediaNotify(String title, String artist, boolean playing) {
            Intent i = new Intent(MainActivity.this, MusicService.class).putExtra("title", title).putExtra("artist", artist).putExtra("playing", playing);
            try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i); } catch (Exception e) { }
        }
        @JavascriptInterface public void mediaStop() {
            try { startService(new Intent(MainActivity.this, MusicService.class).setAction("stop")); } catch (Exception e) { }
        }

        /* ---- Ảnh thật trong máy ---- */
        @JavascriptInterface public String getPhotos() {
            JSONArray arr = new JSONArray();
            try {
                /* Cột đường dẫn: RELATIVE_PATH (Android 10+) hoặc DATA, để phân loại Ảnh chụp / Ảnh chụp màn hình */
                String pathCol = Build.VERSION.SDK_INT >= 29 ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA;
                Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, pathCol}, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (c != null) { int n = 0;
                    while (c.moveToNext() && n++ < 500) { JSONObject o = new JSONObject(); o.put("id", c.getLong(0)); o.put("n", c.getString(1)); String pp = c.getString(2); o.put("p", pp == null ? "" : pp); arr.put(o); }
                    c.close(); }
            } catch (Exception e) { }
            return arr.toString();
        }
        @JavascriptInterface public String getPhotoData(long id, int size) {
            try {
                Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                Bitmap bm;
                if (Build.VERSION.SDK_INT >= 29) bm = getContentResolver().loadThumbnail(u, new Size(size, size), null);
                else {
                    BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                    InputStream in = getContentResolver().openInputStream(u); BitmapFactory.decodeStream(in, null, o); in.close();
                    o.inJustDecodeBounds = false; o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / size);
                    in = getContentResolver().openInputStream(u); bm = BitmapFactory.decodeStream(in, null, o); in.close();
                }
                ByteArrayOutputStream os = new ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 82, os);
                return "data:image/jpeg;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public String getMediaCounts() {
            JSONObject o = new JSONObject();
            String[] ks = {"img", "vid", "aud"};
            for (String k : ks) {
                int n = -1;
                try {
                    Cursor c = getContentResolver().query(mediaUri(k), new String[]{MediaStore.MediaColumns._ID}, null, null, null);
                    if (c != null) { n = c.getCount(); c.close(); }
                } catch (Exception e) { }
                try { o.put(k, n); } catch (Exception e) { }
            }
            return o.toString();
        }
        @JavascriptInterface public String listMedia(String kind) {
            JSONObject res = new JSONObject(); JSONArray arr = new JSONArray();
            try {
                boolean aud = "aud".equals(kind), vid = "vid".equals(kind);
                String order = aud ? MediaStore.Audio.Media.TITLE + " COLLATE NOCASE" : MediaStore.MediaColumns.DATE_ADDED + " DESC";
                String[] proj;
                if (aud) proj = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Audio.Media.DURATION};
                else if (vid) proj = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.Video.Media.DURATION};
                else proj = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED};
                Cursor c = getContentResolver().query(mediaUri(kind), proj, null, null, order);
                if (c == null) { res.put("error", "Không đọc được thư viện"); }
                else {
                    int n = 0;
                    while (c.moveToNext() && n++ < 10000) {
                        JSONObject o = new JSONObject();
                        o.put("id", c.getLong(0));
                        String nm = c.getString(1); o.put("n", nm == null ? "" : nm);
                        o.put("s", c.getLong(2)); o.put("m", c.getLong(3) * 1000L);
                        if (c.getColumnCount() > 4) o.put("d", c.getLong(4));
                        arr.put(o);
                    }
                    c.close();
                }
            } catch (SecurityException e) {
                try { res.put("error", "Chưa cấp quyền truy cập ảnh, video và âm thanh"); } catch (Exception x) { }
            } catch (Exception e) {
                try { res.put("error", "Không đọc được thư viện"); } catch (Exception x) { }
            }
            try { res.put("list", arr); } catch (Exception e) { }
            return res.toString();
        }
        @JavascriptInterface public String getMediaThumb(String kind, long id, int size) {
            try {
                Uri u = ContentUris.withAppendedId(mediaUri(kind), id);
                Bitmap bm;
                if (Build.VERSION.SDK_INT >= 29) bm = getContentResolver().loadThumbnail(u, new Size(size, size), null);
                else if ("vid".equals(kind)) bm = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                else bm = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                if (bm == null) return "";
                ByteArrayOutputStream os = new ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 80, os);
                return "data:image/jpeg;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public boolean openMedia(String kind, long id) {
            try {
                Uri u = ContentUris.withAppendedId(mediaUri(kind), id);
                String mime = getContentResolver().getType(u);
                if (mime == null) mime = "vid".equals(kind) ? "video/*" : "aud".equals(kind) ? "audio/*" : "image/*";
                go(new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                return true;
            } catch (Exception e) { return false; }
        }

        /* ---- Tệp thật ---- */
        @JavascriptInterface public String getRoots() {
            try {
                File r = Environment.getExternalStorageDirectory(); JSONObject o = new JSONObject();
                o.put("root", r.getAbsolutePath());
                o.put("pics", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath());
                o.put("dcim", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath());
                o.put("music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).getAbsolutePath());
                o.put("docs", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath());
                o.put("down", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String listDir(String path) {
            try {
                File dir = new File(path); JSONObject res = new JSONObject(); JSONArray arr = new JSONArray();
                File[] fs = dir.listFiles();
                if (fs == null) { res.put("error", filesOk() ? "Không đọc được thư mục này" : "Chưa cấp quyền truy cập tệp"); res.put("list", arr); return res.toString(); }
                Arrays.sort(fs, new Comparator<File>() { public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName()); } });
                for (File f : fs) {
                    if (f.getName().startsWith(".")) continue;
                    JSONObject o = new JSONObject(); o.put("n", f.getName()); o.put("d", f.isDirectory()); o.put("s", f.length()); o.put("m", f.lastModified()); arr.put(o);
                }
                res.put("list", arr); return res.toString();
            } catch (Exception e) { return "{\"error\":\"Lỗi đọc thư mục\",\"list\":[]}"; }
        }
        @JavascriptInterface public boolean shareFile(String path) {
            try {
                File f = new File(path);
                String mime = mimeOf(f.getName());
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                go(Intent.createChooser(i, "Chia sẻ"));
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public boolean renameFile(String path, String newName) {
            try {
                if (newName == null) return false;
                newName = newName.trim();
                if (newName.length() == 0 || newName.contains("/") || newName.startsWith(".")) return false;
                File f = new File(path), t = new File(f.getParentFile(), newName);
                if (t.exists()) return false;
                boolean ok = f.renameTo(t);
                if (ok) { try { sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(t))); } catch (Exception e) { } }
                return ok;
            } catch (Exception e) { return false; }
        }
        /** Lưu ảnh chụp (dataUrl JPEG) vào thư viện ảnh của máy, thư mục Pictures/Desktop Mini. Trả đường dẫn/URI, rỗng nếu lỗi. */
        @JavascriptInterface public String savePhoto(String dataUrl) {
            try {
                byte[] by = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
                String nm = "IMG_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date()) + ".jpg";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, nm);
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Desktop Mini");
                    v.put(MediaStore.Images.Media.IS_PENDING, 1);
                    Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (u == null) return "";
                    OutputStream os = getContentResolver().openOutputStream(u);
                    os.write(by); os.close();
                    v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(u, v, null, null);
                    return u.toString();
                }
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Desktop Mini"); dir.mkdirs();
                File f = new File(dir, nm);
                FileOutputStream os2 = new FileOutputStream(f); os2.write(by); os2.close();
                /* Đưa vào MediaStore ngay để có id (mở thẳng ảnh vừa chụp trong app Ảnh) */
                try {
                    ContentValues v2 = new ContentValues();
                    v2.put(MediaStore.Images.Media.DISPLAY_NAME, nm);
                    v2.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    v2.put(MediaStore.Images.Media.DATA, f.getAbsolutePath());
                    Uri u2 = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v2);
                    if (u2 != null) return u2.toString();
                } catch (Exception e2) { }
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                return f.getAbsolutePath();
            } catch (Exception e) { return ""; }
        }
        /** Chuyển tệp/thư mục vào thư mục khác. Trả "" nếu xong, ngược lại là lý do lỗi. */
        @JavascriptInterface public String moveFile(String src, String destDir) {
            try {
                File f = new File(src), d = new File(destDir);
                if (!f.exists()) return "Không tìm thấy tệp nguồn";
                if (!d.isDirectory()) return "Thư mục đích không hợp lệ";
                File t = new File(d, f.getName());
                if (t.exists()) return "Thư mục đích đã có tệp cùng tên";
                if (f.isDirectory()) {
                    String a1 = f.getCanonicalPath() + "/", b1 = d.getCanonicalPath() + "/";
                    if (b1.startsWith(a1)) return "Không thể chuyển thư mục vào chính nó";
                }
                boolean ok = f.renameTo(t);
                if (!ok && f.isFile()) {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    FileOutputStream out = new FileOutputStream(t);
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close(); in.close();
                    t.setLastModified(f.lastModified());
                    ok = f.delete();
                    if (!ok) t.delete();
                }
                if (!ok) return "Không chuyển được (khác bộ nhớ hoặc thiếu quyền)";
                try {
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                    sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(t)));
                } catch (Exception e) { }
                return "";
            } catch (Exception e) { return "Lỗi khi chuyển tệp"; }
        }
        @JavascriptInterface public boolean deleteFile(String path) {
            try {
                File f = new File(path);
                if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null && k.length > 0) return false; }
                boolean ok = f.delete();
                if (ok) { try { MediaStore.Files.getContentUri("external"); sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))); } catch (Exception e) { } }
                return ok;
            } catch (Exception e) { return false; }
        }
        /* ---- Mở mọi loại tệp: nhận diện MIME theo đuôi tệp, APK, giải nén ZIP, "Mở bằng…" ---- */
        private String extOf(String name) {
            String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
            int d = n.lastIndexOf('.');
            return d >= 0 ? n.substring(d + 1) : "";
        }
        private String mimeOf(String name) {
            String ext = extOf(name);
            String[][] X = {
                {"apk", "application/vnd.android.package-archive"},
                {"zip", "application/zip"}, {"rar", "application/vnd.rar"}, {"7z", "application/x-7z-compressed"},
                {"tar", "application/x-tar"}, {"gz", "application/gzip"}, {"tgz", "application/gzip"},
                {"doc", "application/msword"}, {"docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
                {"xls", "application/vnd.ms-excel"}, {"xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"},
                {"ppt", "application/vnd.ms-powerpoint"}, {"pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"},
                {"odt", "application/vnd.oasis.opendocument.text"}, {"ods", "application/vnd.oasis.opendocument.spreadsheet"}, {"odp", "application/vnd.oasis.opendocument.presentation"},
                {"pdf", "application/pdf"}, {"epub", "application/epub+zip"}, {"csv", "text/csv"}, {"txt", "text/plain"}, {"md", "text/plain"},
                {"log", "text/plain"}, {"json", "application/json"}, {"xml", "text/xml"}, {"html", "text/html"}, {"htm", "text/html"},
                {"vcf", "text/x-vcard"}, {"ics", "text/calendar"},
                {"mp3", "audio/mpeg"}, {"m4a", "audio/mp4"}, {"aac", "audio/aac"}, {"flac", "audio/flac"}, {"wav", "audio/x-wav"},
                {"ogg", "audio/ogg"}, {"opus", "audio/ogg"}, {"amr", "audio/amr"}, {"mid", "audio/midi"}, {"wma", "audio/x-ms-wma"},
                {"mp4", "video/mp4"}, {"m4v", "video/mp4"}, {"mkv", "video/x-matroska"}, {"webm", "video/webm"}, {"3gp", "video/3gpp"},
                {"avi", "video/x-msvideo"}, {"mov", "video/quicktime"}, {"flv", "video/x-flv"}, {"wmv", "video/x-ms-wmv"}, {"ts", "video/mp2t"},
                {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"png", "image/png"}, {"gif", "image/gif"}, {"webp", "image/webp"},
                {"bmp", "image/bmp"}, {"heic", "image/heic"}, {"svg", "image/svg+xml"}
            };
            for (String[] x : X) if (x[0].equals(ext)) return x[1];
            String m = ext.length() == 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            return m == null ? "*/*" : m;
        }
        /** Mở Uri bằng app phù hợp; thử đúng loại trước rồi mới thử mọi loại. Trả đúng/sai thật (không nuốt lỗi như go()). */
        private boolean viewUri(Uri u, String mime) {
            String[] tries = "*/*".equals(mime) ? new String[]{"*/*"} : new String[]{mime, "*/*"};
            for (String m : tries) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, m)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                } catch (Exception e) { }
            }
            return false;
        }
        /** Trả "" nếu mở được, ngược lại là lý do để hiện cho người dùng. */
        @JavascriptInterface public String openFileMsg(String path) {
            try {
                File f = new File(path);
                if (!f.exists()) return "Không tìm thấy tệp";
                if (f.isDirectory()) return "Đây là thư mục";
                String ext = extOf(f.getName());
                Uri u;
                try { u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f); }
                catch (Exception e) { return "Không đọc được tệp"; }
                if ("apk".equals(ext)) {
                    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                        go(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                        return "Hãy bật \"Cho phép từ nguồn này\" cho Desktop Mini rồi bấm lại tệp APK";
                    }
                    return viewUri(u, "application/vnd.android.package-archive") ? "" : "Không mở được trình cài đặt";
                }
                return viewUri(u, mimeOf(f.getName())) ? "" : "Máy chưa có ứng dụng nào mở được loại tệp ." + ext + " (thử \"Mở bằng…\")";
            } catch (Exception e) { return "Không mở được tệp"; }
        }
        @JavascriptInterface public boolean openFile(String path) { return openFileMsg(path).length() == 0; }
        /** "Mở bằng…": luôn hiện hộp chọn ứng dụng. */
        @JavascriptInterface public boolean openFileWith(String path) {
            try {
                File f = new File(path);
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mimeOf(f.getName())).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent c = Intent.createChooser(v, "Mở bằng");
                c.setClipData(android.content.ClipData.newRawUri("", u));
                c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(c);
                return true;
            } catch (Exception e) { return false; }
        }

        /** Giải nén ZIP vào thư mục cùng tên cạnh tệp (chạy nền). Xong gọi window.nativeZipDone(token, json). */
        @JavascriptInterface public void extractZipAsync(final String path, final String token) {
            new Thread(new Runnable() { public void run() {
                JSONObject res = new JSONObject();
                File out = null; boolean ok = false;
                try {
                    File z = new File(path), parent = z.getParentFile();
                    String base = z.getName(); int d = base.lastIndexOf('.'); if (d > 0) base = base.substring(0, d);
                    out = new File(parent, base); int k = 2;
                    while (out.exists()) { out = new File(parent, base + " " + k); k++; }
                    if (!out.mkdirs()) throw new java.io.IOException("Không tạo được thư mục đích");
                    ArrayList<String> made = new ArrayList<String>();
                    java.util.zip.ZipFile zf;
                    try { zf = new java.util.zip.ZipFile(z); }
                    catch (IllegalArgumentException e) { zf = new java.util.zip.ZipFile(z, java.nio.charset.StandardCharsets.ISO_8859_1); }
                    String root = out.getCanonicalPath() + File.separator;
                    int count = 0;
                    try {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
                        while (en.hasMoreElements()) {
                            java.util.zip.ZipEntry e = en.nextElement();
                            if (++count > 100000) throw new java.io.IOException("Tệp ZIP có quá nhiều mục");
                            File t = new File(out, e.getName());
                            String cp = t.getCanonicalPath();
                            if (!(cp + File.separator).startsWith(root)) continue; /* chặn zip-slip */
                            if (e.isDirectory()) { t.mkdirs(); continue; }
                            File pr = t.getParentFile(); if (pr != null) pr.mkdirs();
                            InputStream in = zf.getInputStream(e); FileOutputStream os = new FileOutputStream(t);
                            try { byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) os.write(buf, 0, n); }
                            finally { os.close(); in.close(); }
                            long tm = e.getTime(); if (tm > 0) t.setLastModified(tm);
                            made.add(t.getAbsolutePath());
                        }
                    } finally { zf.close(); }
                    ok = true;
                    try { android.media.MediaScannerConnection.scanFile(MainActivity.this, made.toArray(new String[0]), null, null); } catch (Exception e) { }
                    res.put("dir", out.getAbsolutePath()); res.put("n", made.size());
                } catch (Exception e) {
                    try { res.put("error", e.getMessage() == null ? "Tệp ZIP lỗi hoặc có mật khẩu" : e.getMessage()); } catch (Exception x) { }
                }
                if (!ok && out != null) rmTree(out);
                final String js = "window.nativeZipDone&&window.nativeZipDone(" + JSONObject.quote(token) + "," + JSONObject.quote(res.toString()) + ")";
                runOnUiThread(new Runnable() { public void run() { if (web != null) web.evaluateJavascript(js, null); } });
            }}).start();
        }
        private void rmTree(File f) {
            File[] k = f.listFiles();
            if (k != null) for (File c : k) rmTree(c);
            f.delete();
        }

        /** Gmail thật: mở app Gmail trên máy; không có thì thử app thư khác, cuối cùng mới mở web. */
        @JavascriptInterface public boolean openMail() {
            try {
                PackageManager pm = getPackageManager();
                Intent i = pm.getLaunchIntentForPackage("com.google.android.gm");
                if (i == null) {
                    Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL);
                    List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
                    for (ResolveInfo r : l) { if (!r.activityInfo.packageName.equals(getPackageName())) { i = pm.getLaunchIntentForPackage(r.activityInfo.packageName); if (i != null) break; } }
                }
                if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/"));
                go(i); return true;
            } catch (Exception e) { return false; }
        }

        /* ---- Tải xuống: toàn bộ tệp trong thư mục Download của máy (kể cả thư mục con), qua MediaStore ---- */
        @JavascriptInterface public String listDownloads() {
            JSONObject res = new JSONObject(); JSONArray arr = new JSONArray();
            try {
                Uri base = MediaStore.Files.getContentUri("external");
                String sel, args[];
                if (Build.VERSION.SDK_INT >= 29) { sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"; args = new String[]{"Download/%"}; }
                else { sel = MediaStore.MediaColumns.DATA + " LIKE ?"; args = new String[]{"%/Download/%"}; }
                sel += " AND " + MediaStore.Files.FileColumns.MEDIA_TYPE + " IS NOT NULL";
                Cursor c = getContentResolver().query(base, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA},
                    sel, args, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
                if (c == null) res.put("error", "Không đọc được thư mục Tải xuống");
                else {
                    int n = 0;
                    while (c.moveToNext() && n++ < 10000) {
                        String nm = c.getString(1); if (nm == null || nm.startsWith(".")) continue;
                        JSONObject o = new JSONObject(); o.put("id", c.getLong(0)); o.put("n", nm); o.put("s", c.getLong(2)); o.put("m", c.getLong(3) * 1000L);
                        String mt = c.getString(4); o.put("t", mt == null ? "" : mt); String dp = c.getString(5); o.put("p", dp == null ? "" : dp); arr.put(o);
                    }
                    c.close();
                }
            } catch (SecurityException e) {
                try { res.put("error", "Chưa cấp quyền truy cập tệp"); } catch (Exception x) { }
            } catch (Exception e) {
                try { res.put("error", "Không đọc được thư mục Tải xuống"); } catch (Exception x) { }
            }
            try { res.put("list", arr); } catch (Exception e) { }
            return res.toString();
        }
        @JavascriptInterface public boolean openDownload(long id, String mime) {
            try {
                Uri u = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id);
                return viewUri(u, (mime == null || mime.length() == 0) ? "*/*" : mime);
            } catch (Exception e) { return false; }
        }

        /* ---- Lịch thật của máy ---- */
        @JavascriptInterface public String getDeviceEvents(long start, long end) {
            JSONArray arr = new JSONArray();
            try {
                if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return "[]";
                Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(b, start); ContentUris.appendId(b, end);
                Cursor c = getContentResolver().query(b.build(), new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY}, null, null, CalendarContract.Instances.BEGIN + " ASC");
                if (c != null) { int n = 0;
                    while (c.moveToNext() && n++ < 500) {
                        JSONObject o = new JSONObject(); String t = c.getString(0); o.put("t", t == null ? "(Không tiêu đề)" : t);
                        o.put("s", c.getLong(1)); o.put("e", c.getLong(2)); o.put("all", c.getInt(3) == 1); arr.put(o);
                    }
                    c.close(); }
            } catch (Exception e) { }
            return arr.toString();
        }


        /* ---- Trình duyệt Gecko nhúng trong cửa sổ ---- */
        @JavascriptInterface public void gkShow(final float x, final float y, final float w, final float h) {
            runOnUiThread(new Runnable() { public void run() {
                try {
                    float d = getResources().getDisplayMetrics().density;
                    for (WebView v : ext.values()) v.setVisibility(View.GONE);
                    gkEnsure();
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, Math.round(w * d)), Math.max(1, Math.round(h * d)));
                    lp.leftMargin = Math.round(x * d); lp.topMargin = Math.round(y * d);
                    gkView.setLayoutParams(lp); gkView.setVisibility(View.VISIBLE);
                    gkPush();
                } catch (Throwable t) { gkToast("Không mở được trình duyệt: " + t); }
            }});
        }
        @JavascriptInterface public void gkHide() { runOnUiThread(new Runnable() { public void run() { gkHideNow(); } }); }
        @JavascriptInterface public void gkClose() {
            runOnUiThread(new Runnable() { public void run() {
                if (gkView == null) return;
                try { gkSession.close(); } catch (Exception e) { }
                try { root.removeView(gkView); } catch (Exception e) { }
                gkView = null; gkSession = null;
            }});
        }
        @JavascriptInterface public void gkGo(final String text) {
            runOnUiThread(new Runnable() { public void run() {
                try { gkEnsure(); String t = text == null ? "" : text.trim(); if (t.length() > 0) gkSession.loadUri(gkToUrl(t)); } catch (Throwable e) { }
            }});
        }
        @JavascriptInterface public void gkBack() { runOnUiThread(new Runnable() { public void run() { if (gkSession != null) gkSession.goBack(); } }); }
        @JavascriptInterface public void gkForward() { runOnUiThread(new Runnable() { public void run() { if (gkSession != null) gkSession.goForward(); } }); }
        @JavascriptInterface public void gkReload() { runOnUiThread(new Runnable() { public void run() { if (gkSession != null) gkSession.reload(); } }); }

        /* ---- Gmail / Lịch Google: trình duyệt nhúng trong cửa sổ ---- */
        @JavascriptInterface public void extShow(final String key, final String url, final float x, final float y, final float w, final float h) {
            runOnUiThread(new Runnable() { public void run() {
                float d = getResources().getDisplayMetrics().density;
                gkHideNow();
                for (java.util.Map.Entry<String, WebView> e : ext.entrySet()) if (!e.getKey().equals(key)) e.getValue().setVisibility(View.GONE);
                WebView v = extGet(key, url);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, Math.round(w * d)), Math.max(1, Math.round(h * d)));
                lp.leftMargin = Math.round(x * d); lp.topMargin = Math.round(y * d);
                v.setLayoutParams(lp); v.setVisibility(View.VISIBLE);
            }});
        }
        @JavascriptInterface public void extHide(final String key) {
            runOnUiThread(new Runnable() { public void run() {
                for (java.util.Map.Entry<String, WebView> e : ext.entrySet()) if (key == null || key.length() == 0 || e.getKey().equals(key)) e.getValue().setVisibility(View.GONE);
            }});
        }
        @JavascriptInterface public void extReload(final String key) {
            runOnUiThread(new Runnable() { public void run() { WebView v = ext.get(key); if (v != null) v.reload(); } });
        }
        @JavascriptInterface public void extGo(final String key, final String url) {
            runOnUiThread(new Runnable() { public void run() { WebView v = extGet(key, url); v.loadUrl(url); } });
        }
        @JavascriptInterface public void extBack(final String key) {
            runOnUiThread(new Runnable() { public void run() { WebView v = ext.get(key); if (v != null && v.canGoBack()) v.goBack(); } });
        }
    }
}
