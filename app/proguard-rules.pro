# Keep WebView JavascriptInterface methods from being obfuscated or stripped by R8 in release builds
-keepattributes JavascriptInterface
-keepattributes *Annotation*

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.model90.metronome.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
