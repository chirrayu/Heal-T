# ProGuard / R8 Rules for Triage AI Health Monitor

# ── General Optimization & Reflection Attributes ───────────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes SourceFile, LineNumberTable

# ── Health Monitor Data Models & Entities ──────────────────────
# Preserve serializable data classes and models against R8 name obfuscation
-keep class com.example.healthmonitor.Soldier { *; }
-keep class com.example.healthmonitor.AppAlert { *; }
-keep class com.example.healthmonitor.SuitConfig { *; }
-keep class com.example.healthmonitor.MapUpdate { *; }
-keep class com.example.healthmonitor.MedicalRecord { *; }
-keep class com.example.healthmonitor.StatusSummaryItem { *; }

# Preserve App state holders
-keep class com.example.healthmonitor.AppState { *; }
-keep class com.example.healthmonitor.AlertState { *; }
-keep class com.example.healthmonitor.SoldierState { *; }
-keep class com.example.healthmonitor.LiveMapState { *; }
-keep class com.example.healthmonitor.TokenStorage { *; }
-keep class com.example.healthmonitor.NetworkConfig { *; }

# ── Gson Serializer Rules ──────────────────────────────────────
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ── OkHttp & Okio Network Security & TLS ───────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# ── AndroidX Security Crypto & Keystore ────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Kotlin Coroutines & Jetpack Compose ────────────────────────
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }