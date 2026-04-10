# Add project specific ProGuard rules here.
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes for API
-keep class com.livesubtitle.OpenAIClient$* { *; }
