# ProGuard rules for FocusGuard

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep Compose
-keep class androidx.compose.** { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Shizuku / Dhizuku / HiddenApiBypass：服务端类名通过 Class.forName 反射调用，
# 混淆后类名对不上会导致增强功能静默失效（禁调试、冻结、隐藏）。
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
-keep class com.rosan.dhizuku.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn moe.shizuku.**
-dontwarn rikka.shizuku.**
-dontwarn com.rosan.dhizuku.**
-dontwarn org.lsposed.hiddenapibypass.**
