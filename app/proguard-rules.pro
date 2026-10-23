# ---- Cầu JavaScript: trang web gọi theo TÊN hàm nên R8 không được đổi tên / xóa ----
-keepattributes JavascriptInterface,Signature,*Annotation*,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.desktopmini.launcher.LauncherBridge { *; }
-keep class com.desktopmini.launcher.MainActivity$WebVoice { *; }
-keep class com.desktopmini.launcher.WordHost$Bridge { *; }
-keep class com.desktopmini.launcher.WebBrowserActivity$* { *; }
-keep class com.desktopmini.launcher.BrowserHost$* { *; }
-keep class com.desktopmini.launcher.YouTubeActivity$* { *; }
-keep class com.desktopmini.launcher.TypingActivity$* { *; }
# Hàm Kotlin được MainActivity.java gọi trực tiếp (YouTubeActivityKt.YT_ONLY_AD_JS...)
-keep class com.desktopmini.launcher.*Kt { *; }
# Activity / Service / Receiver đã được manifest giữ; giữ thêm lớp trong gói app cho chắc (app nhỏ, lợi ích rút gọn nằm ở thư viện)
-keep class com.desktopmini.launcher.** extends android.app.Activity
-keep class com.desktopmini.launcher.** extends android.app.Service
-keep class com.desktopmini.launcher.** extends android.content.BroadcastReceiver
# android.print.PdfPrint dùng API ẩn của hệ thống: để nguyên tên
-keep class android.print.** { *; }
