package org.usbadvance.core.storage.model

/**
 * Parâmetros de configuração detalhados para a operação de formatação e particionamento.
 */
data class FormatOptions(
    val filesystemType: FilesystemType,
    val partitionTableType: PartitionTableType = PartitionTableType.MBR,
    val volumeLabel: String = "USB_DRIVE",
    val clusterSizeBytes: Int = 0, // 0 = Seleção automática baseada na capacidade
    val quickFormat: Boolean = true,
    val wipeSectors: Boolean = false, // Gravação de 0x00 em toda a área de dados
    val align1MiB: Boolean = true, // Força alinhamento no LBA 2048 para preservação da memória Flash
    val customUuid: String? = null,
    val disableJournal: Boolean = false // Relevante para ext4 (reduz ciclos de escrita na Flash)
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
