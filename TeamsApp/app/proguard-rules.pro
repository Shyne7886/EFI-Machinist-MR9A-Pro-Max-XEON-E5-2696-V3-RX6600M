# Add project specific ProGuard rules here.

# Keep WebView JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep DownloadReceiver
-keep class com.teams.webApp.DownloadReceiver { *; }

# AndroidX
-keep class androidx.** { *; }

# Kotlin
-dontwarn kotlin.**
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
