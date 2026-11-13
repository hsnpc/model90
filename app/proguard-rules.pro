# WebView JavascriptInterface 메서드가 릴리스(R8) 빌드에서 난독화/제거되지 않도록 보호
-keepattributes JavascriptInterface
-keepattributes *Annotation*

-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.model90.metronome.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
