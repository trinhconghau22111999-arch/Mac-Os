package com.desktopmini.launcher;

import android.Manifest;
import android.app.Activity;
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
            need.add(Manifest.permission.READ_MEDIA_AUDIO); need.add(Manifest.permission.READ_MEDIA_IMAGES); need.add(Manifest.permission.READ_MEDIA_VIDEO); need.add(Manifest.permission.POST_NOTIFICATIONS);
        } else need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (Build.VERSION.SDK_INT >= 31) need.add(Manifest.permission.BLUETOOTH_CONNECT);
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
        if (Build.VERSION.SDK_INT >= 23 && !((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted() && !sp.getBoolean("dnd", false)) {
            sp.edit().putBoolean("dnd", true).apply();
            go(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return;
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !sp.getBoolean("af", false)) {
            sp.edit().putBoolean("af", true).apply();
            go(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()))); return;
        }
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        askingRuntime = false;
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        root.post(new Runnable() { public void run() { setupNext(); } });
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 91) push("nativeDelResult", res == RESULT_OK ? "1" : "0");
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }
    @Override protected void onResume() {
        super.onResume();
        web.evaluateJavascript("window.nativeReload&&window.nativeReload()", null);
        if (!askingRuntime) root.post(new Runnable() { public void run() { setupNext(); } });
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



        /* ---- Ảnh: chia sẻ, lưu bản sao, xóa ---- */
        private File cacheImg(String dataUrl) throws Exception {
            byte[] by = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
            File dir = new File(getCacheDir(), "shared"); dir.mkdirs();
            File[] old = dir.listFiles(); if (old != null) for (File o : old) o.delete();
            File f = new File(dir, "anh_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream os = new FileOutputStream(f); os.write(by); os.close();
            return f;
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
        @JavascriptInterface public void cancelAlarm(int id) { AlarmReceiver.cancel(MainActivity.this, id); }
        @JavascriptInterface public void stopAlarmRinging() {
            try { startService(new Intent(MainActivity.this, AlarmService.class).setAction("stop")); } catch (Exception e) { }
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
        @JavascriptInterface public boolean hasPackage(String pkg) {
            try { return getPackageManager().getLaunchIntentForPackage(pkg) != null; } catch (Exception e) { return false; }
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
                String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(f).toString());
                String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_SEND).setType(mime == null ? "*/*" : mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
        @JavascriptInterface public boolean deleteFile(String path) {
            try {
                File f = new File(path);
                if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null && k.length > 0) return false; }
                boolean ok = f.delete();
                if (ok) { try { MediaStore.Files.getContentUri("external"); sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))); } catch (Exception e) { } }
                return ok;
            } catch (Exception e) { return false; }
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
                go(new Intent(Intent.ACTION_VIEW).setDataAndType(u, (mime == null || mime.length() == 0) ? "*/*" : mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                return true;
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
