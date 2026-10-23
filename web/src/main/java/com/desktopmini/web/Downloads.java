package com.desktopmini.web;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.webkit.URLUtil;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.WebResponse;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tải xuống của Gecko (ghi thẳng vào thư mục Tải xuống của máy). Dùng chung cho cả Trình duyệt và Ổ mềm; danh sách tệp đã tải được nhớ lại. */
final class Downloads {
    private Downloads() { }

    static final class Item {
        int id; String name = "", state = "run", msg = ""; long got, total; String uri = "";
    }

    interface Listener { void onChanged(); }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final List<Item> items = new ArrayList<Item>();
    private static final java.util.Set<Integer> cancelled = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>());
    private static final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<Listener>();
    private static int seq = 0;
    private static boolean loaded = false;

    static void listen(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    static void unlisten(Listener l) { listeners.remove(l); }
    private static void fire() { UI.post(new Runnable() { public void run() { for (Listener l : listeners) l.onChanged(); } }); }

    static synchronized List<Item> list(Context c) { load(c); return new ArrayList<Item>(items); }

    private static void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            JSONArray a = new JSONArray(c.getApplicationContext().getSharedPreferences("web_data", Context.MODE_PRIVATE).getString("downloads", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item(); it.id = -1; it.name = o.optString("n"); it.state = "ok"; it.got = o.optLong("g"); it.total = it.got; it.uri = o.optString("u");
                if (it.name.length() > 0) items.add(it);
            }
        } catch (Throwable t) { }
    }

    private static void save(Context c) {
        try {
            JSONArray a = new JSONArray(); int n = 0;
            for (Item it : items) {
                if (!"ok".equals(it.state) || n >= 30) continue;
                JSONObject o = new JSONObject(); o.put("n", it.name); o.put("g", it.got); o.put("u", it.uri); a.put(o); n++;
            }
            c.getApplicationContext().getSharedPreferences("web_data", Context.MODE_PRIVATE).edit().putString("downloads", a.toString()).apply();
        } catch (Throwable t) { }
    }

    static synchronized void clearFinished(Context c) {
        load(c);
        for (int i = items.size() - 1; i >= 0; i--) if (!"run".equals(items.get(i).state)) items.remove(i);
        save(c); fire();
    }

    static synchronized void cancel(int id) { for (Item it : items) if (it.id == id && "run".equals(it.state)) cancelled.add(id); }

    static synchronized boolean anyRunning() { for (Item it : items) if ("run".equals(it.state)) return true; return false; }

    static synchronized boolean isRunningName(String name) { for (Item it : items) if ("run".equals(it.state) && name.equals(it.name)) return true; return false; }

    static String header(WebResponse r, String key) {
        if (r.headers == null) return null;
        for (Map.Entry<String, String> e : r.headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    static String nameOf(WebResponse r) { return URLUtil.guessFileName(r.uri, header(r, "content-disposition"), header(r, "content-type")); }

    private static synchronized Item find(int id) { for (Item it : items) if (it.id == id) return it; return null; }

    private static void update(Context c, int id, String name, String state, long got, long total, String msg, String uri) {
        synchronized (Downloads.class) {
            Item it = find(id);
            if (it == null) return;
            if ("cancel".equals(state)) { items.remove(it); save(c); fire(); return; }
            it.name = name; it.state = state; it.got = got; it.total = total; it.msg = msg == null ? "" : msg;
            if (uri != null) it.uri = uri;
            if (!"run".equals(state)) save(c);
        }
        fire();
    }

    static void start(final Context ctx, final WebResponse r) {
        final Context c = ctx.getApplicationContext();
        final String name0 = nameOf(r);
        String ct = header(r, "content-type");
        if (ct != null) { int sc = ct.indexOf(';'); if (sc >= 0) ct = ct.substring(0, sc); ct = ct.trim(); }
        final String mime = (ct == null || ct.length() == 0) ? "application/octet-stream" : ct;
        long tl = -1;
        try { String cl = header(r, "content-length"); if (cl != null) tl = Long.parseLong(cl.trim()); } catch (Exception e) { tl = -1; }
        final long total = tl;
        final int id;
        synchronized (Downloads.class) {
            load(c);
            id = ++seq;
            Item it = new Item(); it.id = id; it.name = name0; it.total = total;
            items.add(0, it);
        }
        fire();
        new Thread(new Runnable() { public void run() {
            Uri mu = null; OutputStream out = null; String finalName = name0, legacyPath = null, openUri = "";
            try {
                InputStream in = r.body;
                if (in == null) throw new java.io.IOException("không có dữ liệu");
                if (Build.VERSION.SDK_INT >= 29) {
                    /* Android 10+: ghi vào Download qua MediaStore (không cần quyền riêng) */
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name0);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    mu = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (mu == null) throw new java.io.IOException("không tạo được tệp");
                    out = c.getContentResolver().openOutputStream(mu);
                    Cursor cu = c.getContentResolver().query(mu, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
                    if (cu != null) { try { if (cu.moveToFirst() && cu.getString(0) != null) finalName = cu.getString(0); } finally { cu.close(); } }
                    openUri = mu.toString();
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!(dir.exists() || dir.mkdirs()) || !dir.canWrite()) dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    File f = new File(dir, name0);
                    int i = 1; String base = name0, ex = "";
                    int dot = name0.lastIndexOf('.');
                    if (dot > 0) { base = name0.substring(0, dot); ex = name0.substring(dot); }
                    while (f.exists()) f = new File(dir, base + " (" + (i++) + ")" + ex);
                    finalName = f.getName();
                    out = new FileOutputStream(f);
                    legacyPath = f.getAbsolutePath();
                    openUri = "file:" + legacyPath;
                }
                if (out == null) throw new java.io.IOException("không ghi được tệp");
                long got = 0, lastPush = 0;
                try {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled.contains(id)) throw new java.io.IOException("cancel");
                        out.write(buf, 0, n); got += n;
                        long now = System.currentTimeMillis();
                        if (now - lastPush > 250) { lastPush = now; update(c, id, finalName, "run", got, total, "", null); }
                    }
                } finally { out.close(); }
                in.close();
                if (mu != null) {
                    ContentValues v2 = new ContentValues();
                    v2.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    c.getContentResolver().update(mu, v2, null, null);
                } else if (legacyPath != null) {
                    android.media.MediaScannerConnection.scanFile(c, new String[]{legacyPath}, null, null);
                }
                update(c, id, finalName, "ok", got, got, "", openUri);
            } catch (Exception e) {
                boolean cx = cancelled.remove(id);
                if (mu != null) { try { c.getContentResolver().delete(mu, null, null); } catch (Exception x) { } }
                if (legacyPath != null) { try { new File(legacyPath).delete(); } catch (Exception x) { } }
                try { InputStream bi = r.body; if (bi != null) bi.close(); } catch (Exception x) { }
                update(c, id, finalName, cx ? "cancel" : "err", 0, total, String.valueOf(e.getMessage()), null);
            }
        } }, "web-download").start();
    }
}
