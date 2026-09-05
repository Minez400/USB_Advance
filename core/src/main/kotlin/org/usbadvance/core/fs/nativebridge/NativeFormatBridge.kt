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
 * JNI Bridge between Kotlin storage orchestration layers and high-performance native C++20 formatting engines.
 * Dispatches sector write callbacks from C++ directly to IBlockDevice via NativeIoCallback.
 */
object NativeFormatBridge {

    init {
        try {
            System.loadLibrary("fsnative")
        } catch (e: UnsatisfiedLinkError) {
            // In pure JVM unit test environments without Android NDK, native lib may not be present
            System.err.println("Warning: fsnative library not loaded (running in pure JVM test environment): ${e.message}")
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
     * Executes native filesystem formatting via the C++20 engine over JNI.
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

                    // Dispatch to IBlockDevice coroutine
                    kotlinx.coroutines.runBlocking {
                        blockDevice.writeSectors(lba, count, buffer)
                    }
                    totalBytesWritten += data.size
                    true
                } catch (e: Exception) {
                    System.err.println("JNI block write error at LBA $lba: ${e.message}")
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
                stageDescription = "Initializing native C++20 engine...",
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
                errorMessage = "Native library fsnative not available: ${e.message}",
                cause = e
            )
        }

        if (!success) {
            return FormatResult.Failure(
                errorCode = ErrorCode.IO_ERROR,
                errorMessage = "Failure during writing of filesystem metadata structures."
            )
        }

        // Flush physical hardware caches
        progressCallback.onProgress(
            FormatProgress(
                stage = FormatStage.SYNCHRONIZING_CACHE,
                stageDescription = "Flushing Flash hardware cache (SCSI SYNC)...",
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
