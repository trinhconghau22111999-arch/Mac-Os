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

## Office Online (4 app dạng cửa sổ, không thanh địa chỉ)
- Bốn biểu tượng **Word Online** (xanh dương), **Excel Online** (xanh lá), **PowerPoint Online** (cam), **Office Online** (trang chủ office.com) nằm sau Gõ văn bản; mở trong cửa sổ như YouTube, **không có thanh địa chỉ** (chỉ có nút Quay lại, tên app, Tải lại).
- Dùng WebView gốc nhúng (`extGet` trong `MainActivity.java`, khóa `ms-word`, `ms-excel`, `ms-ppt`, `ms-office`), giao diện máy tính. Đăng nhập tài khoản Microsoft một lần, dùng chung cho cả bốn (chung cookie).
- Chỉ vào được trang của Microsoft (office.com, microsoft365.com, microsoftonline.com, live.com, sharepoint.com, onedrive.com…); link ra ngoài bị chặn và hiện thông báo, như YouTube.
- Hỗ trợ chọn tệp từ máy (mở/tải lên tài liệu) và tải tệp về qua trình tải xuống của máy. Cần Internet; đóng cửa sổ thì trang được hủy (tài liệu đã tự lưu trên OneDrive).
- Microsoft có thể hiện trang chặn/xác minh khi đăng nhập trong WebView; nếu bị, mở office.com ở Chrome đăng nhập trước hoặc báo lại để đổi cách.

## Ổ mềm = Drive 3 hồ sơ (chép từ repo Drive, thay hẳn bản Ổ mềm cũ)
- Bản Ổ mềm cũ (gộp nhiều Google Drive bằng Drive API, `vn.omem`, WorkManager) đã **xóa toàn bộ**; chỉ giữ biểu tượng **Ổ mềm** trên Dock/Launchpad. Bấm vào là mở app **Drive 3 hồ sơ** ở màn hình Android riêng (thoát/Back thì quay về Desktop Mini).
- Mã nằm ở `app/src/main/java/com/desktopmini/launcher/drive3/` (chép nguyên từ repo Drive, đổi tên gói; `MainActivity` của Drive đổi thành `HomeActivity`).
- 3 hồ sơ cố định **Ảnh / Video / Tệp**, mỗi hồ sơ đăng nhập 1 tài khoản Google riêng trong Drive web; nút **Tải lên** ở mỗi thẻ chọn tệp rồi tự gửi lên Drive.
  - Android 9 trở lên: mỗi hồ sơ 1 process (`:p1`, `:p2`, `:p3`) + thư mục dữ liệu WebView riêng, tách đăng nhập hoàn toàn, mở song song được.
  - Android 7–8: 3 hồ sơ chạy chung process chính và **đổi cookie** theo hồ sơ (`DriveCookies.java`); mỗi lúc chỉ 1 hồ sơ đăng nhập, đang tải lên ở hồ sơ này thì chưa mở được hồ sơ khác.
- Khi tải lên: khung phần trăm ở đáy màn hình, thông báo tiến độ, giữ màn hình sáng, dịch vụ nền (`UploadService`) giữ CPU + Wi-Fi thức khi tắt màn hình.
- Không dùng Google Drive API, không cần khóa/máy chủ; đã bỏ các thư viện chỉ phục vụ Ổ mềm cũ (Google API client, WorkManager, coroutines).

