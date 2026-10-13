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

## Duyệt web và YouTube (dạng cửa sổ)
- **Duyệt web** và **YouTube** giờ mở **trong cửa sổ** của Desktop Mini như Ghi chú, Zalo, Drive (kéo, đổi cỡ, thu nhỏ, đóng), không còn nhảy sang màn hình riêng.
- Cả hai dùng WebView gốc nhúng vào cửa sổ (`extGet` trong `MainActivity.java`), có chặn quảng cáo (chặn tên miền quảng cáo + script tự bấm "Bỏ qua" cho YouTube).
- **Duyệt web**: thanh địa chỉ (gõ địa chỉ hoặc từ khóa tìm Google), nút Lùi/Tiến/Tải lại/Trang chủ, thanh tiến độ, tải tệp qua trình quản lý tải xuống của máy, nút ⤢ mở trang hiện tại ở trình duyệt toàn màn hình cũ (`WebBrowserActivity`, đủ tab/dấu trang/lịch sử).
- **YouTube**: không có thanh địa chỉ, chỉ vào được `youtube.com`, `youtu.be` và trang đăng nhập/đồng ý của Google; link ra ngoài bị chặn. Xem video toàn màn hình được; Back thoát toàn màn hình trước.
- Back: lùi trang nếu còn, hết trang thì đóng cửa sổ. Đóng cửa sổ là dừng hẳn video/âm thanh.
- Các Activity cũ `WebBrowserActivity` và `YouTubeActivity` vẫn còn trong mã (Duyệt web toàn màn hình vẫn mở được từ nút ⤢ và từ app khác), chỉ là Desktop Mini không còn mở chúng trực tiếp từ biểu tượng.
- Google có thể chặn đăng nhập trong WebView; xem YouTube không cần đăng nhập vẫn được.

## Gõ văn bản (trình soạn thảo kiểu Word, chép từ repo Word)
- Biểu tượng **Gõ văn bản** nằm ngay sau Ghi chú; mở **trong cửa sổ** của Desktop Mini (kéo, đổi cỡ, thu nhỏ, đóng) như Duyệt web và YouTube. Trang `assets/word/index.html` chạy offline trong WebView gốc nhúng vào cửa sổ; phần lõi (cầu nối lưu tệp, PDF, chia sẻ, clipboard, đọc thành tiếng, in) ở `WordHost.kt`, dùng chung với `TypingActivity.kt` (màn hình riêng cũ, vẫn còn trong mã).
- Nút ✕ trong trang hoặc Back (sau khi đóng hộp thoại/màn hình Tệp của trang) đóng cửa sổ; nội dung được lưu trước khi đóng.
- Mở/lưu .docx, .html, .txt, xuất PDF (lưu vào **Tải xuống/GoVanBan**), chia sẻ qua Gmail/Drive/Zalo, đọc văn bản thành tiếng, tự lưu.
- Thư viện (Font Awesome, mammoth, JSZip) chép sẵn vào `assets/word/lib` từ npm, không cần tải lúc build.
- Tối ưu so với repo gốc: nạp trang qua WebViewAssetLoader (không dùng file://); mammoth/JSZip chỉ nạp khi mở/lưu .docx (khởi động nhanh hơn);
  bỏ font brands và các font thừa (~120 KB); chặn scheme lạ khi mở link; giải mã/ghi tệp/đọc PDF ở luồng nền;
  tự lưu khi rời app hoặc đóng cửa sổ.
