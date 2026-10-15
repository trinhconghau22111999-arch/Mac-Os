package com.desktopmini.launcher.drive3;

import android.content.Context;

/**
 * Cầu nối cho "Ổ mềm" chạy NHÚNG trong cửa sổ của Desktop Mini (thấy Dock) thay vì màn hình Android riêng.
 * Luôn bật trên mọi đời Android: các hồ sơ chạy chung process chính và tách đăng nhập bằng cách đổi cookie (DriveCookies).
 */
public final class DriveEmbed {
    private DriveEmbed() { }

    public static final String START_URL = "https://drive.google.com/drive/my-drive";

    public static boolean enabled() { return DriveCookies.enabled(); }

    public static boolean valid(String p) { return "anh".equals(p) || "video".equals(p) || "tep".equals(p); }

    public static String pickType(String p) {
        if ("anh".equals(p)) return "image/*";
        if ("video".equals(p)) return "video/*";
        return "*/*";
    }

    public static void activate(Context c, String profile) { DriveCookies.activate(c, profile); }

    public static void captureIfActive(Context c, String profile) { DriveCookies.captureIfActive(c, profile); }

    public static void noteHost(String host) { DriveCookies.noteHost(host); }

    public static String tidyJs() { return DriveActivity.TIDY_JS; }

    public static String clickNewJs() { return DriveActivity.CLICK_NEW_JS; }

    public static String clickUploadJs() { return DriveActivity.CLICK_UPLOAD_JS; }
}
