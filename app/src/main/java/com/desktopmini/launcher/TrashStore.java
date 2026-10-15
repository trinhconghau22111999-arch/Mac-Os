package com.desktopmini.launcher;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Thùng rác thật của Desktop Mini: mọi tệp / ảnh / video / nhạc bị xóa được chuyển (hoặc chép) vào thư mục riêng của ứng dụng
 * kèm 1 tệp chỉ mục index.json; giữ 7 ngày rồi tự xóa hẳn. Khôi phục = đưa về đúng chỗ cũ (hoặc vào thư viện nếu thiếu quyền).
 */
final class TrashStore {
    private TrashStore() { }

    static final long KEEP_MS = 7L * 24 * 3600 * 1000;

    static File dir(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "trash");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File indexFile(Context c) { return new File(dir(c), "index.json"); }

    static synchronized JSONArray index(Context c) {
        try {
            File f = indexFile(c);
            if (!f.exists()) return new JSONArray();
            FileInputStream in = new FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new JSONArray(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void save(Context c, JSONArray a) {
        try {
            FileOutputStream out = new FileOutputStream(indexFile(c));
            out.write(a.toString().getBytes("UTF-8")); out.close();
        } catch (Exception e) { }
    }

    private static String newId() { return System.currentTimeMillis() + "_" + (int) (Math.random() * 1000000); }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long s = 0; File[] k = f.listFiles();
        if (k != null) for (File x : k) s += sizeOf(x);
        return s;
    }

    static boolean copyRec(File a, File b) {
        try {
            if (a.isDirectory()) {
                if (!b.exists() && !b.mkdirs()) return false;
                File[] k = a.listFiles();
                if (k != null) for (File x : k) if (!copyRec(x, new File(b, x.getName()))) return false;
                return true;
            }
            FileInputStream in = new FileInputStream(a); FileOutputStream out = new FileOutputStream(b);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
            b.setLastModified(a.lastModified());
            return true;
        } catch (Exception e) { return false; }
    }

    static boolean delRec(File f) {
        if (f.isDirectory()) { File[] k = f.listFiles(); if (k != null) for (File x : k) delRec(x); }
        return f.delete();
    }

    private static void add(Context c, String id, String name, String orig, long size, String mime, boolean isDir) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("name", name); o.put("orig", orig == null ? "" : orig); o.put("t", System.currentTimeMillis());
            o.put("size", size); o.put("mime", mime == null ? "" : mime); o.put("dir", isDir);
            synchronized (TrashStore.class) { JSONArray a = index(c); a.put(o); save(c, a); }
        } catch (Exception e) { }
    }

    /** Chuyển 1 tệp / thư mục vào thùng rác. Trả id, hoặc null nếu thất bại (tệp gốc vẫn còn nguyên). */
    static String trashFile(Context c, File f) {
        try {
            if (f == null || !f.exists()) return null;
            String id = newId(); File d = new File(dir(c), id);
            long size = sizeOf(f); boolean isDir = f.isDirectory();
            boolean ok = f.renameTo(d);
            if (!ok) {
                ok = copyRec(f, d);
                if (ok) { if (!delRec(f)) { delRec(d); return null; } }
                else { delRec(d); return null; }
            }
            add(c, id, f.getName(), f.getAbsolutePath(), size, "", isDir);
            return id;
        } catch (Exception e) { return null; }
    }

    /** Chép 1 mục thư viện (ảnh / video / nhạc) vào thùng rác TRƯỚC khi xóa khỏi thư viện. Trả id hoặc null. */
    static String trashUri(Context c, Uri u) {
        String id = newId(); File d = new File(dir(c), id);
        Cursor q = null; String name = null, data = null, mime = null; long size = 0;
        try {
            q = c.getContentResolver().query(u, new String[]{MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.MIME_TYPE}, null, null, null);
            if (q != null && q.moveToFirst()) { name = q.getString(0); size = q.getLong(1); data = q.getString(2); mime = q.getString(3); }
        } catch (Exception e) { } finally { if (q != null) try { q.close(); } catch (Exception x) { } }
        try {
            InputStream in = c.getContentResolver().openInputStream(u); if (in == null) return null;
            FileOutputStream out = new FileOutputStream(d);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
            if (size <= 0) size = d.length();
        } catch (Exception e) { d.delete(); return null; }
        if (name == null || name.length() == 0) name = id;
        add(c, id, name, data, size, mime, false);
        return id;
    }

    /** Bỏ 1 mục khỏi thùng rác (dùng khi xóa thất bại sau khi đã chép vào). */
    static void drop(Context c, String id) {
        if (id == null) return;
        purge(c, id);
    }

