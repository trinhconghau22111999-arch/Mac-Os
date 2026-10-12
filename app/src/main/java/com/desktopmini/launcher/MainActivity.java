package com.desktopmini.launcher;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
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
    private WebView web;
    private final HashMap<String, WebView> ext = new HashMap<String, WebView>();
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
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidLauncher");
        web.loadUrl("file:///android_asset/index.html");
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        IntentFilter f = new IntentFilter("com.desktopmini.launcher.MEDIA");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(media, f, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(media, f);
        immersive();
        askPermissions();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(media); } catch (Exception e) { }
        if (inst == this) inst = null;
        super.onDestroy();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Xin các quyền thường ngay lần mở đầu tiên sau khi cài. */
    private void askPermissions() {
        ArrayList<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_AUDIO); need.add(Manifest.permission.READ_MEDIA_IMAGES); need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT);
        need.add(Manifest.permission.READ_CALENDAR);
        ArrayList<String> miss = new ArrayList<String>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        if (!miss.isEmpty()) requestPermissions(miss.toArray(new String[0]), 7);
    }

    private boolean listed(String setting) {
        String v = Settings.Secure.getString(getContentResolver(), setting);
        return v != null && v.contains(getPackageName());
    }
    private boolean notifAccess() { return listed("enabled_notification_listeners"); }
    private boolean a11yOn() { return listed(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES); }
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
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !sp.getBoolean("af", false)) {
            sp.edit().putBoolean("af", true).apply();
            go(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); return;
        }
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }
    @Override protected void onResume() {
        super.onResume();
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        root.post(new Runnable() { public void run() { setupNext(); } });
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); web.evaluateJavascript("window.nativeHome&&window.nativeHome()", null); }
    @Override public void onBackPressed() { web.evaluateJavascript("window.nativeBack&&window.nativeBack()", null); }

    private void go(Intent i) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); try { startActivity(i); } catch (Exception e) { } }

    private WebView extGet(final String key, String url) {
        WebView v = ext.get(key);
        if (v == null) {
            v = new WebView(this);
            WebSettings s = v.getSettings();
            s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setUserAgentString(UA);
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
        @JavascriptInterface public int getBrightness() {
            try { return Math.round(Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) * 100f / 255f); }
            catch (Exception e) { return 60; }
        }
        @JavascriptInterface public boolean canWriteSettings() { return Settings.System.canWrite(MainActivity.this); }
        @JavascriptInterface public void requestWriteSettings() {
            go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
        @JavascriptInterface public void setBrightness(final int p) {
            final int v = Math.max(1, Math.min(255, Math.round(p * 255f / 100f)));
            if (Settings.System.canWrite(MainActivity.this)) {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
            } else {
                runOnUiThread(new Runnable() { public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes(); lp.screenBrightness = v / 255f; getWindow().setAttributes(lp);
                }});
            }
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
                return o.toString();
            } catch (Exception e) { return "{}"; }
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
                    if (Build.VERSION.SDK_INT >= 28 && w.getDisplayCutout() != null) top = Math.max(top, w.getDisplayCutout().getSafeInsetTop());
                }
                JSONObject o = new JSONObject(); o.put("top", Math.round(top / d)); o.put("bottom", Math.round(bottom / d)); return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        /* ---- Khóa màn hình, nguồn (qua dịch vụ trợ năng) ---- */
        @JavascriptInterface public boolean lockScreen() {
            if (LockService.inst != null && Build.VERSION.SDK_INT >= 28) return LockService.inst.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return false;
        }
        @JavascriptInterface public boolean powerMenu() {
            if (LockService.inst != null) return LockService.inst.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
            go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return false;
        }
        @JavascriptInterface public String getStatus() {
            try {
                JSONObject o = new JSONObject();
                o.put("write", Settings.System.canWrite(MainActivity.this)); o.put("notif", notifAccess()); o.put("a11y", a11yOn()); o.put("files", filesOk());
                o.put("calendar", checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void openSetup(String kind) {
            if ("notif".equals(kind)) go(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            else if ("a11y".equals(kind)) go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
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
        @JavascriptInterface public void launchApp(String pkg) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) go(i);
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
                Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME}, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (c != null) { int n = 0;
                    while (c.moveToNext() && n++ < 500) { JSONObject o = new JSONObject(); o.put("id", c.getLong(0)); o.put("n", c.getString(1)); arr.put(o); }
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
        @JavascriptInterface public boolean openFile(String path) {
            try {
                File f = new File(path);
                String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(f).toString());
                String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime == null ? "*/*" : mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                go(i); return true;
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

        /* ---- Gmail / Lịch Google: trình duyệt nhúng trong cửa sổ ---- */
        @JavascriptInterface public void extShow(final String key, final String url, final float x, final float y, final float w, final float h) {
            runOnUiThread(new Runnable() { public void run() {
                float d = getResources().getDisplayMetrics().density;
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
