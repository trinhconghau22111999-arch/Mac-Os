package com.desktopmini.launcher.drive3;

/**
 * Bản cho Android dưới 9: giống hệt 3 hồ sơ gốc nhưng KHÔNG khai báo android:process riêng
 * trong AndroidManifest, nên cả 3 chạy chung 1 process và tách đăng nhập bằng DriveCookies.
 * Mỗi lớp là 1 lớp riêng vì manifest không cho dùng lại 1 lớp với process khác nhau.
 */
final class LegacyActivities {
    private LegacyActivities() { }

    /** Chọn lớp hồ sơ phù hợp với phiên bản Android. */
    static Class<?> photo() {
        return DriveCookies.enabled() ? LegacyPhotoActivity.class : PhotoActivity.class;
    }

    static Class<?> video() {
        return DriveCookies.enabled() ? LegacyVideoActivity.class : VideoActivity.class;
    }

    static Class<?> file() {
        return DriveCookies.enabled() ? LegacyFileActivity.class : FileActivity.class;
    }
}
