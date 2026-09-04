# Regras ProGuard específicas para o USB Advance
-keep class org.usbadvance.core.fs.nativebridge.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
