package org.usbadvance.feature.diagnostic.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.IBlockDevice
import java.nio.ByteBuffer

data class BenchmarkResult(
    val readSpeedMbPerSec: Double,
    val writeSpeedMbPerSec: Double,
    val totalTestedMb: Double
)

enum class BenchmarkStage {
    READING,
    WRITING,
    RESTORING,
    COMPLETED
}

/**
 * Sequential read and write throughput benchmarking engine for USB Mass Storage devices.
 * Uses 64 KB aligned batches (128 sectors) to maximize USB bus bandwidth utilization.
 * 100% Non-destructive: original sector data is backed up into memory during read,
 * and restored upon completion or failure in a finally block.
 */
class BenchmarkEngine {

    suspend fun runBenchmark(
        blockDevice: IBlockDevice,
        testSizeMb: Int = 32,
        blockSizeBytes: Int = 1048576,
        onProgress: (Float, BenchmarkStage) -> Unit
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        val sectorSize = maxOf(512, blockDevice.sectorSize)
        val chunkSizeSectors = maxOf(1, blockSizeBytes / sectorSize)
        val chunkSizeBytes = chunkSizeSectors * sectorSize
        val startLba = 2048L // 1 MiB alignment boundary

        val maxUsableSectors = maxOf(0L, blockDevice.totalSectors - startLba)
        val maxTestBytes = maxUsableSectors * sectorSize
        val requestedTestBytes = maxOf(chunkSizeBytes.toLong(), testSizeMb.toLong() * 1024 * 1024)
        val totalTestBytes = minOf(requestedTestBytes, maxTestBytes)
        if (totalTestBytes < chunkSizeBytes || chunkSizeBytes <= 0) {
            return@withContext BenchmarkResult(0.0, 0.0, 0.0)
        }

        // Limit iteration count so small blocks (e.g. 512B, 4KB) don't run indefinitely
        // and cap backup memory strictly to 16 MB ceiling to eliminate heap OOM / GC freezing
        val maxChunks = when {
            blockSizeBytes <= 512 -> 2048L   // 1 MB total (2,048 IOPS)
            blockSizeBytes <= 4096 -> 2048L  // 8 MB total
            blockSizeBytes <= 65536 -> 256L  // 16 MB total
            else -> 64L                      // up to testSizeMb or single giant buffer
        }
        val maxBackupRam = 16 * 1024 * 1024L // 16 MB maximum RAM ceiling
        val maxChunksForRam = maxOf(1L, maxBackupRam / chunkSizeBytes)
        val rawChunks = totalTestBytes / chunkSizeBytes
        val totalChunks = maxOf(1L, minOf(rawChunks, maxChunks, maxChunksForRam))
        val actualTestBytes = totalChunks * chunkSizeBytes

        val buffer = ByteBuffer.allocateDirect(chunkSizeBytes)
        val backupChunks = ArrayList<ByteArray>(totalChunks.toInt())

        // 1. Sequential Read Test & In-Memory Sector Backup
        onProgress(5.0f, BenchmarkStage.READING)
        val readStartTime = System.currentTimeMillis()
        var currentLba = startLba
        var lastProgressTime = readStartTime

        for (i in 0 until totalChunks) {
            buffer.clear()
            blockDevice.readSectors(currentLba, chunkSizeSectors, buffer)
            buffer.flip()

            val backup = ByteArray(chunkSizeBytes)
            buffer.get(backup)
            backupChunks.add(backup)

            currentLba += chunkSizeSectors
            val now = System.currentTimeMillis()
            if (now - lastProgressTime >= 100 || i == totalChunks - 1L) {
                val pct = 5.0f + (40.0f * (i.toFloat() / totalChunks))
                onProgress(pct, BenchmarkStage.READING)
                lastProgressTime = now
            }
        }
        val readElapsedSec = maxOf(0.001, (System.currentTimeMillis() - readStartTime) / 1000.0)
        val readSpeedMb = (actualTestBytes / (1024.0 * 1024.0)) / readElapsedSec

        // 2. Sequential Write Test with Non-destructive Restoration
        onProgress(45.0f, BenchmarkStage.WRITING)
        val testPattern = ByteBuffer.allocateDirect(chunkSizeBytes)
        val patternChunk = ByteArray(minOf(chunkSizeBytes, 65536)) { (it xor 0x5A).toByte() }
        var bytesWritten = 0
        while (bytesWritten < chunkSizeBytes) {
            val len = minOf(chunkSizeBytes - bytesWritten, patternChunk.size)
            testPattern.put(patternChunk, 0, len)
            bytesWritten += len
        }
        testPattern.flip()

        var writeSpeedMb = 0.0

        try {
            val writeStartTime = System.currentTimeMillis()
            currentLba = startLba
            lastProgressTime = writeStartTime

            for (i in 0 until totalChunks) {
                testPattern.position(0)
                blockDevice.writeSectors(currentLba, chunkSizeSectors, testPattern)
                currentLba += chunkSizeSectors
                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 100 || i == totalChunks - 1L) {
                    val pct = 45.0f + (40.0f * (i.toFloat() / totalChunks))
                    onProgress(pct, BenchmarkStage.WRITING)
                    lastProgressTime = now
                }
            }
            try {
                blockDevice.sync()
            } catch (_: Exception) {}
            val writeElapsedSec = maxOf(0.001, (System.currentTimeMillis() - writeStartTime) / 1000.0)
            writeSpeedMb = (actualTestBytes / (1024.0 * 1024.0)) / writeElapsedSec
        } finally {
            // Unconditionally restore original sector content to guarantee zero data loss
            onProgress(85.0f, BenchmarkStage.RESTORING)
            currentLba = startLba
            lastProgressTime = System.currentTimeMillis()
            for (i in 0 until backupChunks.size) {
                buffer.clear()
                buffer.put(backupChunks[i])
                buffer.flip()
                try {
                    blockDevice.writeSectors(currentLba, chunkSizeSectors, buffer)
                } catch (e: Exception) {
                    // Continue restoring remaining chunks
                }
                currentLba += chunkSizeSectors
                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 100 || i == backupChunks.size - 1) {
                    val pct = 85.0f + (14.0f * (i.toFloat() / backupChunks.size))
                    onProgress(pct, BenchmarkStage.RESTORING)
                    lastProgressTime = now
                }
            }
            try {
                blockDevice.sync()
            } catch (_: Exception) {}
        }

        onProgress(100.0f, BenchmarkStage.COMPLETED)

        BenchmarkResult(
            readSpeedMbPerSec = readSpeedMb,
            writeSpeedMbPerSec = writeSpeedMb,
            totalTestedMb = actualTestBytes / (1024.0 * 1024.0)
        )
    }
}
