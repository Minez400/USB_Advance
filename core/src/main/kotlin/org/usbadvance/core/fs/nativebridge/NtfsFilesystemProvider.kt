package org.usbadvance.core.fs.nativebridge

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions
import org.usbadvance.core.storage.model.FormatProgressCallback
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.core.storage.model.ValidationResult
import org.usbadvance.core.storage.provider.FilesystemProvider

/**
 * High-performance rootless NTFS filesystem provider for USB Advance.
 * Dispatches formatting directly through C++20 NtfsFormatter over JNI.
 */
class NtfsFilesystemProvider : FilesystemProvider {
    override val id: String = "ntfs"
    override val filesystemType: FilesystemType = FilesystemType.NTFS
    override val displayName: String = "NTFS"
    override val description: String = "Standard Windows NT file system. Supports large files, journaling, and high reliability."
    override val isRootRequired: Boolean = false
    override val supportedClusterSizes: List<Int> = listOf(512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
    override val defaultClusterSize: Int = 4096
    override val maxVolumeLabelLength: Int = 32
    override val supportsVolumeLabel: Boolean = true

    override fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()
        val errorKeys = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val warningKeys = mutableListOf<String>()

        if (options.volumeLabel.length > maxVolumeLabelLength) {
            errors.add("Volume label cannot exceed $maxVolumeLabelLength characters for NTFS.")
            errorKeys.add("validation_err_label_too_long")
        }
        if (diskCapacityBytes < 10L * 1024 * 1024) { // 10 MB minimum
            errors.add("Partition size is too small for NTFS (minimum 10 MB required).")
            errorKeys.add("validation_err_partition_too_small")
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
