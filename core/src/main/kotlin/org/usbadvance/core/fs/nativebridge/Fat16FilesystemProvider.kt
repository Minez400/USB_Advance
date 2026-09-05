package org.usbadvance.core.fs.nativebridge

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions
import org.usbadvance.core.storage.model.FormatProgressCallback
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.core.storage.model.ValidationResult
import org.usbadvance.core.storage.provider.FilesystemProvider

class Fat16FilesystemProvider : FilesystemProvider {
    override val id: String = "fat16"
    override val filesystemType: FilesystemType = FilesystemType.FAT16
    override val displayName: String = "FAT16"
    override val description: String = "For legacy electronics, vintage synthesizers, CNC machines, and older media under 2 GB."
    override val isRootRequired: Boolean = false
    override val supportedClusterSizes: List<Int> = listOf(2048, 4096, 8192, 16384, 32768, 65536)
    override val defaultClusterSize: Int = 16384
    override val maxVolumeLabelLength: Int = 11
    override val supportsVolumeLabel: Boolean = true

    override fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()
        val errorKeys = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val warningKeys = mutableListOf<String>()

        if (options.volumeLabel.length > maxVolumeLabelLength) {
            errors.add("Volume label cannot exceed $maxVolumeLabelLength characters for FAT16.")
            errorKeys.add("validation_err_label_too_long")
        }
        if (diskCapacityBytes > 4L * 1024 * 1024 * 1024) {
            errors.add("FAT16 supports a maximum partition capacity of 4 GB.")
            errorKeys.add("validation_err_fat16_over_4gb")
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
