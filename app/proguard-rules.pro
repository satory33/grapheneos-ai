# ProGuard rules for AI Assistant
# Optimized for security and minimal APK size

# Keep native method names for JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Whisper JNI interface
-keep class com.vincent.ai_integrated_into_android.audio.WhisperJNI {
    native <methods>;
    *;
}

# Keep data classes for JSON serialization
-keep class com.vincent.ai_integrated_into_android.search.SearchResult { *; }

# Keep service classes
-keep class com.vincent.ai_integrated_into_android.service.** { *; }

# Keep accessibility service
-keep class * extends android.accessibilityservice.AccessibilityService

# Keep voice interaction services
-keep class * extends android.service.voice.VoiceInteractionService
-keep class * extends android.service.voice.VoiceInteractionSessionService
-keep class * extends android.service.voice.VoiceInteractionSession

# Keep tile service
-keep class * extends android.service.quicksettings.TileService

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Security - don't obfuscate crypto classes
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keep class android.security.keystore.** { *; }

# PDFBox dependencies
-dontwarn com.gemalto.jp2.**
-keep class com.gemalto.jp2.** { *; }
-dontwarn org.apache.pdfbox.**
-keep class org.apache.pdfbox.** { *; }
-keep class com.tom_roush.pdfbox.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Optimization
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Preserve line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile