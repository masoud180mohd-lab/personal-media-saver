# ==============================================================================
# Personal Media Saver — Production R8 / ProGuard Configuration
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. Zero-Telemetry & Dead-Code Elimination: Strip all debug logging
# ------------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int wtf(...);
}

-assumenosideeffects class com.mediasaver.core.common.AppLogger {
    public static void d(...);
    public static void i(...);
    public static void w(...);
    public static void e(...);
}

# ------------------------------------------------------------------------------
# 2. General Reflection, Annotations, and Attributes
# ------------------------------------------------------------------------------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# ------------------------------------------------------------------------------
# 3. NewPipe Extractor & HTML Parsing (Jsoup)
# ------------------------------------------------------------------------------
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# Rhino / Javascript interpreter if loaded dynamically by NewPipe Extractor
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }

# ------------------------------------------------------------------------------
# 4. FFmpegKit & JNI Native Boundaries
# ------------------------------------------------------------------------------
-keep class com.arthenica.ffmpegkit.** { *; }
-keepclassmembers class com.arthenica.ffmpegkit.** {
    native <methods>;
    public <fields>;
    public <methods>;
}
-dontwarn com.arthenica.ffmpegkit.**

# ------------------------------------------------------------------------------
# 5. Room Database, Entities, and DAOs
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class * extends androidx.room.TypeConverter
-dontwarn androidx.room.paging.**

# ------------------------------------------------------------------------------
# 6. Jetpack Compose Runtime & State
# ------------------------------------------------------------------------------
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
-dontwarn androidx.compose.**

# ------------------------------------------------------------------------------
# 7. OkHttp, Okio, and Coroutines
# ------------------------------------------------------------------------------
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.coroutines.**

# ------------------------------------------------------------------------------
# 8. Coil Image Loading
# ------------------------------------------------------------------------------
-dontwarn coil.**
-keep class coil.** { *; }

# ------------------------------------------------------------------------------
# 9. Hilt & Dependency Injection
# ------------------------------------------------------------------------------
-keep class * extends android.app.Application
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends androidx.work.ListenableWorker
-keep class * implements dagger.hilt.internal.GeneratedComponent
-keep class * implements dagger.hilt.internal.ComponentManager
