-dontwarn org.mozilla.**
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**
# GeckoView: mã gốc (JNI) gọi lại Java theo TÊN lớp/hàm/trường nên R8 không được đổi tên hay xóa
-keep class org.mozilla.** { *; }
-keep interface org.mozilla.** { *; }
-keepclasseswithmembers class * {
    @org.mozilla.gecko.annotation.WrapForJNI <methods>;
}
-keepclasseswithmembers class * {
    @org.mozilla.gecko.annotation.WrapForJNI <fields>;
}
-keepclasseswithmembers class * {
    native <methods>;
}
-keep class com.desktopmini.web.** extends android.app.Activity
