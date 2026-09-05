package org.usbadvance.core.fs.nativebridge

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions
import org.usbadvance.core.storage.model.FormatProgressCallback
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.core.storage.model.ValidationResult
import org.usbadvance.core.storage.provider.FilesystemProvider

class Fat32FilesystemProvider : FilesystemProvider {
    override val id: String = "fat32"
    override val filesystemType: FilesystemType = FilesystemType.FAT32
    override val displayName: String = "FAT32"
    override val description: String = "Maximum compatibility across PCs, consoles, TVs, and car stereos. 4 GB individual file size limit."
    override val isRootRequired: Boolean = false
    override val supportedClusterSizes: List<Int> = listOf(4096, 8192, 16384, 32768, 65536)
    override val defaultClusterSize: Int = 32768
    override val maxVolumeLabelLength: Int = 11
    override val supportsVolumeLabel: Boolean = true

    override fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()
        val errorKeys = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val warningKeys = mutableListOf<String>()

        if (options.volumeLabel.length > maxVolumeLabelLength) {
            errors.add("Volume label cannot exceed $maxVolumeLabelLength characters for FAT32.")
            errorKeys.add("validation_err_label_too_long")
        }
        if (diskCapacityBytes < 65525L * 512L) { // ~33 MB minimum
            errors.add("Partition size is too small for FAT32 (minimum 33 MB required).")
            errorKeys.add("validation_err_partition_too_small_fat32")
        }
        if (diskCapacityBytes > 2L * 1024 * 1024 * 1024 * 1024) {
            warnings.add("FAT32 partitions larger than 2 TB may not be compatible with certain operating systems.")
            warningKeys.add("validation_warn_fat32_over_2tb")
        }

        return if (errors.isEmpty()) {
            ValidationResult.valid(warnings, warningKeys)
        } else {
            ValidationResult.invalidWithKeys(errors, errorKeys, warnings, warningKeys)
        }
    }

    override suspend fun format(
        blockDevice: IBlockDevice,
        partition: IPartition,
        options: FormatOptions,
        progressCallback: FormatProgressCallback
    ): FormatResult {
        return NativeFormatBridge.executeFormat(
            fsType = filesystemType,
            blockDevice = blockDevice,
            partition = partition,
            options = options,
            progressCallback = progressCallback
        )
    }
}
