package org.usbadvance.feature.formatter.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.IBlockDevice
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

data class IsoBurnProgress(
    val progressPct: Float,
    val speedMbPerSec: Double,
    val remainingSeconds: Long,
    val bytesWritten: Long,
    val totalBytes: Long,
    val stageMessage: String
)

data class IsoBurnResult(
    val success: Boolean,
    val totalBytesWritten: Long,
    val durationSeconds: Double,
    val averageSpeedMbPerSec: Double,
    val errorMessage: String? = null
)

/**
 * Raw disk image writer engine (Rufus / Etcher style) for writing OS installation media (.iso, .img)
 * directly to physical sectors of target USB storage devices over BOT/SCSI.
 */
class IsoWriterEngine {

    companion object {
        /**
         * Resolves display name and file size via ContentResolver and OpenableColumns / PFD statSize.
         */
        fun queryFileInfo(contentResolver: ContentResolver, uri: Uri): Pair<String, Long> {
            var displayName = "image.iso"
            var sizeBytes = 0L

            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            cursor.getString(nameIndex)?.let { displayName = it }
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            sizeBytes = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {}

            if (sizeBytes <= 0L) {
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        sizeBytes = pfd.statSize
                    }
                } catch (_: Exception) {}
            }

            return Pair(displayName, sizeBytes)
        }
    }

    /**
     * Burns disk image from URI directly to block device sectors.
     * Uses 1 MB aligned batches to maximize USB bus throughput and flushes device cache upon completion.
     */
    suspend fun burnImage(
        contentResolver: ContentResolver,
        imageUri: Uri,
        imageSizeBytes: Long,
        blockDevice: IBlockDevice,
        onProgress: (IsoBurnProgress) -> Unit
    ): IsoBurnResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var totalWritten = 0L

        try {
            val sectorSize = blockDevice.sectorSize
            // 1 MB per USB BOT transaction to maximize bus throughput
            val maxChunkBytes = 1048576
            val sectorsPerChunk = maxOf(1, maxChunkBytes / sectorSize)
            val chunkSizeBytes = sectorsPerChunk * sectorSize

            if (imageSizeBytes > blockDevice.capacityBytes) {
                val imgMb = imageSizeBytes / (1024 * 1024)
                val devMb = blockDevice.capacityBytes / (1024 * 1024)
                return@withContext IsoBurnResult(
                    success = false,
                    totalBytesWritten = 0,
                    durationSeconds = 0.0,
                    averageSpeedMbPerSec = 0.0,
                    errorMessage = "Image ($imgMb MB) exceeds drive capacity ($devMb MB)."
                )
            }

            if (blockDevice.isWriteProtected()) {
                return@withContext IsoBurnResult(
                    success = false,
                    totalBytesWritten = 0,
                    durationSeconds = 0.0,
                    averageSpeedMbPerSec = 0.0,
                    errorMessage = "Device is write-protected (physical switch or read-only flag enabled)."
                )
            }

            onProgress(
                IsoBurnProgress(
                    progressPct = 0f,
                    speedMbPerSec = 0.0,
                    remainingSeconds = 0,
                    bytesWritten = 0,
                    totalBytes = imageSizeBytes,
                    stageMessage = "Flashing image to drive..."
                )
            )

            val rawBuffer = ByteArray(chunkSizeBytes)
            val byteBuffer = ByteBuffer.allocateDirect(chunkSizeBytes)
            var currentLba = 0L
            var lastSpeedUpdate = startTime
            var bytesSinceLastUpdate = 0L
            var currentSpeedMb = 0.0

            contentResolver.openInputStream(imageUri)?.use { inputStream ->
                while (true) {
                    coroutineContext.ensureActive()

                    var bytesAccumulated = 0
                    while (bytesAccumulated < chunkSizeBytes) {
                        val read = inputStream.read(rawBuffer, bytesAccumulated, chunkSizeBytes - bytesAccumulated)
                        if (read < 0) break
                        bytesAccumulated += read
                    }
                    if (bytesAccumulated <= 0) break

                    // Pad the trailing incomplete sector with zeros ONLY at EOF if needed
                    val paddedBytes = ((bytesAccumulated + sectorSize - 1) / sectorSize) * sectorSize
                    if (paddedBytes > bytesAccumulated) {
                        java.util.Arrays.fill(rawBuffer, bytesAccumulated, paddedBytes, 0.toByte())
                    }

                    byteBuffer.clear()
                    byteBuffer.put(rawBuffer, 0, paddedBytes)
                    byteBuffer.flip()

                    val sectorsToWrite = paddedBytes / sectorSize
                    blockDevice.writeSectors(currentLba, sectorsToWrite, byteBuffer)

                    currentLba += sectorsToWrite
                    totalWritten += bytesAccumulated
                    bytesSinceLastUpdate += bytesAccumulated

                    val now = System.currentTimeMillis()
                    val timeDelta = now - lastSpeedUpdate
                    if (timeDelta >= 300 || totalWritten >= imageSizeBytes) {
                        currentSpeedMb = (bytesSinceLastUpdate / (1024.0 * 1024.0)) / maxOf(0.001, timeDelta / 1000.0)
                        lastSpeedUpdate = now
                        bytesSinceLastUpdate = 0L

                        val progressPct = if (imageSizeBytes > 0) {
                            (totalWritten.toFloat() / imageSizeBytes.toFloat()) * 100f
                        } else 0f

                        val remainingBytes = maxOf(0L, imageSizeBytes - totalWritten)
                        val remainingSec = if (currentSpeedMb > 0.01) {
                            (remainingBytes / (currentSpeedMb * 1024.0 * 1024.0)).toLong()
                        } else 0L

                        onProgress(
                            IsoBurnProgress(
                                progressPct = minOf(99f, progressPct),
                                speedMbPerSec = currentSpeedMb,
                                remainingSeconds = remainingSec,
                                bytesWritten = totalWritten,
                                totalBytes = imageSizeBytes,
                                stageMessage = "Gravando imagem no pendrive..."
                            )
                        )
                    }
                }
            } ?: return@withContext IsoBurnResult(
                success = false,
                totalBytesWritten = 0,
                durationSeconds = 0.0,
                averageSpeedMbPerSec = 0.0,
                errorMessage = "Could not open the selected image file."
            )

            // Flush USB volatile caches to physical Flash NAND
            onProgress(
                IsoBurnProgress(
                    progressPct = 99.5f,
                    speedMbPerSec = currentSpeedMb,
                    remainingSeconds = 0,
                    bytesWritten = totalWritten,
                    totalBytes = imageSizeBytes,
                    stageMessage = "Flushing Flash memory cache (SCSI SYNC)..."
                )
            )
            blockDevice.sync()

            val elapsedSec = maxOf(0.001, (System.currentTimeMillis() - startTime) / 1000.0)
            val avgSpeed = (totalWritten / (1024.0 * 1024.0)) / elapsedSec

            onProgress(
                IsoBurnProgress(
                    progressPct = 100f,
                    speedMbPerSec = avgSpeed,
                    remainingSeconds = 0,
                    bytesWritten = totalWritten,
                    totalBytes = imageSizeBytes,
                    stageMessage = "Flashing completed successfully!"
                )
            )

            IsoBurnResult(
                success = true,
                totalBytesWritten = totalWritten,
                durationSeconds = elapsedSec,
                averageSpeedMbPerSec = avgSpeed
            )
        } catch (e: Exception) {
            val elapsedSec = maxOf(0.001, (System.currentTimeMillis() - startTime) / 1000.0)
            IsoBurnResult(
                success = false,
                totalBytesWritten = totalWritten,
                durationSeconds = elapsedSec,
                averageSpeedMbPerSec = 0.0,
                errorMessage = e.message ?: "Unknown error during flashing."
            )
        }
    }
}
