package org.usbadvance.core.storage.provider

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions
import org.usbadvance.core.storage.model.FormatProgressCallback
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.core.storage.model.ValidationResult

/**
 * Service Provider Interface (SPI) para desacoplar e plugar sistemas de arquivos.
 * Permite adicionar novos formatos (ex: FAT32, exFAT, ext4, F2FS, Btrfs)
 * sem modificar a interface de usuário ou os controladores de barramento USB.
 */
interface FilesystemProvider {
    val id: String
    val filesystemType: FilesystemType
    val displayName: String
    val description: String
    val isRootRequired: Boolean
    val supportedClusterSizes: List<Int> // Lista de tamanhos de cluster em bytes (ex: 4096, 8192, 16384, 32768, 65536)
    val defaultClusterSize: Int
    val maxVolumeLabelLength: Int
    val supportsVolumeLabel: Boolean

    /**
     * Valida se os parâmetros fornecidos são adequados para este sistema de arquivos e tamanho de disco.
     */
    fun validateOptions(options: FormatOptions, diskCapacityBytes: Long): ValidationResult

    /**
     * Executa a formatação física e lógica da partição ou unidade especificada.
     */
    suspend fun format(
        blockDevice: IBlockDevice,
        partition: IPartition,
        options: FormatOptions,
        progressCallback: FormatProgressCallback
    ): FormatResult
}

/**
 * Registro central de provedores de sistema de arquivos disponíveis na aplicação.
 */
object FilesystemRegistry {
    private val providers = mutableMapOf<FilesystemType, FilesystemProvider>()

    fun register(provider: FilesystemProvider) {
        providers[provider.filesystemType] = provider
    }

    fun get(fsType: FilesystemType): FilesystemProvider? = providers[fsType]

    fun getAll(): List<FilesystemProvider> = providers.values.toList()

    fun isSupported(fsType: FilesystemType): Boolean = providers.containsKey(fsType)
}
