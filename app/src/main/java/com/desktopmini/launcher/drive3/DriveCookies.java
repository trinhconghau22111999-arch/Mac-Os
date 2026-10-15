package com.desktopmini.launcher.drive3;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.CookieManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tách đăng nhập 3 hồ sơ trên Android DƯỚI 9 (vd. 7.1.1).
 * Cách làm học từ app Laptrinh (LegacyProfileCookies.kt).
 *
 * Từ Android 9, mỗi process có thư mục dữ liệu WebView riêng (setDataDirectorySuffix) nên
 * 3 hồ sơ chạy 3 process là tách cookie thật sự. Android 7–8 không có tính năng đó, nên trên máy
 * cũ cả 3 hồ sơ chạy CHUNG 1 process (các lớp Legacy*Activity) và tách đăng nhập bằng cách đổi cookie:
 *  - Rời hồ sơ: lưu cookie của hồ sơ đó (theo từng tên miền đã truy cập) vào SharedPreferences.
 *  - Vào hồ sơ khác: xoá sạch cookie WebView rồi nạp lại cookie đã lưu của hồ sơ đó.
 *
 * Giới hạn: mỗi lúc chỉ 1 hồ sơ "đang đăng nhập" (không chạy song song được); localStorage/IndexedDB
 * vẫn dùng chung; chỉ lưu được tên=giá trị cookie (mất cờ HttpOnly/Domain/hạn dùng, cookie nạp lại
 * là cookie theo từng tên miền, hạn 1 năm).
 */
final class DriveCookies {
    private DriveCookies() { }

    /**
     * Ổ mềm luôn chạy NHÚNG trong cửa sổ Desktop Mini (như YouTube / Duyệt web, chừa Dock) trên mọi đời Android,
     * nên mọi máy đều tách 3 hồ sơ bằng cách đổi cookie trong cùng 1 process (không mở Activity toàn màn hình riêng nữa).
     */
    static boolean enabled() {
        return true;
    }

    private static final String PREFS = "legacy_profile_cookies";
    private static final String KEY_ACTIVE = "active_profile";
    private static final int MAX_HOSTS = 400;
    private static final int ONE_YEAR_SECONDS = 31536000;

    // Các tên miền đã thấy của hồ sơ đang "active" (ghi từ nhiều luồng nên dùng tập đồng thời).
    private static final Set<String> hosts =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static String hostsOwner = "";

    // --- Tải lên đang chạy: không được đổi cookie giữa chừng (cùng process nên dùng biến tĩnh) ---
    private static volatile String uploadingProfile = "";

    static void setUploading(String profile) {
        uploadingProfile = profile == null ? "" : profile;
    }

    /** Tên hồ sơ đang tải lên dở, hoặc "" nếu không có. */
    static String uploading() {
        return uploadingProfile;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Hồ sơ đang sở hữu cookie hiện có trong WebView ("" = chưa hồ sơ nào). */
    static String active(Context c) {
        return prefs(c).getString(KEY_ACTIVE, "");
    }

    /** Ghi nhận 1 tên miền mà hồ sơ đang dùng đã kết nối tới (gọi từ mọi request, nhẹ). */
    static void noteHost(String host) {
        if (!enabled() || host == null || host.isEmpty()) return;
        if (hosts.size() >= MAX_HOSTS) return;
        hosts.add(host.toLowerCase());
    }

    private static void ensureHosts(Context c, String profile) {
        if (profile.equals(hostsOwner)) return;
        hosts.clear();
        String saved = prefs(c).getString("hosts_" + profile, "");
        for (String h : saved.split("\n")) {
            if (!h.trim().isEmpty()) hosts.add(h.trim());
        }
        hostsOwner = profile;
    }

    private static void capture(Context c, String profile) {
        if (profile.isEmpty()) return;
        CookieManager cm = CookieManager.getInstance();
        ArrayList<String> snapshot = new ArrayList<>(hosts);
        JSONObject obj = new JSONObject();
        StringBuilder hs = new StringBuilder();
        for (String h : snapshot) {
            String ck = null;
            try { ck = cm.getCookie("https://" + h); } catch (Exception ignored) { }
            if (ck != null && !ck.trim().isEmpty()) {
                try { obj.put(h, ck); } catch (Exception ignored) { }
            }
            if (hs.length() > 0) hs.append('\n');
            hs.append(h);
        }
        prefs(c).edit()
                .putString("cookies_" + profile, obj.toString())
                .putString("hosts_" + profile, hs.toString())
                .apply();
    }

    /** Lưu cookie hiện tại vào hồ sơ NẾU hồ sơ đó đang sở hữu cookie (tránh ghi nhầm của hồ sơ khác). */
    static void captureIfActive(Context c, String profile) {
        if (!enabled() || profile == null || profile.isEmpty()) return;
        if (!active(c).equals(profile)) return;
        ensureHosts(c, profile);
        capture(c, profile);
    }

    /**
     * Chuyển quyền sở hữu cookie sang [profile]: lưu hồ sơ trước, xoá sạch, nạp cookie của [profile].
     * PHẢI gọi TRƯỚC khi tạo/tải bất kỳ WebView nào của hồ sơ.
     */
    static void activate(Context c, String profile) {
        if (!enabled() || profile == null || profile.isEmpty()) return;
        SharedPreferences p = prefs(c);
        String current = p.getString(KEY_ACTIVE, "");
        if (current.equals(profile)) {
            ensureHosts(c, profile);
            return;
        }
        if (!current.isEmpty()) {
            ensureHosts(c, current);
            capture(c, current);
        }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.removeAllCookies(null);

        hostsOwner = "";
        ensureHosts(c, profile);

        String saved = p.getString("cookies_" + profile, null);
        if (saved != null && !saved.trim().isEmpty()) {
            try {
                JSONObject obj = new JSONObject(saved);
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String h = keys.next();
                    String line = obj.optString(h, "");
                    for (String part : line.split(";")) {
                        String kv = part.trim();
                        if (kv.isEmpty() || !kv.contains("=")) continue;
                        cm.setCookie("https://" + h,
                                kv + "; Path=/; Secure; Max-Age=" + ONE_YEAR_SECONDS);
                    }
                }
            } catch (Exception ignored) {
                // Dữ liệu lưu hỏng -> bỏ qua, hồ sơ bắt đầu trống (đăng nhập lại).
            }
        }
        cm.flush();
        p.edit().putString(KEY_ACTIVE, profile).apply();
    }
}
