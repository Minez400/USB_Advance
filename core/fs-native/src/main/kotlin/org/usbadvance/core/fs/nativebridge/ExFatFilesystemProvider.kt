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
    override val displayName: String = "exFAT (Moderno / Arquivos > 4 GB)"
    override val description: String = "Ideal para pendrives e SSDs modernos. Permite arquivos de qualquer tamanho e é amplamente suportado por Windows, macOS e Android 13+."
    override val isRootRequired: Boolean = false
    override val supportedClusterSizes: List<Int> = listOf(4096, 8192, 16384, 32768, 65536, 131072, 262144)
    override val defaultClusterSize: Int = 32768
    override val maxVolumeLabelLength: Int = 11
    override val supportsVolumeLabel: Boolean = true

    override fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (options.volumeLabel.length > maxVolumeLabelLength) {
            errors.add("O rótulo do volume no exFAT não pode ultrapassar $maxVolumeLabelLength caracteres.")
        }
        if (diskCapacityBytes < 10L * 1024 * 1024) { // 10 MB
            errors.add("O tamanho da partição é muito pequeno para estruturar o exFAT.")
        }

        return if (errors.isEmpty()) ValidationResult.valid(warnings) else ValidationResult(false, errors, warnings)
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
