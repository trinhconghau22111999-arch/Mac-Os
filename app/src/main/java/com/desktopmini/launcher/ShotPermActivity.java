package com.desktopmini.launcher;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;

/** Hộp thoại xin quyền ghi màn hình (chỉ hiện lần đầu hoặc khi hệ thống thu hồi quyền). */
public class ShotPermActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), 1);
        } catch (Throwable t) { fail(); }
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res == RESULT_OK && data != null) {
            Intent s = new Intent(this, ShotService.class).putExtra("code", res).putExtra("data", data);
            try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s); }
            catch (Throwable t) { fail(); return; }
        } else fail();
        finish();
    }
    private void fail() {
        ShotService.Cb cb = ShotService.pending; ShotService.pending = null;
        if (cb != null) cb.done(null);
        finish();
    }
}
