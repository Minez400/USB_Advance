package org.usbadvance.core.fs.nativebridge

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.ErrorCode
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions
import org.usbadvance.core.storage.model.FormatProgress
import org.usbadvance.core.storage.model.FormatProgressCallback
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.core.storage.model.FormatStage
import java.nio.ByteBuffer

/**
 * Ponte JNI entre o subsistema Kotlin e os motores de formatação nativos C++20.
 */
object NativeFormatBridge {

    init {
        try {
            System.loadLibrary("fsnative")
        } catch (e: UnsatisfiedLinkError) {
            // Em testes unitários na JVM sem NDK, a biblioteca nativa pode não estar presente
            System.err.println("Aviso: fsnative não carregada (ambiente JVM puro): ${e.message}")
        }
    }

    private external fun nativeFormat(
        fsTypeOrdinal: Int,
        startLba: Long,
        sectorCount: Long,
        sectorSize: Int,
        clusterSizeBytes: Int,
        volumeLabel: String,
        quickFormat: Boolean,
        disableJournal: Boolean,
        callback: NativeIoCallback
    ): Boolean

    /**
     * Executa a formatação nativa chamando o motor C++20 via JNI.
     */
    suspend fun executeFormat(
        fsType: FilesystemType,
        blockDevice: IBlockDevice,
        partition: IPartition,
        options: FormatOptions,
        progressCallback: FormatProgressCallback
    ): FormatResult {
        val startTime = System.currentTimeMillis()
        var totalBytesWritten = 0L

        val callback = object : NativeIoCallback {
            override fun onWriteSectors(lba: Long, count: Int, data: ByteArray): Boolean {
                return try {
                    val buffer = ByteBuffer.wrap(data)

                    // Despacha para a corrotina do IBlockDevice
                    kotlinx.coroutines.runBlocking {
                        blockDevice.writeSectors(lba, count, buffer)
                    }
                    totalBytesWritten += data.size
                    true
                } catch (e: Exception) {
                    System.err.println("Erro na gravação JNI no LBA $lba: ${e.message}")
                    false
                }
            }

            override fun onProgress(percentage: Float, description: String) {
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                val speed = if (elapsedSec > 0) (totalBytesWritten / elapsedSec).toLong() else 0L

                progressCallback.onProgress(
                    FormatProgress(
                        stage = FormatStage.INITIALIZING_METADATA,
                        stageDescription = description,
                        percentage = percentage,
                        bytesProcessed = totalBytesWritten,
                        currentSpeedBytesPerSec = speed
                    )
                )
            }
        }

        progressCallback.onProgress(
            FormatProgress(
                stage = FormatStage.INITIALIZING,
                stageDescription = "Iniciando motor nativo C++20...",
                percentage = 0.0f
            )
        )

        val success = try {
            nativeFormat(
                fsTypeOrdinal = fsType.ordinal,
                startLba = partition.startLba,
                sectorCount = partition.sectorCount,
                sectorSize = blockDevice.sectorSize,
                clusterSizeBytes = options.clusterSizeBytes,
                volumeLabel = options.volumeLabel,
                quickFormat = options.quickFormat,
                disableJournal = options.disableJournal,
                callback = callback
            )
        } catch (e: UnsatisfiedLinkError) {
            return FormatResult.Failure(
                errorCode = ErrorCode.INTERNAL_NATIVE_ERROR,
                errorMessage = "Biblioteca nativa fsnative não disponível: ${e.message}",
                cause = e
            )
        }

        if (!success) {
            return FormatResult.Failure(
                errorCode = ErrorCode.IO_ERROR,
                errorMessage = "Falha durante gravação das estruturas do sistema de arquivos."
            )
        }

        // Garante a sincronização de cache de hardware
        progressCallback.onProgress(
            FormatProgress(
                stage = FormatStage.SYNCHRONIZING_CACHE,
                stageDescription = "Descarregando cache de hardware Flash...",
                percentage = 99.0f
            )
        )
        blockDevice.sync()

        val totalTimeMs = System.currentTimeMillis() - startTime
        val avgSpeed = if (totalTimeMs > 0) (totalBytesWritten * 1000L) / totalTimeMs else 0L

        return FormatResult.Success(
            totalTimeMs = totalTimeMs,
            bytesWritten = totalBytesWritten,
            averageSpeedBytesPerSec = avgSpeed,
            filesystem = fsType,
            partitionTable = partition.partitionTableType,
            volumeLabel = options.volumeLabel
        )
    }
}

interface NativeIoCallback {
    fun onWriteSectors(lba: Long, count: Int, data: ByteArray): Boolean
    fun onProgress(percentage: Float, description: String)
}
