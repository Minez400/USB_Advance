package org.usbadvance.core.storage.model

/**
 * Detailed configuration parameters for formatting and partition operations.
 */
data class FormatOptions(
    val filesystemType: FilesystemType,
    val partitionTableType: PartitionTableType = PartitionTableType.MBR,
    val volumeLabel: String = "USB_DRIVE",
    val clusterSizeBytes: Int = 0, // 0 = Automatic selection based on volume capacity
    val quickFormat: Boolean = true,
    val wipeSectors: Boolean = false, // Zero out entire data area (0x00 wipe)
    val align1MiB: Boolean = true, // Force 1 MiB alignment at LBA 2048 to preserve Flash NAND erase blocks
    val customUuid: String? = null,
    val disableJournal: Boolean = false // Specific to ext4 to reduce Flash write endurance cycles
) {
    companion object {
        fun defaultFor(fs: FilesystemType): FormatOptions {
            return FormatOptions(
                filesystemType = fs,
                partitionTableType = PartitionTableType.MBR,
                volumeLabel = when (fs) {
                    FilesystemType.FAT16 -> "FAT16_DISK"
                    FilesystemType.FAT32 -> "USB_ADVANCE"
                    FilesystemType.EXFAT -> "EXFAT_DRIVE"
                    FilesystemType.EXT4 -> "EXT4_LINUX"
                    FilesystemType.NTFS -> "NTFS_DRIVE"
                }
            )
        }
    }
}
