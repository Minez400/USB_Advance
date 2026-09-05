package org.usbadvance.core.storage.model

/**
 * Sistemas de arquivos suportados pelo USB Advance.
 */
enum class FilesystemType(
    val id: String,
    val displayName: String,
    val isRootRequired: Boolean,
    val maxFileSizeBytes: Long,
    val maxVolumeSizeBytes: Long
) {
    FAT16(
        id = "fat16",
        displayName = "FAT16 (Mídias antigas / Legado)",
        isRootRequired = false,
        maxFileSizeBytes = 2L * 1024 * 1024 * 1024, // 2 GB
        maxVolumeSizeBytes = 4L * 1024 * 1024 * 1024 // 4 GB (com clusters de 64 KB)
    ),
    FAT32(
        id = "fat32",
        displayName = "FAT32 (Universal / Compatibilidade Máxima)",
        isRootRequired = false,
        maxFileSizeBytes = 4L * 1024 * 1024 * 1024 - 1, // 4 GB - 1 byte
        maxVolumeSizeBytes = 2L * 1024 * 1024 * 1024 * 1024 // 2 TB practical limit
    ),
    EXFAT(
        id = "exfat",
        displayName = "exFAT (Moderno / Arquivos Grandes)",
        isRootRequired = false,
        maxFileSizeBytes = Long.MAX_VALUE,
        maxVolumeSizeBytes = Long.MAX_VALUE
    ),
    EXT4(
        id = "ext4",
        displayName = "ext4 (Linux / Android Avançado)",
        isRootRequired = false, // Nosso motor NDK grava via USB Host sem root!
        maxFileSizeBytes = 16L * 1024 * 1024 * 1024 * 1024, // 16 TB
        maxVolumeSizeBytes = 1L * 1024 * 1024 * 1024 * 1024 * 1024 * 1024 // 1 EB
    ),
    NTFS(
        id = "ntfs",
        displayName = "NTFS (Windows NT)",
        isRootRequired = false,
        maxFileSizeBytes = Long.MAX_VALUE,
        maxVolumeSizeBytes = Long.MAX_VALUE
    );

    companion object {
        fun fromId(id: String): FilesystemType? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
