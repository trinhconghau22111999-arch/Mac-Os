package com.desktopmini.web;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebResponse;

/**
 * "Ổ mềm" của Desktop Mini = Google Drive 3 hồ sơ (Ảnh / Video / Tệp), chạy bằng Gecko trong app phụ riêng.
 * Mỗi hồ sơ có kho cookie riêng (contextId) nên đăng nhập 3 tài khoản Google khác nhau mà không lẫn nhau.
 * Nút "Tải lên": chọn tệp trong máy rồi tự bấm Mới → Tải tệp lên trong Drive (tiện ích dmdrive trong assets).
 */
public class OmemActivity extends Activity {
    private static final String START_URL = "https://drive.google.com/drive/my-drive";
    private static final int REQ_PICK = 31, REQ_FILE = 32;
    private static final String[][] P = {{"anh", "Ảnh"}, {"video", "Video"}, {"tep", "Tệp"}};

    private GeckoView view;
    private GeckoSession session;
    private LinearLayout home, bar, chips;
    private Ic icBack, icFwd;
    private String profile = "", url = "";
    private boolean backOk, fwdOk, ready, uploadStarted, extDone;
    private Uri[] pending;
    private GeckoSession.PromptDelegate.FilePrompt filePrompt;
    private GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fileRes;
    private final TextView[] chipViews = new TextView[3];

    private static String startUrl(String p) {
        if ("anh".equals(p)) return "https://drive.google.com/drive/search?q=type:image";
        if ("video".equals(p)) return "https://drive.google.com/drive/search?q=type:video";
        return START_URL;
    }
    private static boolean valid(String p) { return "anh".equals(p) || "video".equals(p) || "tep".equals(p); }
    private static String pickType(String p) { return "anh".equals(p) ? "image/*" : "video".equals(p) ? "video/*" : "*/*"; }

    private int dp(int v) { return GeckoCore.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        GeckoCore.rt(this);
        buildUi();
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent it) { super.onNewIntent(it); setIntent(it); handle(it); }

