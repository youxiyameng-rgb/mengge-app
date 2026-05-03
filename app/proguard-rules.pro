# ======================== 通用 Android / Kotlin ========================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(...);
}

# ======================== OkHttp3 ========================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.** { *; }
-keepclassmembers class * implements okhttp3.Interceptor {
    *;
}

# ======================== ViewBinding ========================
-keep class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(android.view.LayoutInflater);
    public static * inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
    public static * bind(android.view.View);
}

# ======================== Navigation ========================
-keepnames class androidx.navigation.fragment.NavHostFragment
-keep class * extends androidx.fragment.app.Fragment{}
-keepnames class * extends android.os.Parcelable
-keepnames class * extends java.io.Serializable

# ======================== 数据类（JSON 序列化/反序列化） ========================
-keep class com.aivoice.app.api.ApiClient$VoicePreset { *; }
-keep class com.aivoice.app.api.ApiClient$CoverResult { *; }
-keep class com.aivoice.app.model.** { *; }

# ======================== App 入口 / Activity / Fragment ========================
-keep class com.aivoice.app.App { *; }
-keep class com.aivoice.app.MainActivity { *; }
-keep class com.aivoice.app.ui.** { *; }

# ======================== Retrofit / OkHttp CallAdapter（未来兼容） ========================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# ======================== Coroutines ========================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}

# ======================== Material / AndroidX ========================
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# ======================== R8 安全模式 ========================
-keepattributes EnclosingMethod
-keepattributes InnerClasses
