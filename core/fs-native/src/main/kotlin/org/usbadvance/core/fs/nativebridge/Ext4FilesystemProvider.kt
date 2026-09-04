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
    override val displayName: String = "ext4 (Linux / Android Avançado)"
    override val description: String = "Sistema de arquivos padrão do ecossistema Linux. Excelente integridade de dados e permissões POSIX nativas."
    override val isRootRequired: Boolean = false // Written directly via userspace USB Host BOT without root requirement!
    override val supportedClusterSizes: List<Int> = listOf(1024, 2048, 4096)
    override val defaultClusterSize: Int = 4096
    override val maxVolumeLabelLength: Int = 16
    override val supportsVolumeLabel: Boolean = true

    override fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (options.volumeLabel.length > maxVolumeLabelLength) {
            errors.add("O rótulo do volume no ext4 não pode ultrapassar $maxVolumeLabelLength caracteres.")
        }
        if (diskCapacityBytes < 32L * 1024 * 1024) {
            errors.add("O tamanho mínimo recomendado para criar um volume ext4 é 32 MB.")
        }
        warnings.add("Avisos: certifique-se de que seu receptor (TV ou computador) possui suporte a partições ext4.")

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
