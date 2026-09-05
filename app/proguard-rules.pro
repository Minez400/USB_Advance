# USB Advance ProGuard & R8 Optimization Rules

# Preserve native JNI methods and native bridge
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.usbadvance.core.fs.nativebridge.** { *; }

# Preserve storage models and geometry
-keep class org.usbadvance.core.storage.model.** { *; }
-keep class org.usbadvance.core.storage.api.** { *; }

# Preserve Libsu root execution components
-keep class com.topjohnwu.superuser.** { *; }

# Coroutines and reflection attributes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn com.topjohnwu.superuser.**