## Bản build, R8 và bảo mật (ghi chú kỹ thuật)
- **CPU (ABI)**: mặc định chỉ đóng gói **x86** cho Lenovo Yoga Book YB1-X90F (không cài được trên điện thoại ARM). Máy khác: chạy workflow bằng nút *Run workflow* và nhập `abis` (ví dụ `arm64-v8a`), hoặc build cục bộ với `-Pabis=arm64-v8a`. Không chia APK theo ABI.
- **R8**: bản release bật `minifyEnabled` + `shrinkResources`. Các lớp có `@JavascriptInterface` được giữ nguyên tên trong `app/proguard-rules.pro`; nếu thêm cầu JS mới (lớp mới có `@JavascriptInterface`) thì quy tắc chung vẫn giữ được. Sau mỗi lần đổi quy tắc, thử lại các app dùng cầu: Tệp, Ảnh, Ghi âm, Nhạc, Gõ văn bản.
- **CI**: push liên tiếp trên cùng nhánh sẽ hủy bản build cũ (`concurrency`), chỉ build bản mới nhất.
- **Cầu AndroidLauncher**: mọi hàm nhận đường dẫn tệp đều qua `safeFile()` (`MainActivity.java`): lấy đường dẫn thật, chỉ cho phép trong bộ nhớ ngoài (`/storage`, `/mnt`), chặn thư mục dữ liệu riêng của app. WebView chính chạy ở `https://appassets.androidplatform.net/assets/` (`WebViewAssetLoader`, không còn `file://`), tắt `allowFileAccess`/`allowContentAccess`, chỉ ở trong origin này; link http(s) mở ra ngoài. Lần đầu sau khi cập nhật, dữ liệu lưu cũ của origin `file://` (cài đặt, Dock, hình nền, ghi chú...) được xóa một lần (`purge-old.html`) và giao diện về mặc định. Cầu `AndroidVoice` chỉ nhận lệnh từ trang https đang hiển thị (YouTube thì phải đúng tên miền YouTube).
- **Mạng**: chỉ cho https (`res/xml/network_security_config.xml`). Cần mở một địa chỉ http riêng thì thêm `domain-config` vào tệp đó.
- **Nạp lười (`assets/js/`)**: Máy tính, Cài đặt, Ghi âm, Webcam, Trình duyệt, Ảnh, Terminal nằm trong `assets/js/<tên>.js`, chỉ tải khi mở app lần đầu (`lazyBuild` trong `index.html`). Mỗi tệp đặt mã trong hàm bọc `window.__LZ.<tên>`; `index.html` lấy thân hàm rồi `eval` trực tiếp để dùng chung biến với phần còn lại, nên sửa mã app ở đó như bình thường. Thêm app lazy mới: tạo tệp theo mẫu, thêm tên hàm vào `LZFN`, và để hàm giữ chỗ `function buildX(b,w){return lazyBuild('x','buildX',b,w)}` trong `index.html`.
- **Hiệu năng**: bộ hẹn giờ chung `dmEvery` (tạm dừng khi ra nền/tắt màn hình), tối đa 3 trang web nhúng sống cùng lúc (`EXT_MAX`), blur kính 20px (`--bl`; Chế độ nhẹ vẫn tắt hẳn blur), font Awesome của Gõ văn bản cắt còn đúng icon dùng.
- **Ổ mềm**: Android dưới 9 (kể cả Lenovo Yoga Book 7.1.1) chạy **nhúng trong cửa sổ** như Duyệt web (thấy Dock): chọn hồ sơ Ảnh / Video / Tệp ngay trên thanh tiêu đề, nút *Tải lên* chọn tệp rồi tự bấm Mới → Tải tệp lên. Các hồ sơ chung process chính và tách đăng nhập bằng cách đổi cookie (`DriveCookies`, `DriveEmbed`): mỗi lúc chỉ 1 hồ sơ đăng nhập, đổi hồ sơ sẽ xóa cookie của các trang web nhúng khác (YouTube, Zalo...). Bản nhúng không có khung phần trăm tải lên và dịch vụ nền giữ CPU/Wi-Fi như màn hình hồ sơ riêng. Android 9+ vẫn mở màn hình hồ sơ riêng (process riêng từng hồ sơ) và thoát hồ sơ thì process đó được giải phóng.
- **Danh sách Tệp**: thư mục và Tải xuống dựng theo từng đợt (80 dòng đầu, cuộn gần cuối dựng thêm 120) nên thư mục nhiều tệp mở nhanh.
