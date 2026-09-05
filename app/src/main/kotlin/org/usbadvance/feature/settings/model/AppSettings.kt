package org.usbadvance.feature.settings.model

/**
 * Immutable user preferences model for USB Advance.
 */
data class AppSettings(
    val languageTag: String = "", // "" = System Default, "en" = English, "pt" = Portuguese
    val defaultFileSystem: String = "exFAT", // "exFAT", "FAT32", "ext4", "FAT16"
    val defaultQuickFormat: Boolean = true,
    val strictSafetyConfirmation: Boolean = true, // If true, requires typing confirmation keyword
    val ioBlockSizeBytes: Int = 1048576, // 512 B to 64 MB (default 1 MB)
    val developerMode: Boolean = false, // FPS, RAM, CPU, GPU overlay
    val enableFakeUsbDrive: Boolean = false // Mock 64 GB USB drive for testing
)
