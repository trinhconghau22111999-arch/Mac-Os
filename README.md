# Desktop Mini Launcher (Android)

Giao diện kiểu macOS làm màn hình chính Android, dùng dữ liệu và cài đặt thật của máy.

## Cách có file APK
1. Tạo repo GitHub mới, tải toàn bộ thư mục này lên (giữ nguyên `.github/workflows/build.yml`).
2. Vào tab **Actions** → đợi "Build APK" chạy xong (~3-5 phút).
3. Mở lần chạy đó → mục **Artifacts** → tải `DesktopMiniLauncher-apk`, giải nén lấy `app-debug.apk`, cài vào điện thoại.
4. Bấm nút Home → chọn "Desktop Mini" → "Luôn luôn" để đặt làm màn hình chính.

## Quyền (xin ngay lần mở đầu tiên)
Lần lượt: nhạc, ảnh, thông báo, Bluetooth, micro (Ghi âm), camera (Máy ảnh), lịch → sửa cài đặt hệ thống (độ sáng) → đọc thông báo → Trợ năng (khóa màn hình, menu nguồn) → truy cập mọi tệp.
Mỗi quyền đặc biệt chỉ hỏi một lần; xem lại và cấp sau trong Cài đặt hệ thống → Quyền truy cập.

## Ghi chú
- Gmail và Lịch Google chạy trong trình duyệt nhúng; Google đôi khi chặn đăng nhập trong WebView. Nếu bị chặn, mở Chrome đăng nhập trước hoặc báo lại để đổi cách.
- Khởi động lại/Tắt máy mở menu nguồn thật của Android (Android không cho app tự tắt máy).