    private void handle(Intent it) {
        String p = it == null ? null : it.getStringExtra("profile");
        boolean up = it != null && it.getBooleanExtra("upload", false);
        if (p != null && valid(p)) open(p, up); else open("anh", false); /* mặc định vào thẳng trang Ảnh */
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFE9E9EE);
        root.setPadding(0, GeckoCore.menuBarInset(this), 0, 0);   /* chừa chỗ cho thanh menu nổi của Desktop Mini */

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(2), 0, dp(2), 0);
        bar.addView(Ic.make(this, Ic.CLOSE, new View.OnClickListener() { public void onClick(View v) { finish(); } }));
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL); chips.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 3; i++) {
            final String id = P[i][0];
            TextView c = new TextView(this);
            c.setText(P[i][1]); c.setTextSize(12); c.setGravity(Gravity.CENTER);
            c.setPadding(dp(12), 0, dp(12), 0);
            c.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { open(id, false); } });
            chipViews[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22));
            lp.rightMargin = dp(4);
            chips.addView(c, lp);
        }
        bar.addView(chips);
        View sp = new View(this); bar.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(Ic.make(this, Ic.UP, new View.OnClickListener() { public void onClick(View v) { if (valid(profile)) pick(profile); else toast("Hãy chọn Ảnh, Video hoặc Tệp trước"); } }));
        icBack = Ic.make(this, Ic.BACK, new View.OnClickListener() { public void onClick(View v) { if (session != null && backOk) session.goBack(); } });
        icFwd = Ic.make(this, Ic.FWD, new View.OnClickListener() { public void onClick(View v) { if (session != null && fwdOk) session.goForward(); } });
        bar.addView(icBack); bar.addView(icFwd);
        bar.addView(Ic.make(this, Ic.RELOAD, new View.OnClickListener() { public void onClick(View v) { if (session != null) session.reload(); } }));
        for (int i = 0; i < bar.getChildCount(); i++) { View v = bar.getChildAt(i); if (v instanceof Ic) v.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(30))); }
        bar.setVisibility(View.GONE);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(Color.WHITE);
        view = new GeckoView(this);
        view.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW);
        view.setOverScrollMode(View.OVER_SCROLL_NEVER);
        view.setVisibility(View.GONE);
        GeckoCore.attachZoomKeys(this, view);
        content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL); home.setGravity(Gravity.CENTER);
        home.setBackgroundColor(0xFFF2F2F7);
        home.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView title = new TextView(this);
        title.setText("Ổ mềm"); title.setTextSize(24); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(0xFF1C1C1E);
        title.setGravity(Gravity.CENTER); title.setPadding(0, 0, 0, dp(16));
        home.addView(title);
        final String[] colors = {"#4fc3f7", "#ff8a80", "#81c784"};
        for (int i = 0; i < 3; i++) {
            final String id = P[i][0];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(14)); bg.setStroke(dp(3), Color.parseColor(colors[i]));
            row.setBackground(bg);
            TextView nm = new TextView(this); nm.setText(P[i][1]); nm.setTextSize(18); nm.setTypeface(Typeface.DEFAULT_BOLD); nm.setTextColor(0xFF1C1C1E);
            row.addView(nm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(pill("Mở", new View.OnClickListener() { public void onClick(View v) { open(id, false); } }));
            row.addView(pill("Tải lên", new View.OnClickListener() { public void onClick(View v) { open(id, true); } }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            home.addView(row, lp);
        }
        content.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        markChips();
    }

    private TextView pill(String s, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(14); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFF0A60D8); t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFFE6EFFC); bg.setCornerRadius(dp(16));
        t.setBackground(bg);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void markChips() {
        for (int i = 0; i < 3; i++) {
            boolean on = P[i][0].equals(profile);
            GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(16)); bg.setColor(on ? 0xFF0A60D8 : 0xFFFFFFFF);
            chipViews[i].setBackground(bg);
            chipViews[i].setTextColor(on ? Color.WHITE : 0xFF3A3A3C);
        }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    /* ---------------- Gecko ---------------- */
    /** Tiện ích dọn giao diện Drive + tự bấm Tải tệp lên (assets/dmdrive). Lỗi thì bỏ qua, Ổ mềm vẫn dùng được. */
    private void ext() {
        if (extDone) return;
        extDone = true;
        try { GeckoCore.rt(this).getWebExtensionController().ensureBuiltIn("resource://android/assets/dmdrive/", "dmdrive@desktopmini.launcher"); }
        catch (Throwable t) { /* bỏ qua */ }
    }

    private GeckoSession newSession(String p) {
        ext();
        GeckoSessionSettings st = new GeckoSessionSettings.Builder()
            .contextId("omem-" + p)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .build();
        final GeckoSession se = new GeckoSession(st);
        se.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onExternalResponse(GeckoSession s, WebResponse response) { Downloads.start(OmemActivity.this, response); toast("Đang tải xuống…"); }
            @Override public void onCrash(GeckoSession s) { dropSession(); toast("Ổ mềm bị văng, hãy mở lại."); showHome(); }
            @Override public void onKill(GeckoSession s) { dropSession(); toast("Ổ mềm bị tắt do thiếu RAM, hãy mở lại."); showHome(); }
        });
        se.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String u) { if (u != null) url = u; }
            @Override public void onPageStop(GeckoSession s, boolean success) {
                if (!success || url == null) return;
                String h = Uri.parse(url).getHost();
                if (h != null && h.equals("drive.google.com")) {
                    ready = true;
                    /* đã chọn tệp bằng nút "Tải lên": vào tới trang Drive thì tự bấm Mới -> Tải tệp lên */
                    if (pending != null && !uploadStarted) { uploadStarted = true; view.postDelayed(new Runnable() { @Override public void run() { runUpload(); } }, 1500); }
                }
            }
        });
        se.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onCanGoBack(GeckoSession s, boolean can) { backOk = can; icBack.setEnabled(can); }
            @Override public void onCanGoForward(GeckoSession s, boolean can) { fwdOk = can; icFwd.setEnabled(can); }
            @Override public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) { se.loadUri(uri); return null; }
        });
        se.setPromptDelegate(new GeckoSession.PromptDelegate() {
            @Override public GeckoResult<PromptResponse> onFilePrompt(GeckoSession s, FilePrompt prompt) {
                if (pending != null) {   /* nút "Tải lên": giao thẳng tệp đã chọn cho Drive */
                    Uri[] given = pending; pending = null;
                    toast("Đang tải lên Drive…");
                    return GeckoResult.fromValue(confirm(prompt, given));
                }
                if (fileRes != null) fileRes.complete(prompt.dismiss());
                filePrompt = prompt;
                fileRes = new GeckoResult<PromptResponse>();
                Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                /* hồ sơ Ảnh chỉ cho chọn ảnh, hồ sơ Video chỉ cho chọn video, hồ sơ Tệp cho chọn mọi loại */
                it.addCategory(Intent.CATEGORY_OPENABLE); it.setType(pickType(profile));
                it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(Intent.createChooser(it, "Chọn tệp"), REQ_FILE); }
                catch (Exception e) { fileRes.complete(prompt.dismiss()); }
                return fileRes;
            }
        });
        se.open(GeckoCore.rt(this));
        return se;
    }

    private GeckoSession.PromptDelegate.PromptResponse confirm(GeckoSession.PromptDelegate.FilePrompt prompt, Uri[] uris) {
        try {
            if (uris.length == 1) return prompt.confirm(this, uris[0]);
            return prompt.confirm(this, uris);
        } catch (Throwable t) {
            try { return prompt.confirm(this, uris[0]); } catch (Throwable t2) { return prompt.dismiss(); }
        }
    }

    private void dropSession() {
        GeckoSession old = session;
        session = null; backOk = false; fwdOk = false; ready = false; uploadStarted = false;
        try { view.releaseSession(); } catch (Throwable t) { }
        if (old != null) { try { old.close(); } catch (Throwable t) { } }
    }

    private void showHome() {
        view.setVisibility(View.GONE); bar.setVisibility(View.GONE); home.setVisibility(View.VISIBLE);
        profile = ""; markChips();
    }

    /** Mở / chuyển hồ sơ (mỗi hồ sơ một kho cookie riêng). */
    private void open(String p, boolean upload) {
        if (!valid(p)) return;
        if (session != null && p.equals(profile)) { if (upload) pick(p); return; }
        dropSession();
        profile = p; pending = null;
        session = newSession(p);
        view.setSession(session);
        url = startUrl(p);
        session.loadUri(url);
        home.setVisibility(View.GONE); bar.setVisibility(View.VISIBLE); view.setVisibility(View.VISIBLE);
        icBack.setEnabled(false); icFwd.setEnabled(false);
        markChips();
        if (upload) pick(p);
    }

    private void pick(String p) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(pickType(p));
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_PICK); } catch (Exception e) { toast("Không mở được trình chọn tệp"); }
    }

    /** Đổi địa chỉ thành #dm-upload-...: tiện ích trong trang Drive tự bấm "Mới" rồi "Tải tệp lên"; Drive gọi hộp chọn tệp thì onFilePrompt giao tệp đã chọn. */
    private void runUpload() {
        if (session == null || pending == null) return;
        String base = url == null ? "" : url;
        int h = base.indexOf('#'); if (h >= 0) base = base.substring(0, h);
        String bh = null; try { bh = Uri.parse(base).getHost(); } catch (Exception e) { }
        if (bh == null || !bh.equals("drive.google.com") || base.contains("/drive/search")) base = START_URL;   /* trang lọc theo loại không có chỗ tải lên: dùng Drive của tôi */
        session.loadUri(base + "#dm-upload-" + System.currentTimeMillis());
        view.postDelayed(new Runnable() { @Override public void run() {
            if (pending != null) toast("Chưa tự bấm được – hãy bấm Mới → Tải tệp lên, tệp đã chọn sẽ được gửi");
        } }, 12000);
    }

    private static Uri[] uris(Intent d) {
        if (d == null) return null;
        if (d.getClipData() != null && d.getClipData().getItemCount() > 0) {
            Uri[] u = new Uri[d.getClipData().getItemCount()];
            for (int i = 0; i < u.length; i++) u[i] = d.getClipData().getItemAt(i).getUri();
            return u;
        }
        return d.getData() != null ? new Uri[]{d.getData()} : null;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        Uri[] u = res == RESULT_OK ? uris(data) : null;
        if (req == REQ_PICK) {
            if (u != null && u.length > 0) {
                for (Uri x : u) { try { getContentResolver().takePersistableUriPermission(x, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable t) { } }
                pending = u; uploadStarted = false;
                toast("Đã chọn " + u.length + " tệp – đang gửi lên Drive");
                if (ready) { uploadStarted = true; runUpload(); }
            }
        } else if (req == REQ_FILE) {
            GeckoResult<GeckoSession.PromptDelegate.PromptResponse> fr = fileRes;
            GeckoSession.PromptDelegate.FilePrompt fp = filePrompt;
            fileRes = null; filePrompt = null;
            if (fr == null || fp == null) return;
            if (u != null && u.length > 0) fr.complete(confirm(fp, u)); else fr.complete(fp.dismiss());
        }
    }

    @Override public void onBackPressed() {
        if (session != null && backOk) { session.goBack(); return; }
        if (session != null) { dropSession(); finish(); return; }
        finish();
    }

    @Override protected void onStart() { super.onStart(); if (session != null) { try { session.setActive(true); } catch (Throwable e) { } } }
    @Override protected void onStop() { super.onStop(); if (session != null && !Downloads.anyRunning()) { try { session.setActive(false); } catch (Throwable e) { } } }
    @Override protected void onDestroy() { dropSession(); super.onDestroy(); }
}
