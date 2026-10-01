# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /path-to-android-sdk/tools/proguard/proguard-android.txt

# Keep OkHttp classes
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep app classes
-keep class cyou.famflow.smsforwarder.** { *; }
