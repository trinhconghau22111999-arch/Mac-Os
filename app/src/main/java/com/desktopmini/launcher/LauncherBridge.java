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
import androidx.webkit.WebViewAssetLoader;
import android.view.KeyEvent;
import android.view.MotionEvent;
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

/** Cầu nối: giao diện HTML điều khiển cài đặt và dữ liệu thật của máy (tách từ MainActivity). */
public class LauncherBridge {
    private final MainActivity act;
    LauncherBridge(MainActivity act) { this.act = act; }
        private AudioManager am() { return (AudioManager) act.getSystemService(Context.AUDIO_SERVICE); }

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
                final android.media.Ringtone r = android.media.RingtoneManager.getRingtone(act, u);
                if (r == null) return;
                if (Build.VERSION.SDK_INT >= 21) r.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage("alarm".equals(kind) ? android.media.AudioAttributes.USAGE_ALARM : "ring".equals(kind) ? android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE : android.media.AudioAttributes.USAGE_NOTIFICATION).build());
                r.play();
                act.web.postDelayed(new Runnable() { public void run() { try { r.stop(); } catch (Exception e) { } } }, 1500);
            } catch (Exception e) { }
        }
        @JavascriptInterface public int getBrightness() {
            try {
                float w = act.getWindow().getAttributes().screenBrightness;
                int raw = (w >= 0f && !Settings.System.canWrite(act)) ? Math.round(w * 255f)
                        : Settings.System.getInt(act.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
                raw = Math.max(1, Math.min(255, raw));
                return Math.max(1, Math.round((float) (100.0 * Math.pow(raw / 255.0, 0.5))));
            }
            catch (Exception e) { return 60; }
        }
        @JavascriptInterface public boolean canWriteSettings() { return Settings.System.canWrite(act); }
        @JavascriptInterface public void requestWriteSettings() {
            act.go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + act.getPackageName())));
        }
        @JavascriptInterface public void setBrightness(final int p) {
            final int pc = Math.max(1, Math.min(100, p));
            // Độ sáng cảm nhận không tuyến tính: dùng đường cong bình phương để thanh trượt về thấp là thật sự tối
            final int v = Math.max(1, Math.min(255, (int) Math.round(255.0 * (pc / 100.0) * (pc / 100.0))));
            if (Settings.System.canWrite(act)) {
                try {
                    Settings.System.putInt(act.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(act.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
                } catch (Exception ignored) { }
            }
            // Luôn đặt độ sáng theo cửa sổ để có hiệu lực ngay và xuống được mức tối nhất
            act.runOnUiThread(new Runnable() { public void run() {
                WindowManager.LayoutParams lp = act.getWindow().getAttributes(); lp.screenBrightness = Math.max(0.004f, v / 255f); act.getWindow().setAttributes(lp);
            }});
        }
        /** Có bàn phím thật (USB/Bluetooth/bàn phím rời) đang nối không? Dùng để không bật bàn phím ảo khi đã có bàn phím thật. */
        @JavascriptInterface public boolean hasHardKeyboard() {
            try {
                int[] ids = android.view.InputDevice.getDeviceIds();
                for (int id : ids) {
                    android.view.InputDevice d = android.view.InputDevice.getDevice(id);
                    if (d == null || d.isVirtual()) continue;
                    if ((d.getSources() & android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD
                        && d.getKeyboardType() == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;
                }
            } catch (Throwable t) { }
            try { return act.getResources().getConfiguration().keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS
                && act.getResources().getConfiguration().hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO; }
            catch (Throwable t) { return false; }
        }
        /** Ẩn bàn phím ảo ngay (khi đã có bàn phím thật). */
        @JavascriptInterface public void hideKeyboard() {
            act.runOnUiThread(new Runnable() { public void run() {
                try {
                    android.view.inputmethod.InputMethodManager m = (android.view.inputmethod.InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                    m.hideSoftInputFromWindow(act.getWindow().getDecorView().getWindowToken(), 0);
                } catch (Throwable t) { }
            }});
        }
        /** Ẩn bàn phím ảo khi con trỏ không còn trong ô nhập nào của trang chính. Chỉ ẩn nếu WebView chính đang giữ focus,
         *  để không đóng nhầm bàn phím của app nhúng (Gecko/WebView ngoài) vừa nhận focus. */
        @JavascriptInterface public void setHtmlEditing(boolean on) { act.htmlEditing = on; }
        @JavascriptInterface public void hideKeyboardIfIdle() {
            act.runOnUiThread(new Runnable() { public void run() {
                try {
                    if (act.web == null || !act.web.hasFocus()) return;
                    android.view.inputmethod.InputMethodManager m = (android.view.inputmethod.InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                    m.hideSoftInputFromWindow(act.web.getWindowToken(), 0);
                } catch (Throwable t) { }
            }});
        }
        @JavascriptInterface public void openWallpaper() {
            act.go(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Hình nền"));
        }
        @JavascriptInterface public void openSystemSettings() {
            act.go(new Intent(Settings.ACTION_SETTINGS));
        }
        @JavascriptInterface public String getWallpaper() {
            try {
                Drawable d = android.app.WallpaperManager.getInstance(act).getDrawable();
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
            try { return ((WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).isWifiEnabled(); } catch (Exception e) { return true; }
        }
        @JavascriptInterface public boolean isBluetoothOn() {
            try { BluetoothAdapter a = ((BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter(); return a != null && a.isEnabled(); }
            catch (Exception e) { return false; }
        }
        @JavascriptInterface public boolean isOnline() {
            try { ConnectivityManager cm = (ConnectivityManager) act.getSystemService(Context.CONNECTIVITY_SERVICE); return cm.getActiveNetwork() != null; }
            catch (Exception e) { return true; }
        }
        /* ---- Wi-Fi / Bluetooth thật: danh sách mạng, thiết bị đã kết nối và chưa kết nối ---- */
        private boolean locOk() {
            return act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        private boolean locServiceOn() {
            try {
                android.location.LocationManager lm = (android.location.LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
                return Settings.Secure.getInt(act.getContentResolver(), Settings.Secure.LOCATION_MODE, 0) != Settings.Secure.LOCATION_MODE_OFF;
            } catch (Exception e) { return true; }
        }
        private String cleanSsid(String s) {
            if (s == null) return "";
            if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            return "<unknown ssid>".equals(s) ? "" : s;
        }
        private WifiManager wifiMgr() { return (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE); }
        @JavascriptInterface public void wifiScan() { try { wifiMgr().startScan(); } catch (Exception e) { } }
        @JavascriptInterface public String getWifiInfo() {
            JSONObject res = new JSONObject(); JSONArray arr = new JSONArray();
            try {
                WifiManager wm = wifiMgr();
                boolean on = wm.isWifiEnabled(); res.put("on", on); res.put("loc", locOk()); res.put("locOn", locServiceOn());
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
            try { return ((BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter(); } catch (Exception e) { return null; }
        }
        @JavascriptInterface public boolean setBluetooth(boolean on) {
            if (Build.VERSION.SDK_INT >= 33) return false;
            try { BluetoothAdapter a = btAdapter(); if (a == null) return false; return on ? a.enable() : a.disable(); } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void btScan() {
            try {
                BluetoothAdapter a = btAdapter(); if (a == null || !a.isEnabled()) return;
                if (Build.VERSION.SDK_INT >= 31 && act.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    act.runOnUiThread(new Runnable() { public void run() { try { act.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 8); } catch (Exception e) { } } });
                    return;
                }
                if (!act.btRecv) {
                    IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND); f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                    if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(act.btRx, f, Context.RECEIVER_EXPORTED); else act.registerReceiver(act.btRx, f);
                    act.btRecv = true;
                }
                if (a.isDiscovering()) return; /* đang quét thì để yên, không hủy giữa chừng */
                act.btDiscovering = a.startDiscovery();
            } catch (Exception e) { }
        }
        private void btProxies(BluetoothAdapter a) {
            if (act.btProxReq) return; act.btProxReq = true;
            BluetoothProfile.ServiceListener l = new BluetoothProfile.ServiceListener() {
                public void onServiceConnected(int p, BluetoothProfile proxy) { synchronized (act.btProx) { act.btProx.put(p, proxy); } }
                public void onServiceDisconnected(int p) { synchronized (act.btProx) { act.btProx.remove(p); } }
            };
            try { a.getProfileProxy(act, l, BluetoothProfile.A2DP); } catch (Exception e) { }
            try { a.getProfileProxy(act, l, BluetoothProfile.HEADSET); } catch (Exception e) { }
        }
        @JavascriptInterface public String getBtInfo() {
            JSONObject res = new JSONObject();
            try {
                BluetoothAdapter a = btAdapter();
                if (a == null) { res.put("none", true); return res.toString(); }
                boolean on = a.isEnabled(); res.put("on", on); res.put("loc", locOk()); res.put("locOn", locServiceOn());
                if (Build.VERSION.SDK_INT >= 31) res.put("scanPerm", act.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED);
                try { res.put("scanning", a.isDiscovering()); } catch (Exception e) { }
                JSONArray paired = new JSONArray(), found = new JSONArray();
                if (on) {
                    btProxies(a);
                    java.util.HashSet<String> cs = new java.util.HashSet<String>();
                    synchronized (act.btProx) { for (BluetoothProfile p : act.btProx.values()) { try { for (BluetoothDevice d : p.getConnectedDevices()) cs.add(d.getAddress()); } catch (Exception e) { } } }
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
                    synchronized (act.btFound) { for (JSONObject o : act.btFound.values()) if (!bonded.contains(o.optString("addr"))) found.put(o); }
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
            act.go(i);
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
                act.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                o.put("sw", Math.min(dm.widthPixels, dm.heightPixels));
                o.put("sh", Math.max(dm.widthPixels, dm.heightPixels)); o.put("dpi", dm.densityDpi);
                try {
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    ((android.app.ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
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
            try { return ((android.os.PowerManager) act.getSystemService(Context.POWER_SERVICE)).isPowerSaveMode(); } catch (Exception e) { return false; }
        }
        /**
         * Bật/tắt tiết kiệm pin. Android chỉ cho ghi cài đặt này khi ứng dụng có quyền WRITE_SECURE_SETTINGS
         * (cấp một lần bằng adb); chưa có thì mở trang Tiết kiệm pin của hệ thống và trả về false.
         */
        @JavascriptInterface public boolean setPowerSave(boolean on) {
            try {
                Settings.Global.putInt(act.getContentResolver(), "low_power", on ? 1 : 0);
                return true;
            } catch (Exception e) { }
            try { act.go(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)); } catch (Exception e) { act.go(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)); }
            return false;
        }
        /** Tổng mili giây màn hình đã sáng trong hôm nay (OnTime trên thanh menu). */
        @JavascriptInterface public long getScreenOnMs() { return ScreenTime.todayMs(act); }
        /** RAM đang dùng / tổng RAM của máy (byte), cho thanh menu phía trên. */
        @JavascriptInterface public String getRam() {
            try {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                ((android.app.ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
                JSONObject o = new JSONObject(); o.put("total", mi.totalMem); o.put("free", mi.availMem); return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public String getBattery() {
            try {
                Intent b = act.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100), st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                JSONObject o = new JSONObject(); o.put("level", lv / (float) sc);
                o.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL); return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        /** Khoảng đệm an toàn (lỗ camera, thanh điều hướng) tính bằng dp. */
        @JavascriptInterface public String getInsets() {
            try {
                float d = act.getResources().getDisplayMetrics().density; int top = 0, bottom = 0;
                WindowInsets w = act.getWindow().getDecorView().getRootWindowInsets();
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
            File dir = new File(act.getCacheDir(), "shared"); dir.mkdirs();
            File[] old = dir.listFiles(); if (old != null) for (File o : old) o.delete();
            File f = new File(dir, "anh_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream os = new FileOutputStream(f); os.write(by); os.close();
            return f;
        }
        /** Chia sẻ bản ghi âm (dataUrl base64) qua bảng chọn ứng dụng của Android. */
        @JavascriptInterface public boolean shareAudio(String dataUrl, String name, String mime) {
            try {
                byte[] by = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
                File dir = new File(act.getCacheDir(), "shared"); dir.mkdirs();
                File[] old = dir.listFiles(); if (old != null) for (File o : old) o.delete();
                String base = (name == null || name.length() == 0) ? "ghi_am" : name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
                String type = (mime == null || mime.length() == 0) ? "audio/*" : mime.split(";")[0];
                File f = new File(dir, base + (type.contains("mp4") ? ".m4a" : ".webm"));
                FileOutputStream os = new FileOutputStream(f); os.write(by); os.close();
                Uri u = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                i.setClipData(android.content.ClipData.newRawUri("", u));
                act.go(Intent.createChooser(i, "Chia sẻ bản ghi").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                return true;
            } catch (Exception e) { return false; }
        }
        /** pkg rỗng = mở bảng chọn ứng dụng của Android. dataUrl rỗng = chia sẻ ảnh gốc theo id. */
        @JavascriptInterface public boolean shareImage(String pkg, long id, String dataUrl) {
            try {
                Uri u;
                if (dataUrl != null && dataUrl.length() > 0) u = FileProvider.getUriForFile(act, act.getPackageName() + ".files", cacheImg(dataUrl));
                else u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                Intent i = new Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                i.setClipData(android.content.ClipData.newRawUri("", u));
                if (pkg != null && pkg.length() > 0) {
                    i.setPackage(pkg);
                    try { act.startActivity(i); return true; } catch (Exception e) { i.setPackage(null); }
                }
                act.go(Intent.createChooser(i, "Chia sẻ ảnh").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public boolean saveImage(String dataUrl) {
            act.invalidatePhotos();
            try {
                byte[] by = Base64.decode(dataUrl.substring(dataUrl.indexOf(',') + 1), Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "DesktopMini_" + System.currentTimeMillis() + ".jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DesktopMini");
                Uri u = act.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                OutputStream os = act.getContentResolver().openOutputStream(u); os.write(by); os.close();
                return true;
            } catch (Exception e) { return false; }
        }
        /** 1 = đã xóa, 2 = đang chờ người dùng xác nhận trong hộp thoại hệ thống, 0 = thất bại. */
        @JavascriptInterface public int deletePhoto(long id) {
            act.invalidatePhotos();
            final Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
            final String tid = TrashStore.trashUri(act, u);   /* chép vào Thùng rác trước khi xóa */
            try { int dr = act.getContentResolver().delete(u, null, null) > 0 ? 1 : 0; if (dr == 0) TrashStore.drop(act, tid); return dr; }
            catch (final SecurityException se) {
                if (Build.VERSION.SDK_INT >= 30) {
                    act.runOnUiThread(new Runnable() { public void run() {
                        try {
                            android.app.PendingIntent pi = MediaStore.createDeleteRequest(act.getContentResolver(), Collections.singletonList(u));
                            act.startIntentSenderForResult(pi.getIntentSender(), 91, null, 0, 0, 0);
                        } catch (Exception e) { act.push("nativeDelResult", "0"); }
                    }});
                    return 2;
                }
                if (Build.VERSION.SDK_INT >= 29 && se instanceof android.app.RecoverableSecurityException) {
                    final android.app.RecoverableSecurityException rse = (android.app.RecoverableSecurityException) se;
                    act.runOnUiThread(new Runnable() { public void run() {
                        try { act.startIntentSenderForResult(rse.getUserAction().getActionIntent().getIntentSender(), 91, null, 0, 0, 0); }
                        catch (Exception e) { act.push("nativeDelResult", "0"); }
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
                Uri u = ContentUris.withAppendedId(act.mediaUri(kind), id);
                String tid = TrashStore.trashUri(act, u);   /* chép vào Thùng rác trước khi xóa */
                try { if (act.getContentResolver().delete(u, null, null) <= 0) { fail++; TrashStore.drop(act, tid); } }
                catch (SecurityException se) { pend.add(u); }
                catch (Exception e) { fail++; }
            }
            if (pend.isEmpty()) return fail == 0 ? 1 : 0;
            if (Build.VERSION.SDK_INT >= 30) {
                act.runOnUiThread(new Runnable() { public void run() {
                    try {
                        android.app.PendingIntent pi = MediaStore.createDeleteRequest(act.getContentResolver(), pend);
                        act.startIntentSenderForResult(pi.getIntentSender(), 91, null, 0, 0, 0);
                    } catch (Exception e) { act.push("nativeDelResult", "0"); }
                }});
                return 2;
            }
            return 0;
        }

        /* ---- Độ sáng thấp nhất khi không dùng ---- */
        /** Bật/tắt và đặt số phút không chạm trước khi hạ xuống độ sáng thấp nhất. Không cần quyền hệ thống (chỉ đổi độ sáng của cửa sổ). */
        @JavascriptInterface public boolean setIdleDim(boolean on, int min) {
            act.idleDimMs = Math.max(1, min) * 60000L; act.idleDimOn = on;
            act.lastTouchAt = android.os.SystemClock.elapsedRealtime();
            if (!on) act.runOnUiThread(new Runnable() { public void run() { act.undimIdle(); } });
            return true;
        }

        /* ---- Tự xoay màn hình (cài đặt thật của máy) ---- */
        @JavascriptInterface public boolean isAutoRotate() {
            try { return Settings.System.getInt(act.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION) == 1; } catch (Exception e) { return false; }
        }
        /** Cần quyền "Sửa cài đặt hệ thống"; chưa có thì mở trang cấp quyền và trả về false. */
        @JavascriptInterface public boolean setAutoRotate(boolean on) {
            if (!Settings.System.canWrite(act)) {
                act.go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + act.getPackageName()))); return false;
            }
            try {
                if (!on) {
                    int r = act.getWindowManager().getDefaultDisplay().getRotation();
                    Settings.System.putInt(act.getContentResolver(), Settings.System.USER_ROTATION, r);
                }
                Settings.System.putInt(act.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
                return true;
            } catch (Exception e) { return false; }
        }

        /* ---- Báo thức thật (chạy cả khi app đóng) ---- */
        @JavascriptInterface public String getAlarmSounds() {
            JSONArray arr = new JSONArray();
            try {
                JSONObject d = new JSONObject(); d.put("name", "Mặc định");
                d.put("uri", android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM).toString()); arr.put(d);
                android.media.RingtoneManager rm = new android.media.RingtoneManager(act);
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
            act.runOnUiThread(new Runnable() { public void run() {
                try {
                    if (act.previewTone != null) act.previewTone.stop();
                    act.previewTone = android.media.RingtoneManager.getRingtone(act, Uri.parse(uri));
                    if (act.previewTone == null) return;
                    if (Build.VERSION.SDK_INT >= 21) act.previewTone.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build());
                    act.previewTone.play();
                    act.web.postDelayed(new Runnable() { public void run() { try { if (act.previewTone != null) act.previewTone.stop(); } catch (Exception e) { } } }, 4000);
                } catch (Exception e) { }
            }});
        }
        @JavascriptInterface public void stopPreview() {
            act.runOnUiThread(new Runnable() { public void run() { try { if (act.previewTone != null) act.previewTone.stop(); } catch (Exception e) { } } });
        }
        @JavascriptInterface public boolean setAlarm(int id, int hour, int min, String uri, boolean repeat, String label) {
            return AlarmReceiver.schedule(act, id, hour, min, uri, repeat, label, true);
        }
        @JavascriptInterface public String getAlarmError() { return AlarmReceiver.lastError; }
        @JavascriptInterface public void cancelAlarm(int id) { AlarmReceiver.cancel(act, id); }
        @JavascriptInterface public void stopAlarmRinging() {
            try { act.startService(new Intent(act, AlarmService.class).setAction("stop")); } catch (Exception e) { }
        }

        /* ---- Khóa màn hình, nguồn (qua dịch vụ trợ năng) ---- */
        /** Tắt màn hình ngay. Dùng quyền quản trị thiết bị (chỉ "khóa màn hình"); Android 9+ có thể dùng Trợ năng thay thế. */
        @JavascriptInterface public boolean lockScreen() {
            try {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) act.getSystemService(Context.DEVICE_POLICY_SERVICE);
                android.content.ComponentName cn = new android.content.ComponentName(act, AdminReceiver.class);
                if (dpm.isAdminActive(cn)) { dpm.lockNow(); return true; }
                if (LockService.inst != null && Build.VERSION.SDK_INT >= 28) return LockService.inst.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                act.go(new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Cho phép Desktop Mini tắt màn hình khi bạn bấm Tắt máy. Quyền này chỉ dùng để khóa màn hình."));
            } catch (Exception e) { }
            return false;
        }
        @JavascriptInterface public boolean powerMenu() {
            if (LockService.inst != null) return LockService.inst.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
            act.go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return false;
        }
        /* ---- Dock nổi trên app khác (qua dịch vụ trợ năng) ---- */
        @JavascriptInterface public void setDockData(String json) { LockService.saveData(act, json); }
        @JavascriptInterface public void setDockOverlay(boolean on) { LockService.saveEnabled(act, on); }
        @JavascriptInterface public boolean isDockOverlay() { return LockService.isEnabled(act); }
        @JavascriptInterface public void setDockPrefs(String ics, boolean magnify, boolean autoHide) { LockService.setDockPrefs(act, ics, magnify, autoHide); }
        @JavascriptInterface public void setDockBase(int px) { LockService.setDockBase(act, px); }
        /** Thứ tự icon đã ghim trên Dock (người dùng sắp xếp): lưu bên Android để mở lại app vẫn giữ nguyên. */
        @JavascriptInterface public String getDockOrder() { return LockService.getDockOrder(act); }
        @JavascriptInterface public void setDockOrder(String json) { LockService.setDockOrder(act, json); }
        /** Mép trên Dock nổi tính bằng px CSS theo toạ độ màn hình thật (vùng nhìn của trang có thể ngắn hơn màn hình, nên trang không tự đo được). */
        @JavascriptInterface public float dockTopCss() {
            try {
                android.graphics.Point p = new android.graphics.Point(); act.getWindowManager().getDefaultDisplay().getRealSize(p);
                float d = act.getResources().getDisplayMetrics().density;
                return (p.y - Math.round(LockService.DOCK_BAR_DP * d)) / d;
            } catch (Throwable t) { return 0f; }
        }
        @JavascriptInterface public boolean dockOverlayLive() { return LockService.isEnabled(act) && LockService.isRunning(); }
        @JavascriptInterface public void setBarLock(boolean on) { LockService.saveBarLock(act, on); }
        @JavascriptInterface public boolean isBarLock() { return LockService.isBarLock(act); }
        @JavascriptInterface public void setMenuBar(boolean on) { LockService.saveMenuBar(act, on); }
        @JavascriptInterface public boolean isMenuBar() { return LockService.isMenuBar(act); }
        @JavascriptInterface public boolean isMenuBarActive() { return LockService.inst != null && LockService.isMenuBar(act); }
        @JavascriptInterface public void setBarInfo(String app) { LockService.setBarInfo(app); }
        @JavascriptInterface public void setBarHide(boolean hide) { LockService.setBarHide(hide); }
        @JavascriptInterface public void setBarGeom(int dp) { LockService.setBarGeom(dp); }
        @JavascriptInterface public void setBarWall(String src) { LockService.setWall(src); }
        @JavascriptInterface public boolean isHomeDefault() { return act.isDefaultHome(); }
        @JavascriptInterface public String getStatus() {
            try {
                JSONObject o = new JSONObject();
                o.put("write", Settings.System.canWrite(act)); o.put("notif", act.notifAccess()); o.put("a11y", act.a11yOn()); o.put("files", act.filesOk()); o.put("home", act.isDefaultHome());
                o.put("calendar", act.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED);
                o.put("dnd", Build.VERSION.SDK_INT < 23 || ((android.app.NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE)).isNotificationPolicyAccessGranted());
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void openSetup(String kind) {
            if ("notif".equals(kind)) act.go(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            else if ("a11y".equals(kind)) act.go(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            else if ("dnd".equals(kind)) act.go(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
            else if ("home".equals(kind)) act.go(new Intent(Settings.ACTION_HOME_SETTINGS));
            else if ("write".equals(kind)) act.go(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + act.getPackageName())));
            else if ("files".equals(kind) && Build.VERSION.SDK_INT >= 30) act.go(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + act.getPackageName())));
            else act.go(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + act.getPackageName())));
        }

        /* ---- Ứng dụng đã cài ---- */
        @JavascriptInterface public String getApps() {
            String c = act.appsCache; if (c != null) return c;
            return buildApps();
        }
        private synchronized String buildApps() {
            if (act.appsCache != null) return act.appsCache;
            String r = buildAppsNow(); act.appsCache = r; act.appsVer = System.currentTimeMillis(); return r;
        }
        @JavascriptInterface public long getAppsVersion() { return act.appsVer; }
        String buildAppsNow() {
            PackageManager pm = act.getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
            Collections.sort(l, new ResolveInfo.DisplayNameComparator(pm));
            JSONArray arr = new JSONArray();
            for (ResolveInfo r : l) {
                String pkg = r.activityInfo.packageName;
                if (pkg.equals(act.getPackageName())) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("label", r.loadLabel(pm).toString()); o.put("pkg", pkg); o.put("icon", act.icon(r.loadIcon(pm), 96));
                    arr.put(o);
                } catch (Exception e) { }
            }
            return arr.toString();
        }
        @JavascriptInterface public String getBrowsers() {
            PackageManager pm = act.getPackageManager();
            JSONArray arr = new JSONArray();
            try {
                Intent q = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
                List<ResolveInfo> l = pm.queryIntentActivities(q, PackageManager.MATCH_ALL);
                Collections.sort(l, new ResolveInfo.DisplayNameComparator(pm));
                java.util.HashSet<String> seen = new java.util.HashSet<String>();
                for (ResolveInfo r : l) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(act.getPackageName()) || !seen.add(pkg)) continue;
                    if (pm.getLaunchIntentForPackage(pkg) == null) continue;
                    try {
                        JSONObject o = new JSONObject();
                        o.put("label", r.loadLabel(pm).toString()); o.put("pkg", pkg); o.put("icon", act.icon(r.loadIcon(pm), 96));
                        arr.put(o);
                    } catch (Exception e) { }
                }
            } catch (Exception e) { }
            return arr.toString();
        }
        /** Nút chụp ảnh màn hình: chụp xong gọi window.shotDone(true/false). */
        @JavascriptInterface public void takeScreenshot() { act.runOnUiThread(new Runnable() { public void run() { act.captureScreen(); } }); }
        /** Tìm tệp theo tên trong máy; kết quả (JSON) trả về qua window.fsCb(id, json). */
        @JavascriptInterface public void searchFiles(final String q, final int cb) {
            new Thread(new Runnable() { public void run() {
                final String json = act.findFiles(q).toString();
                act.runOnUiThread(new Runnable() { public void run() {
                    if (act.web != null) act.web.evaluateJavascript("window.fsCb&&window.fsCb(" + cb + "," + JSONObject.quote(json) + ")", null);
                } });
            } }).start();
        }
        /** Nút AI: có dịch vụ nhận dạng giọng nói trên máy không. */
        @JavascriptInterface public boolean aiAvailable() { return act.aiAvailable(); }
        /** Nút AI: bắt đầu nghe lệnh; kết quả / mức âm / lỗi trả về qua window.aiVoice(obj). */
        @JavascriptInterface public void aiListen() { act.runOnUiThread(new Runnable() { public void run() { act.aiListen(); } }); }
        /** Nút AI: nói xong sớm (nhận dạng luôn phần đã nói). */
        @JavascriptInterface public void aiStop() { act.runOnUiThread(new Runnable() { public void run() { act.aiStop(); } }); }
        /** Nút AI: hủy nghe, không trả kết quả. */
        @JavascriptInterface public void aiCancel() { act.runOnUiThread(new Runnable() { public void run() { act.aiCancel(); } }); }
        /** Mở app "Ổ mềm" (Drive 3 hồ sơ: Ảnh / Video / Tệp) ở màn hình riêng; thoát thì quay về Desktop Mini. */
        @JavascriptInterface public void openDrive3() { act.runOnUiThread(new Runnable() { public void run() { act.openDriveHome(); } }); }
        /** Mở app con "Duyệt web" (trình duyệt mặc định của app); url có thể rỗng. */
        @JavascriptInterface public void openWebBrowser(String url) { act.openWeb(url); }
        /** Mở app con "YouTube" (chỉ YouTube, không thanh địa chỉ). */
        @JavascriptInterface public void openYouTube() { act.openYt(); }
        /** Nút mic trên thanh tìm kiếm Google của Desktop. */
        @JavascriptInterface public void startVoiceSearch() { act.runOnUiThread(new Runnable() { public void run() { act.voiceKey = null; act.launchVoiceSearch(); } }); }
        /** Mở app con "Gõ văn bản" (trình soạn thảo kiểu Word). */
        @JavascriptInterface public void openTyping() { act.openTy(); }
        @JavascriptInterface public boolean hasPackage(String pkg) {
            try { return act.getPackageManager().getLaunchIntentForPackage(pkg) != null; } catch (Exception e) { return false; }
        }
        @JavascriptInterface public void launchApp(String pkg) {
            Intent i = act.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) act.go(i);
        }
        /** Các app ngoài đã mở từ Dock nổi (JSON mảng pkg, mới nhất trước); đọc xong thì xóa để không lặp. */
        @JavascriptInterface public String takeExtLaunched() { return LockService.takeExtLaunched(act); }
        /** Tên + biểu tượng của một ứng dụng đã cài (cho thẻ đa nhiệm). */
        @JavascriptInterface public String appInfo(String pkg) {
            try {
                PackageManager pm = act.getPackageManager();
                android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                JSONObject o = new JSONObject();
                o.put("label", pm.getApplicationLabel(ai).toString()); o.put("pkg", pkg); o.put("icon", act.icon(pm.getApplicationIcon(ai), 96));
                return o.toString();
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public void clearExtDock() { LockService.forgetAllExt(); }
        /** Tắt tiến trình nền của ứng dụng ngoài (cần quyền KILL_BACKGROUND_PROCESSES). */
        @JavascriptInterface public boolean killApp(String pkg) {
            if (pkg == null || pkg.equals(act.getPackageName())) return false;
            LockService.forgetExt(pkg);   /* gỡ icon app này khỏi Dock nổi dù có tắt được tiến trình hay không */
            try {
                ((ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE)).killBackgroundProcesses(pkg);
                return true;
            } catch (Exception e) { return false; }
        }

        /* ---- Nhạc thật trong máy ---- */
        private MediaPlayer mp; private boolean ended = false;
        @JavascriptInterface public String getTracks() {
            JSONArray arr = new JSONArray();
            try {
                Cursor c = act.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
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
                mp.setDataSource(act, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id));
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
            Intent i = new Intent(act, MusicService.class).putExtra("title", title).putExtra("artist", artist).putExtra("playing", playing);
            try { if (Build.VERSION.SDK_INT >= 26) act.startForegroundService(i); else act.startService(i); } catch (Exception e) { }
        }
        @JavascriptInterface public void mediaStop() {
            try { act.startService(new Intent(act, MusicService.class).setAction("stop")); } catch (Exception e) { }
        }

        /* ---- Ảnh thật trong máy ---- */
        /** Ép quét lại bộ nhớ tìm ảnh/video/nhạc chưa có trong thư viện (gọi khi mở app Ảnh/Tệp). */
        @JavascriptInterface public void rescanMedia() { act.scanMedia(true); }
        @JavascriptInterface public String getPhotos() {
            String pc = act.photosCache; if (pc != null) return pc;
            final int gen = act.photosGen;
            JSONArray arr = new JSONArray();
            try {
                /* Cột đường dẫn: RELATIVE_PATH (Android 10+) hoặc DATA, để phân loại Ảnh chụp / Ảnh chụp màn hình */
                String pathCol = Build.VERSION.SDK_INT >= 29 ? MediaStore.Images.Media.RELATIVE_PATH : MediaStore.Images.Media.DATA;
                Cursor c = act.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, pathCol,
                        MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_MODIFIED},
                    null, null, MediaStore.Images.Media.DATE_ADDED + " DESC");
                if (c != null) {
                    /* Mới nhất lên đầu: lấy mốc thời gian lớn nhất (lúc chụp / lúc thêm / lúc sửa) rồi sắp giảm dần, sau đó mới cắt 500 ảnh */
                    final java.util.ArrayList<long[]> rows = new java.util.ArrayList<long[]>();
                    final java.util.ArrayList<String[]> info = new java.util.ArrayList<String[]>();
                    while (c.moveToNext()) {
                        long t = Math.max(c.getLong(3), Math.max(c.getLong(4), c.getLong(5)) * 1000L);
                        rows.add(new long[]{c.getLong(0), t});
                        String pp = c.getString(2);
                        info.add(new String[]{c.getString(1), pp == null ? "" : pp});
                    }
                    c.close();
                    Integer[] idx = new Integer[rows.size()];
                    for (int i = 0; i < idx.length; i++) idx[i] = i;
                    java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
                        @Override public int compare(Integer x, Integer y) { return Long.compare(rows.get(y)[1], rows.get(x)[1]); }
                    });
                    for (int k = 0; k < idx.length && k < 50000; k++) {
                        int i = idx[k];
                        JSONObject o = new JSONObject();
                        o.put("id", rows.get(i)[0]); o.put("n", info.get(i)[0]); o.put("p", info.get(i)[1]); o.put("t", rows.get(i)[1]);
                        arr.put(o);
                    }
                }
            } catch (Exception e) { }
            String out = arr.toString();
            /* Chỉ nhớ khi đã có bộ theo dõi báo thay đổi, để danh sách luôn mới */
            if (act.mediaWatching && arr.length() > 0 && gen == act.photosGen) act.photosCache = out;
            return out;
        }
        @JavascriptInterface public String getPhotoData(long id, int size) {
            try {
                final boolean cacheable = size <= 400;
                final String ck = id + "_" + size;
                if (cacheable) {
                    String hit = act.thumbMem.get(ck); if (hit != null) return hit;
                    File tf = new File(new File(act.getCacheDir(), "thumbs"), ck + ".jpg");
                    if (tf.isFile() && tf.length() > 0) {
                        try {
                            java.io.FileInputStream fi = new java.io.FileInputStream(tf); byte[] bb = new byte[(int) tf.length()];
                            int off = 0; while (off < bb.length) { int r = fi.read(bb, off, bb.length - off); if (r < 0) break; off += r; } fi.close();
                            if (off == bb.length) { String d = "data:image/jpeg;base64," + Base64.encodeToString(bb, Base64.NO_WRAP); act.thumbMem.put(ck, d); return d; }
                        } catch (Exception e) { }
                    }
                }
                Uri u = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                Bitmap bm;
                if (Build.VERSION.SDK_INT >= 29) bm = act.getContentResolver().loadThumbnail(u, new Size(size, size), null);
                else {
                    BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                    InputStream in = act.getContentResolver().openInputStream(u); BitmapFactory.decodeStream(in, null, o); in.close();
                    o.inJustDecodeBounds = false; o.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / size);
                    in = act.getContentResolver().openInputStream(u); bm = BitmapFactory.decodeStream(in, null, o); in.close();
                }
                ByteArrayOutputStream os = new ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 82, os);
                byte[] jb = os.toByteArray();
                String res = "data:image/jpeg;base64," + Base64.encodeToString(jb, Base64.NO_WRAP);
                if (cacheable) {
                    act.thumbMem.put(ck, res);
                    try {
                        File td = new File(act.getCacheDir(), "thumbs"); td.mkdirs();
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(new File(td, ck + ".jpg")); fo.write(jb); fo.close();
                    } catch (Exception e) { }
                }
                return res;
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public String getMediaCounts() {
            JSONObject o = new JSONObject();
            String[] ks = {"img", "vid", "aud"};
            for (String k : ks) {
                int n = -1;
                try {
                    Cursor c = act.getContentResolver().query(act.mediaUri(k), new String[]{MediaStore.MediaColumns._ID}, null, null, null);
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
                /* thêm cột đường dẫn ở cuối để kéo tệp ra màn hình desktop được (mở/đổi tên theo đường dẫn) */
                proj = java.util.Arrays.copyOf(proj, proj.length + 1); proj[proj.length - 1] = MediaStore.MediaColumns.DATA;
                Cursor c = act.getContentResolver().query(act.mediaUri(kind), proj, null, null, order);
                if (c == null) { res.put("error", "Không đọc được thư viện"); }
                else {
                    int n = 0;
                    while (c.moveToNext() && n++ < 10000) {
                        JSONObject o = new JSONObject();
                        o.put("id", c.getLong(0));
                        String nm = c.getString(1); o.put("n", nm == null ? "" : nm);
                        o.put("s", c.getLong(2)); o.put("m", c.getLong(3) * 1000L);
                        if (c.getColumnCount() > 5) o.put("d", c.getLong(4));
                        try { String pth = c.getString(c.getColumnCount() - 1); if (pth != null) o.put("p", pth); } catch (Exception ex) { }
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
                Uri u = ContentUris.withAppendedId(act.mediaUri(kind), id);
                Bitmap bm;
                if (Build.VERSION.SDK_INT >= 29) bm = act.getContentResolver().loadThumbnail(u, new Size(size, size), null);
                else if ("vid".equals(kind)) bm = MediaStore.Video.Thumbnails.getThumbnail(act.getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                else bm = MediaStore.Images.Thumbnails.getThumbnail(act.getContentResolver(), id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                if (bm == null) return "";
                ByteArrayOutputStream os = new ByteArrayOutputStream(); bm.compress(Bitmap.CompressFormat.JPEG, 80, os);
                return "data:image/jpeg;base64," + Base64.encodeToString(os.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public boolean openMedia(String kind, long id) {
            try {
                Uri u = ContentUris.withAppendedId(act.mediaUri(kind), id);
                String mime = act.getContentResolver().getType(u);
                if (mime == null) mime = "vid".equals(kind) ? "video/*" : "aud".equals(kind) ? "audio/*" : "image/*";
                act.go(new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
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
                File dir = act.safeFile(path); JSONObject res = new JSONObject(); JSONArray arr = new JSONArray();
                if (dir == null) { res.put("error", "Đường dẫn không hợp lệ"); res.put("list", arr); return res.toString(); }
                File[] fs = dir.listFiles();
                if (fs == null) { res.put("error", act.filesOk() ? "Không đọc được thư mục này" : "Chưa cấp quyền truy cập tệp"); res.put("list", arr); return res.toString(); }
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
                File f = act.safeFile(path); if (f == null) return false;
                String mime = mimeOf(f.getName());
                Uri u = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
                Intent i = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.go(Intent.createChooser(i, "Chia sẻ"));
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public boolean renameFile(String path, String newName) {
            try {
                if (newName == null) return false;
                newName = newName.trim();
                if (newName.length() == 0 || newName.contains("/") || newName.startsWith(".")) return false;
                File f = act.safeFile(path); if (f == null || f.getParentFile() == null) return false;
                File t = new File(f.getParentFile(), newName);
                if (newName.contains("..") || newName.indexOf('\0') >= 0) return false;
                if (t.exists()) return false;
                boolean ok = f.renameTo(t);
                if (ok) { try { act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(t))); } catch (Exception e) { } }
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
                    Uri u = act.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (u == null) return "";
                    OutputStream os = act.getContentResolver().openOutputStream(u);
                    os.write(by); os.close();
                    v.clear(); v.put(MediaStore.Images.Media.IS_PENDING, 0);
                    act.getContentResolver().update(u, v, null, null);
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
                    Uri u2 = act.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v2);
                    if (u2 != null) return u2.toString();
                } catch (Exception e2) { }
                act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                return f.getAbsolutePath();
            } catch (Exception e) { return ""; }
        }
        /** Chuyển tệp/thư mục vào thư mục khác. Trả "" nếu xong, ngược lại là lý do lỗi. */
        @JavascriptInterface public String moveFile(String src, String destDir) {
            try {
                File f = act.safeFile(src), d = act.safeFile(destDir);
                if (f == null || d == null) return "Đường dẫn không hợp lệ";
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
                    act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)));
                    act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(t)));
                } catch (Exception e) { }
                return "";
            } catch (Exception e) { return "Lỗi khi chuyển tệp"; }
        }
        /* ---- Nhật ký lỗi ---- */
        @JavascriptInterface public void logError(String where, String msg) { CrashLog.logJs(act, where, msg); }
        @JavascriptInterface public String crashLogPath() { return CrashLog.where(act); }
        /* ---- Thùng rác thật (giữ 7 ngày) ---- */
        @JavascriptInterface public boolean trashFile(String path) {
            try {
                File f = act.safeFile(path); if (f == null) return false;
                String id = TrashStore.trashFile(act, f);
                if (id == null) return false;
                try { act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))); } catch (Exception e) { }
                return true;
            } catch (Exception e) { return false; }
        }
        @JavascriptInterface public String trashList() { return TrashStore.list(act); }
        @JavascriptInterface public String trashRestore(String id) { return TrashStore.restore(act, id); }
        @JavascriptInterface public int trashPurge(String ids) { return TrashStore.purge(act, ids); }
        @JavascriptInterface public int trashPurgeOld() { return TrashStore.purgeOld(act); }
        /** Chép tệp / thư mục vào thư mục khác (dùng cho Chép / Cắt - Dán). Trả "" nếu xong, ngược lại là lý do lỗi. */
        @JavascriptInterface public String copyFile(String src, String destDir, boolean move) {
            try {
                File f = act.safeFile(src), d = act.safeFile(destDir);
                if (f == null || d == null) return "Đường dẫn không hợp lệ";
                if (!f.exists()) return "Không tìm thấy tệp nguồn";
                if (!d.isDirectory()) return "Thư mục đích không hợp lệ";
                if (f.isDirectory()) { String a1 = f.getCanonicalPath() + "/", b1 = d.getCanonicalPath() + "/"; if (b1.startsWith(a1)) return "Không thể chép thư mục vào chính nó"; }
                File t = new File(d, f.getName());
                if (t.exists()) {
                    if (move && t.getCanonicalPath().equals(f.getCanonicalPath())) return "";
                    String n = f.getName(), base = n, ext = ""; int dot = n.lastIndexOf('.');
                    if (dot > 0 && f.isFile()) { base = n.substring(0, dot); ext = n.substring(dot); }
                    for (int i = 1; i < 1000 && t.exists(); i++) t = new File(d, base + " (" + (i + 1) + ")" + ext);
                }
                boolean ok;
                if (move) { ok = f.renameTo(t); if (!ok) { ok = TrashStore.copyRec(f, t); if (ok) TrashStore.delRec(f); else TrashStore.delRec(t); } }
                else ok = TrashStore.copyRec(f, t);
                if (!ok) return move ? "Không chuyển được" : "Không chép được";
                try { act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(t))); if (move) act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))); } catch (Exception e) { }
                return "";
            } catch (Exception e) { return "Lỗi khi chép tệp"; }
        }
        @JavascriptInterface public boolean deleteFile(String path) {
            try {
                File f = act.safeFile(path); if (f == null) return false;
                if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null && k.length > 0) return false; }
                boolean ok = f.delete();
                if (ok) { try { MediaStore.Files.getContentUri("external"); act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f))); } catch (Exception e) { } }
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
                    act.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, m)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                } catch (Exception e) { }
            }
            return false;
        }
        /** Trả "" nếu mở được, ngược lại là lý do để hiện cho người dùng. */
        @JavascriptInterface public String openFileMsg(String path) {
            try {
                File f = act.safeFile(path);
                if (f == null) return "Đường dẫn không hợp lệ";
                if (!f.exists()) return "Không tìm thấy tệp";
                if (f.isDirectory()) return "Đây là thư mục";
                String ext = extOf(f.getName());
                Uri u;
                try { u = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f); }
                catch (Exception e) { return "Không đọc được tệp"; }
                if ("apk".equals(ext)) {
                    if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
                        act.go(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
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
                File f = act.safeFile(path); if (f == null) return false;
                Uri u = FileProvider.getUriForFile(act, act.getPackageName() + ".files", f);
                Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mimeOf(f.getName())).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent c = Intent.createChooser(v, "Mở bằng");
                c.setClipData(android.content.ClipData.newRawUri("", u));
                c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(c);
                return true;
            } catch (Exception e) { return false; }
        }

        /** Giải nén ZIP vào thư mục cùng tên cạnh tệp (chạy nền). Xong gọi window.nativeZipDone(token, json). */
        @JavascriptInterface public void extractZipAsync(final String path, final String token) {
            new Thread(new Runnable() { public void run() {
                JSONObject res = new JSONObject();
                File out = null; boolean ok = false;
                try {
                    File z = act.safeFile(path); if (z == null || z.getParentFile() == null) throw new java.io.IOException("Đường dẫn không hợp lệ");
                    File parent = z.getParentFile();
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
                    try { android.media.MediaScannerConnection.scanFile(act, made.toArray(new String[0]), null, null); } catch (Exception e) { }
                    res.put("dir", out.getAbsolutePath()); res.put("n", made.size());
                } catch (Exception e) {
                    try { res.put("error", e.getMessage() == null ? "Tệp ZIP lỗi hoặc có mật khẩu" : e.getMessage()); } catch (Exception x) { }
                }
                if (!ok && out != null) rmTree(out);
                final String js = "window.nativeZipDone&&window.nativeZipDone(" + JSONObject.quote(token) + "," + JSONObject.quote(res.toString()) + ")";
                act.runOnUiThread(new Runnable() { public void run() { if (act.web != null) act.web.evaluateJavascript(js, null); } });
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
                PackageManager pm = act.getPackageManager();
                Intent i = pm.getLaunchIntentForPackage("com.google.android.gm");
                if (i == null) {
                    Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL);
                    List<ResolveInfo> l = pm.queryIntentActivities(q, 0);
                    for (ResolveInfo r : l) { if (!r.activityInfo.packageName.equals(act.getPackageName())) { i = pm.getLaunchIntentForPackage(r.activityInfo.packageName); if (i != null) break; } }
                }
                if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com/"));
                act.go(i); return true;
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
                Cursor c = act.getContentResolver().query(base, new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA},
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
                if (act.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return "[]";
                Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
                ContentUris.appendId(b, start); ContentUris.appendId(b, end);
                Cursor c = act.getContentResolver().query(b.build(), new String[]{CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY}, null, null, CalendarContract.Instances.BEGIN + " ASC");
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
            act.runOnUiThread(new Runnable() { public void run() {
                try {
                    float d = act.getResources().getDisplayMetrics().density;
                    /* không ẩn view web nhúng khác: mỗi cửa sổ tự ẩn/hiện mình qua đồng bộ bên giao diện (cho phép nhiều cửa sổ không chồng nhau hiện cùng lúc) */
                    act.gkEnsure();
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, Math.round(w * d)), Math.max(1, Math.round(h * d)));
                    lp.leftMargin = Math.round(x * d); lp.topMargin = Math.round(y * d);
                    act.gkView.setLayoutParams(lp); act.gkView.setVisibility(View.VISIBLE);
                    act.gkPush();
                } catch (Throwable t) { CrashLog.log(act, "Trình duyệt Gecko không mở được (gkShow)", t); act.gkToast("Không mở được trình duyệt: " + t + "\nChi tiết: " + CrashLog.where(act)); }
            }});
        }
        @JavascriptInterface public void gkHide() { act.runOnUiThread(new Runnable() { public void run() { act.gkHideNow(); } }); }
        @JavascriptInterface public void gkClose() {
            act.runOnUiThread(new Runnable() { public void run() {
                if (act.gkView == null) return;
                try { act.gkCloseAll(); } catch (Exception e) { }
                try { act.root.removeView(act.gkView); } catch (Exception e) { }
                act.gkView = null; act.gkSession = null;
            }});
        }
        @JavascriptInterface public void gkGo(final String text) {
            act.runOnUiThread(new Runnable() { public void run() {
                try { act.gkEnsure(); String t = text == null ? "" : text.trim(); if (t.length() > 0) act.gkSession.loadUri(act.gkToUrl(t)); } catch (Throwable e) { }
            }});
        }
        @JavascriptInterface public void gkNewTab(final String url) { act.runOnUiThread(new Runnable() { public void run() { try { act.gkEnsure(); String t = url == null ? "" : url.trim(); act.gkNewTab(t.length() == 0 ? MainActivity.GK_HOME : act.gkToUrl(t), true); } catch (Throwable e) { } } }); }
        @JavascriptInterface public void gkSelectTab(final int i) { act.runOnUiThread(new Runnable() { public void run() { act.gkSelect(i); } }); }
        @JavascriptInterface public void gkCloseTab(final int i) { act.runOnUiThread(new Runnable() { public void run() { act.gkCloseTab(i); } }); }
        @JavascriptInterface public void gkBack() { act.runOnUiThread(new Runnable() { public void run() { if (act.gkSession != null) act.gkSession.goBack(); } }); }
        @JavascriptInterface public void gkForward() { act.runOnUiThread(new Runnable() { public void run() { if (act.gkSession != null) act.gkSession.goForward(); } }); }
        @JavascriptInterface public void gkReload() { act.runOnUiThread(new Runnable() { public void run() { if (act.gkSession != null) act.gkSession.reload(); } }); }

        /* ---- Gmail / Lịch Google: trình duyệt nhúng trong cửa sổ ---- */
        @JavascriptInterface public void extShow(final String key, final String url, final float x, final float y, final float w, final float h) {
            act.runOnUiThread(new Runnable() { public void run() {
                float d = act.getResources().getDisplayMetrics().density;
                /* không ẩn view web nhúng khác: mỗi cửa sổ tự ẩn/hiện mình (xem winFree bên giao diện) nên nhiều cửa sổ không chồng nhau hiện cùng lúc được */
                if ("omem".equals(key)) {   /* Ổ mềm chạy bằng Gecko */
                    try {
                        act.omemShow(Math.round(x * d), Math.round(y * d), Math.round(w * d), Math.round(h * d));
                    } catch (Throwable t) {
                        CrashLog.log(act, "Ổ mềm không mở được (extShow omem)", t);
                        act.gkToast("Ổ mềm không mở được: " + t + "\nChi tiết: " + CrashLog.where(act));
                    }
                    return;
                }
                if ("webandr".equals(key)) {
                    try {
                        View bv = act.browserGet(url).getRoot();
                        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(Math.max(1, Math.round(w * d)), Math.max(1, Math.round(h * d)));
                        bl.leftMargin = Math.round(x * d); bl.topMargin = Math.round(y * d);
                        bv.setLayoutParams(bl); bv.setVisibility(View.VISIBLE);
                    } catch (Throwable t) {
                        CrashLog.log(act, "Duyệt web không mở được (extShow webandr)", t);
                        act.browserHost = null;
                        act.gkToast("Duyệt web không mở được: " + t + "\nChi tiết: " + CrashLog.where(act));
                    }
                    return;
                }
                WebView v = act.extGet(key, url);
                act.extHiddenAt.remove(key);
                try { v.onResume(); } catch (Exception x) { }
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, Math.round(w * d)), Math.max(1, Math.round(h * d)));
                lp.leftMargin = Math.round(x * d); lp.topMargin = Math.round(y * d);
                v.setLayoutParams(lp); v.setVisibility(View.VISIBLE);
                ZoomHelper zz = act.zooms.get(key); if (zz != null) zz.onShown();
            }});
        }
        /** true = Ổ mềm chạy nhúng trong cửa sổ (Android < 9); false = dùng màn hình hồ sơ riêng (Android 9+, process riêng). */
        @JavascriptInterface public boolean omemEmbedded() { return com.desktopmini.launcher.drive3.DriveEmbed.enabled(); }
        @JavascriptInterface public void omemOpen(final String profile, final boolean upload) {
            act.runOnUiThread(new Runnable() { public void run() { act.omemSwitch(profile, upload); } });
        }
        @JavascriptInterface public void extHide(final String key) {
            act.runOnUiThread(new Runnable() { public void run() {
                if (key == null || key.length() == 0 || "webandr".equals(key)) act.browserHide();
                if (key == null || key.length() == 0 || "omem".equals(key)) act.omemHideNow();
                for (java.util.Map.Entry<String, WebView> e : act.ext.entrySet()) if (key == null || key.length() == 0 || e.getKey().equals(key)) { e.getValue().setVisibility(View.GONE); act.extPause(e.getKey(), e.getValue()); act.markExtHidden(e.getKey()); }
            }});
        }
        @JavascriptInterface public void extReload(final String key) {
            act.runOnUiThread(new Runnable() { public void run() { if ("webandr".equals(key)) { if (act.browserHost != null) act.browserHost.reload(); return; } if ("omem".equals(key)) { act.omemReload(); return; } WebView v = act.ext.get(key); if (v != null) v.reload(); } });
        }
        @JavascriptInterface public void extGo(final String key, final String url) {
            act.runOnUiThread(new Runnable() { public void run() { if ("webandr".equals(key)) { act.browserGet(url).openUrl(url); return; } if ("omem".equals(key)) { act.omemGo(url); return; } WebView v = act.extGet(key, url); v.loadUrl(url); } });
        }
        /** Back trong cửa sổ Gõ văn bản: đóng hộp thoại/màn hình Tệp của trang trước, hết thì đóng cửa sổ. */
        @JavascriptInterface public void wordBack() {
            act.runOnUiThread(new Runnable() { public void run() { if (act.wordHost != null) act.wordHost.back(); } });
        }
        @JavascriptInterface public void extForward(final String key) {
            act.runOnUiThread(new Runnable() { public void run() { if ("webandr".equals(key)) { if (act.browserHost != null) act.browserHost.forward(); return; } if ("omem".equals(key)) { act.omemForward(); return; } WebView v = act.ext.get(key); if (v != null && v.canGoForward()) v.goForward(); } });
        }
        /** Đóng hẳn WebView nhúng (dừng phát video/âm thanh khi đóng cửa sổ). */
        @JavascriptInterface public void extClose(final String key) {
            act.runOnUiThread(new Runnable() { public void run() {
                act.extHideFull();
                if ("webandr".equals(key)) {
                    if (act.browserHost == null) return;
                    final BrowserHost bh = act.browserHost; act.browserHost = null;
                    bh.onPause();
                    try { act.root.removeView(bh.getRoot()); } catch (Exception e) { }
                    bh.destroy();
                    return;
                }
                if ("omem".equals(key)) { act.omemClose(); return; }
                final WebView v = act.ext.remove(key);
                act.zooms.remove(key); act.extHiddenAt.remove(key);
                if (v == null) return;
                if ("word".equals(key) && act.wordHost != null) {
                    /* lưu nội dung đang gõ rồi mới hủy trang (lệnh lưu chạy bất đồng bộ) */
                    final WordHost h = act.wordHost; act.wordHost = null;
                    h.save();
                    v.setVisibility(View.GONE);
                    act.root.postDelayed(new Runnable() { public void run() { h.destroy(); try { act.root.removeView(v); v.destroy(); } catch (Exception e) { } } }, 500);
                    return;
                }
                try { v.loadUrl("about:blank"); act.root.removeView(v); v.destroy(); } catch (Exception e) { }
            }});
        }
        @JavascriptInterface public String extUrl(final String key) {
            if ("webandr".equals(key)) return act.browserHost == null ? "" : act.browserHost.currentUrl();
            if ("omem".equals(key)) return act.omemUrl();
            WebView v = act.ext.get(key); return v == null || v.getUrl() == null ? "" : v.getUrl();
        }
        @JavascriptInterface public void extBack(final String key) {
            act.runOnUiThread(new Runnable() { public void run() {
                if ("webandr".equals(key)) {
                    /* hết việc để lùi (không còn bảng/trang/thẻ) thì đóng cửa sổ Duyệt web */
                    if (act.browserHost == null || !act.browserHost.back()) act.web.evaluateJavascript("window.nativeCloseApp&&window.nativeCloseApp('webbrowser')", null);
                    return;
                }
                if ("omem".equals(key)) { act.omemBack(); return; }
                WebView v = act.ext.get(key); if (v == null) return;
                if (v.canGoBack()) { v.goBack(); return; }
                /* Trang một-trang (vd Zalo web) không có lịch sử để lùi: gửi phím Esc để đóng cuộc trò chuyện/bảng đang mở */
                v.evaluateJavascript("(function(){try{var e={key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true};var t=document.activeElement||document.body;t.dispatchEvent(new KeyboardEvent('keydown',e));document.dispatchEvent(new KeyboardEvent('keydown',e));t.dispatchEvent(new KeyboardEvent('keyup',e));}catch(x){}})()", null);
            } });
        }
}
