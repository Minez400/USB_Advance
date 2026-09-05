package org.usbadvance.core.fs.nativebridge

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions
import org.usbadvance.core.storage.model.FormatProgressCallback
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.core.storage.model.ValidationResult
import org.usbadvance.core.storage.provider.FilesystemProvider

class ExFatFilesystemProvider : FilesystemProvider {
    override val id: String = "exfat"
    override val filesystemType: FilesystemType = FilesystemType.EXFAT
    override val displayName: String = "exFAT"
    override val description: String = "Modern standard for flash drives and external disks. Supports individual files larger than 4 GB."
    override val isRootRequired: Boolean = false
    override val supportedClusterSizes: List<Int> = listOf(4096, 8192, 16384, 32768, 65536, 131072, 262144)
    override val defaultClusterSize: Int = 32768
    override val maxVolumeLabelLength: Int = 11
    override val supportsVolumeLabel: Boolean = true

    override fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()
        val errorKeys = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val warningKeys = mutableListOf<String>()

        if (options.volumeLabel.length > maxVolumeLabelLength) {
            errors.add("Volume label cannot exceed $maxVolumeLabelLength characters for exFAT.")
            errorKeys.add("validation_err_label_too_long")
        }
        if (diskCapacityBytes < 10L * 1024 * 1024) { // 10 MB
            errors.add("Partition size is too small for exFAT (minimum 10 MB required).")
            errorKeys.add("validation_err_partition_too_small_exfat")
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