    static String list(Context c) {
        purgeOld(c);
        return index(c).toString();
    }

    static int purgeOld(Context c) {
        int n = 0;
        synchronized (TrashStore.class) {
            JSONArray a = index(c), keep = new JSONArray(); long now = System.currentTimeMillis();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                if (now - o.optLong("t") > KEEP_MS) { delRec(new File(dir(c), o.optString("id"))); n++; }
                else keep.put(o);
            }
            if (n > 0) save(c, keep);
        }
        return n;
    }

    /** ids: danh sách id cách nhau dấu phẩy, hoặc "*" = tất cả. Trả số mục đã xóa vĩnh viễn. */
    static int purge(Context c, String ids) {
        int n = 0;
        synchronized (TrashStore.class) {
            boolean all = "*".equals(ids);
            java.util.HashSet<String> set = new java.util.HashSet<String>();
            if (!all && ids != null) for (String s : ids.split(",")) if (s.trim().length() > 0) set.add(s.trim());
            JSONArray a = index(c), keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                if (all || set.contains(o.optString("id"))) { delRec(new File(dir(c), o.optString("id"))); n++; }
                else keep.put(o);
            }
            save(c, keep);
        }
        return n;
    }

    private static File unique(File t) {
        if (!t.exists()) return t;
        String n = t.getName(), base = n, ext = ""; int d = n.lastIndexOf('.');
        if (d > 0) { base = n.substring(0, d); ext = n.substring(d); }
        for (int i = 1; i < 1000; i++) { File x = new File(t.getParentFile(), base + " (khôi phục" + (i > 1 ? " " + i : "") + ")" + ext); if (!x.exists()) return x; }
        return t;
    }

    /** Khôi phục. Trả "" nếu xong, ngược lại là lý do lỗi. */
    static String restore(Context c, String id) {
        JSONObject o = null;
        synchronized (TrashStore.class) {
            JSONArray a = index(c);
            for (int i = 0; i < a.length(); i++) { JSONObject x = a.optJSONObject(i); if (x != null && id.equals(x.optString("id"))) { o = x; break; } }
        }
        if (o == null) return "Không tìm thấy mục này";
        File src = new File(dir(c), id);
        if (!src.exists()) { purge(c, id); return "Dữ liệu đã bị xóa"; }
        String orig = o.optString("orig"), name = o.optString("name"), mime = o.optString("mime");
        boolean isDir = o.optBoolean("dir");
        File out = null;
        if (orig.length() > 0) {
            try {
                File t = unique(new File(orig));
                File p = t.getParentFile(); if (p != null && !p.exists()) p.mkdirs();
                boolean ok = src.renameTo(t);
                if (!ok) { ok = copyRec(src, t); if (ok) delRec(src); else delRec(t); }
                if (ok) out = t;
            } catch (Exception e) { }
        }
        if (out == null && !isDir) {   // thiếu quyền ghi về chỗ cũ: đưa vào thư viện
            try {
                String m = mime == null ? "" : mime;
                if (m.length() == 0) { String ex = android.webkit.MimeTypeMap.getFileExtensionFromUrl(name.replace(" ", "_")); String g = ex == null ? null : android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ex.toLowerCase()); m = g == null ? "application/octet-stream" : g; }
                Uri coll; String rel;
                if (m.startsWith("image/")) { coll = MediaStore.Images.Media.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_PICTURES + "/DesktopMini"; }
                else if (m.startsWith("video/")) { coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_MOVIES + "/DesktopMini"; }
                else if (m.startsWith("audio/")) { coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_MUSIC + "/DesktopMini"; }
                else if (Build.VERSION.SDK_INT >= 29) { coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI; rel = Environment.DIRECTORY_DOWNLOADS + "/DesktopMini"; }
                else return "Thiếu quyền ghi về vị trí cũ";
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name); v.put(MediaStore.MediaColumns.MIME_TYPE, m);
                if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                Uri u = c.getContentResolver().insert(coll, v);
                if (u == null) return "Không khôi phục được";
                OutputStream os = c.getContentResolver().openOutputStream(u);
                FileInputStream in = new FileInputStream(src);
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.close(); in.close(); src.delete();
                purge(c, id);
                return "";
            } catch (Exception e) { return "Không khôi phục được"; }
        }
        if (out == null) return "Không khôi phục được (thiếu quyền ghi về vị trí cũ)";
        try { MediaScannerConnection.scanFile(c, new String[]{out.getAbsolutePath()}, null, null); } catch (Exception e) { }
        purge(c, id);
        return "";
    }
}
