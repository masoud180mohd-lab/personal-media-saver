# Personal Media Saver R8 / Proguard Rules

# Hilt rules
-keep,allowobfuscation,allowshrinking class com.mediasaver.MediaSaverApplication
-keep,allowobfuscation,allowshrinking class com.mediasaver.MainActivity

# Room rules
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }

# Coil
-dontwarn coil.**
