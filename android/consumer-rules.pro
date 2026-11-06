# TTLock Android SDK (ttlock-release.aar is already obfuscated; do not re-shrink).
# Without these rules, Flutter release (minifyEnabled=true) can strip / re-obfuscate
# SDK classes so BluetoothImpl adapter stays null → BLE_SERVER_NOT_INIT → "蓝牙未开启".
-keep class com.ttlock.** { *; }
-keepclassmembers class com.ttlock.** { *; }
-dontwarn com.ttlock.**

# Plugin host / Pigeon generated code
-keep class com.ttlock.ttlock_flutter.** { *; }
-keepclassmembers class com.ttlock.ttlock_flutter.** { *; }

# Gson models used by SDK / plugin JSON bridges.
# R8 full mode drops the generic signature of TypeToken subclasses even when
# Signature is kept, then `new TypeToken<...>()` crashes in release.
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**

# OkHttp (SDK networking)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Nordic DFU (declared by official ttlock; flatDir AAR does not pull consumer rules)
-dontwarn no.nordicsemi.android.**
-keep class no.nordicsemi.android.** { *; }
