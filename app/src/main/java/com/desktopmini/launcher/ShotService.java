package com.desktopmini.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Chụp được MỌI màn hình (app ngoài lẫn app trong) trên máy không có API chụp của dịch vụ trợ năng (Android 7 trên Yoga Book):
 * dùng MediaProjection. Hệ thống hỏi quyền đúng một lần; dịch vụ nền này giữ phiên chụp để các lần sau bấm là chụp ngay.
 */
public class ShotService extends Service {
    public interface Cb { void done(Bitmap b); }

    static volatile MediaProjection proj;
    static Cb pending;
    private static final Handler H = new Handler(Looper.getMainLooper());

    /** Lấy ảnh toàn màn hình hiện tại. Chưa có quyền thì mở hộp thoại xin quyền rồi tự chụp sau khi được cấp. */
    public static void capture(Context c, Cb cb) {
        if (proj == null) {
            pending = cb;
            try { c.startActivity(new Intent(c, ShotPermActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Throwable t) { pending = null; cb.done(null); }
            return;
        }
        grab(c, cb);
    }

    static void grab(final Context c, final Cb cb) {
        final MediaProjection p = proj;
        if (p == null) { cb.done(null); return; }
        final boolean[] fin = {false};
        try {
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            final int w = dm.widthPixels, hh = dm.heightPixels;
            final ImageReader ir = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2);
            final VirtualDisplay vd = p.createVirtualDisplay("shot", w, hh, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.getSurface(), null, H);
            final Runnable timeout = new Runnable() { public void run() {
                if (fin[0]) return; fin[0] = true;
                try { vd.release(); ir.close(); } catch (Throwable t) { }
                cb.done(null);
            } };
            H.postDelayed(timeout, 2500);
            ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() { public void onImageAvailable(ImageReader r) {
                if (fin[0]) return;
                Image img = null; Bitmap out = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    Image.Plane pl = img.getPlanes()[0];
                    ByteBuffer buf = pl.getBuffer();
                    int ps = pl.getPixelStride(), rs = pl.getRowStride();
                    Bitmap full = Bitmap.createBitmap(rs / ps, hh, Bitmap.Config.ARGB_8888);
                    full.copyPixelsFromBuffer(buf);
                    out = full.getWidth() == w ? full : Bitmap.createBitmap(full, 0, 0, w, hh);
                } catch (Throwable t) { out = null; }
                finally { if (img != null) img.close(); }
                fin[0] = true; H.removeCallbacks(timeout);
                try { vd.release(); ir.close(); } catch (Throwable t) { }
                cb.done(out);
            } }, H);
        } catch (Throwable t) { if (!fin[0]) { fin[0] = true; cb.done(null); } }
    }

    /** Lưu PNG vào Ảnh › Screenshots (thư viện ảnh của máy). */
    public static boolean save(Context c, Bitmap b) {
        String name = "Screenshot_" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Screenshots");
                android.net.Uri u = c.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (u == null) return false;
                OutputStream o = c.getContentResolver().openOutputStream(u);
                b.compress(Bitmap.CompressFormat.PNG, 100, o); o.close();
                return true;
            }
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots");
            if (!d.exists() && !d.mkdirs()) throw new Exception("no dir");
            File f = new File(d, name);
            FileOutputStream o = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.PNG, 100, o); o.close();
            MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{"image/png"}, null);
            return true;
        } catch (Throwable t) {
            try { /* không ghi được vào bộ nhớ chung (thiếu quyền): lưu vào thư mục riêng của app */
                File d = c.getExternalFilesDir("Screenshots"); if (d == null) return false; d.mkdirs();
                FileOutputStream o = new FileOutputStream(new File(d, name));
                b.compress(Bitmap.CompressFormat.PNG, 100, o); o.close();
                return true;
            } catch (Throwable t2) { return false; }
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent in, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("shot", "Chụp màn hình", NotificationManager.IMPORTANCE_LOW));
                nb = new Notification.Builder(this, "shot");
            } else nb = new Notification.Builder(this);
            Notification n = nb.setSmallIcon(R.drawable.ic_shot).setContentTitle("Chụp màn hình sẵn sàng")
                .setContentText("Bấm nút chụp trên Dock để chụp mọi màn hình").setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(4711, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(4711, n);
            if (in != null && in.hasExtra("data")) {
                MediaProjectionManager m = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                MediaProjection p = m.getMediaProjection(in.getIntExtra("code", 0), (Intent) in.getParcelableExtra("data"));
                if (p != null) {
                    p.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { proj = null; } }, H);
                    proj = p;
                }
            }
        } catch (Throwable t) { proj = null; }
        final Cb cb = pending; pending = null;
        if (cb != null) {
            if (proj == null) cb.done(null);
            else {
                final Context c = this;
                H.postDelayed(new Runnable() { public void run() { grab(c, cb); } }, 700);
            }
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        MediaProjection p = proj; proj = null;
        if (p != null) { try { p.stop(); } catch (Throwable t) { } }
        super.onDestroy();
    }

    static void toast(Context c, String s) { try { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); } catch (Throwable t) { } }
}
