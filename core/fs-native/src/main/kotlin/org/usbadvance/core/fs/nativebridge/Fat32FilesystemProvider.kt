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
    override val displayName: String = "FAT32 (Compatibilidade Máxima)"
    override val description: String = "Compatível com quase todos os sistemas operacionais, TVs e consoles. Limite de 4 GB por arquivo individual."
    override val isRootRequired: Boolean = false
    override val supportedClusterSizes: List<Int> = listOf(4096, 8192, 16384, 32768, 65536)
    override val defaultClusterSize: Int = 32768
    override val maxVolumeLabelLength: Int = 11
    override val supportsVolumeLabel: Boolean = true

    override fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (options.volumeLabel.length > maxVolumeLabelLength) {
            errors.add("O rótulo do volume no FAT32 não pode ultrapassar $maxVolumeLabelLength caracteres.")
        }
        if (diskCapacityBytes < 65525L * 512L) { // ~33 MB mínimo
            errors.add("O tamanho da partição é muito pequeno para o sistema FAT32.")
        }
        if (diskCapacityBytes > 2L * 1024 * 1024 * 1024 * 1024) {
            warnings.add("Partições FAT32 maiores que 2 TB podem apresentar incompatibilidade com certos sistemas.")
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
