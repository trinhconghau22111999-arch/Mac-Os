# Desktop Mini Launcher (Android)

Giao diện kiểu macOS làm màn hình chính Android, dùng dữ liệu và cài đặt thật của máy.

## Cách có file APK
1. Tạo repo GitHub mới, tải toàn bộ thư mục này lên (giữ nguyên `.github/workflows/build.yml`).
2. Vào tab **Actions** → đợi "Build APK" chạy xong (~3-5 phút).
3. Mở lần chạy đó → mục **Artifacts** → tải `DesktopMiniLauncher-apk`, giải nén lấy file `.apk` (bản release), cài vào điện thoại.
4. Bấm nút Home → chọn "Desktop Mini" → "Luôn luôn" để đặt làm màn hình chính.

## Quyền (xin ngay lần mở đầu tiên)
Lần lượt: nhạc, ảnh, thông báo, Bluetooth, micro (Ghi âm), camera (Máy ảnh), lịch → sửa cài đặt hệ thống (độ sáng) → đọc thông báo → Trợ năng (khóa màn hình, menu nguồn) → truy cập mọi tệp.
Mỗi quyền đặc biệt chỉ hỏi một lần; xem lại và cấp sau trong Cài đặt hệ thống → Quyền truy cập.

## Ghi chú
- Gmail và Lịch Google chạy trong trình duyệt nhúng; Google đôi khi chặn đăng nhập trong WebView. Nếu bị chặn, mở Chrome đăng nhập trước hoặc báo lại để đổi cách.
- Khởi động lại/Tắt máy mở menu nguồn thật của Android (Android không cho app tự tắt máy).

## Duyệt web (app riêng, chép từ repo Webandr)
- **Duyệt web** là app mới nằm cạnh app **Trình duyệt** cũ (GeckoView), app cũ giữ nguyên, không bị thay.
- Bấm Duyệt web mở màn hình trình duyệt thật (`WebBrowserActivity.kt`): nhiều tab, dấu trang, lịch sử, tải xuống, mật khẩu đã lưu, chặn quảng cáo/web đen, chia đôi màn hình, QR Remote Input, Truyền tệp.
- Nút ✕ (hoặc Back khi chỉ còn 1 tab) quay về màn hình Desktop Mini.
- Không đăng ký làm trình duyệt mặc định của hệ thống.
- Đã bật `usesCleartextTraffic` để mở được trang http.

## YouTube (app riêng, chỉ mở YouTube)
- Biểu tượng **YouTube** (đỏ) nằm cạnh Duyệt web; mở màn hình `YouTubeActivity.kt`, **không có thanh địa chỉ**.
- Chỉ vào được `youtube.com`, `youtu.be` và trang đăng nhập/đồng ý của Google. Link ra ngoài (kể cả cổng `/redirect` của YouTube, cửa sổ mới, `intent:`) đều bị chặn và hiện thông báo.
- Chặn quảng cáo như Duyệt web: chặn tên miền quảng cáo + script tự bấm "Bỏ qua", tua nhanh quảng cáo không bỏ qua được, ẩn banner.
- Back: lùi trang; hết trang để lùi thì đóng app về Desktop Mini. Xem video toàn màn hình được.
- Google có thể chặn đăng nhập trong WebView; app đã dùng UA như Chrome để giảm khả năng này, nhưng không đảm bảo. Xem YouTube không cần đăng nhập vẫn được.
