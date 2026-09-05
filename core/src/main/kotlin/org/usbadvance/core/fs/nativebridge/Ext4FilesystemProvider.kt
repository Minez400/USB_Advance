package org.usbadvance.core.fs.nativebridge

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions
import org.usbadvance.core.storage.model.FormatProgressCallback
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.core.storage.model.ValidationResult
import org.usbadvance.core.storage.provider.FilesystemProvider

class Ext4FilesystemProvider : FilesystemProvider {
    override val id: String = "ext4"
    override val filesystemType: FilesystemType = FilesystemType.EXT4
    override val displayName: String = "ext4"
    override val description: String = "Standard Linux filesystem. Robust journaling, POSIX permissions, and high data integrity."
    override val isRootRequired: Boolean = false // Written directly via userspace USB Host BOT without root requirement!
    override val supportedClusterSizes: List<Int> = listOf(1024, 2048, 4096)
    override val defaultClusterSize: Int = 4096
    override val maxVolumeLabelLength: Int = 16
    override val supportsVolumeLabel: Boolean = true

    override fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()
        val errorKeys = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val warningKeys = mutableListOf<String>()

        if (options.volumeLabel.length > maxVolumeLabelLength) {
            errors.add("Volume label cannot exceed $maxVolumeLabelLength characters for ext4.")
            errorKeys.add("validation_err_label_too_long")
        }
        if (diskCapacityBytes < 32L * 1024 * 1024) {
            errors.add("Minimum partition size for ext4 is 32 MB.")
            errorKeys.add("validation_err_partition_too_small_ext4")
        }
        warnings.add("Ensure your destination device (computer, TV, media center) supports ext4 partitions.")
        warningKeys.add("validation_warn_ext4_compatibility")

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
