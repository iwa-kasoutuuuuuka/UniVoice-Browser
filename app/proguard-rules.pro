# Proguard rules for UniVoice Browser
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Retrofit / Gson models
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.univoice.browser.model.** { *; }

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
