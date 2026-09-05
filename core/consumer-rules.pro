# Regras ProGuard para o módulo :core
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.usbadvance.core.fs.nativebridge.** { *; }
-keep class org.usbadvance.core.storage.model.** { *; }
-keep class org.usbadvance.core.storage.api.** { *; }
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
