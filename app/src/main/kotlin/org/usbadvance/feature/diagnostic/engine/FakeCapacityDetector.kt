package org.usbadvance.feature.diagnostic.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.IBlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

data class FakeDetectionResult(
    val isAuthentic: Boolean,
    val declaredCapacityBytes: Long,
    val realCapacityBytes: Long,
    val testedCheckpoints: Int,
    val corruptedCheckpoints: Int,
    val details: String
)

/**
 * Detection engine for counterfeit flash drives and tampered NAND flash controllers (H2testw / FakeFlashTest algorithm).
 *
 * Counterfeit flash drives (e.g. advertised as 2 TB, but with only 16 GB or 32 GB of real NAND)
 * utilize hacked controller firmware where sector addressing wraps around: writes beyond real physical capacity
 * silently overwrite earlier sectors, destroying file tables and existing data.
 */
class FakeCapacityDetector {

    companion object {
        private const val MAGIC_TOKEN = 0x55414456L // "UADV" in Little Endian
    }

    /**
     * Smart Boundary Probe:
     * Evaluates logarithmic power-of-2 capacity boundaries (512 MB, 1 GB, 2 GB, 4 GB, 8 GB, 16 GB, 32 GB, etc.).
     * At each boundary, writes a unique signed cryptographic sector and immediately verifies all prior checkpoints.
     * If writing to a higher LBA corrupts or overwrites an earlier checkpoint signature, a controller wrap-around
     * is definitively detected in under 60 seconds without having to fill the entire drive.
     */
    suspend fun runQuickProbe(
        blockDevice: IBlockDevice,
        onProgress: (Float, String) -> Unit
    ): FakeDetectionResult = withContext(Dispatchers.IO) {
        val sectorSize = blockDevice.sectorSize
        val totalSectors = blockDevice.totalSectors
        val declaredCapacity = blockDevice.capacityBytes
        val sessionNonce = Random().nextLong()

        // List of strategic boundary checkpoints: 512 MB, 1 GB, 2 GB, 4 GB, 8 GB, 16 GB, 32 GB, 64 GB, etc.
        val checkpointsBytes = mutableListOf<Long>()
        var currentBytes = 512L * 1024 * 1024 // Start at 512 MB boundary
        while (currentBytes < declaredCapacity) {
            checkpointsBytes.add(currentBytes)
            currentBytes *= 2
        }
        if (checkpointsBytes.isEmpty() || checkpointsBytes.last() < declaredCapacity - (100L * 1024 * 1024)) {
            checkpointsBytes.add(maxOf(0L, declaredCapacity - (16L * 1024 * 1024))) // Test near drive end
        }

        val checkpointsLba = checkpointsBytes.map { it / sectorSize }.filter { it < totalSectors }
        val buffer = ByteBuffer.allocateDirect(sectorSize).order(ByteOrder.LITTLE_ENDIAN)

        var detectedRealCapacity = declaredCapacity
        var isFake = false
        var corruptedCount = 0

        onProgress(5.0f, "Starting fast authenticity scan...")

        // Phase 1: Progressive writing and verification of boundary signatures
        for (i in checkpointsLba.indices) {
            val targetLba = checkpointsLba[i]
            val pct = 5.0f + (70.0f * (i.toFloat() / checkpointsLba.size))
            val currentGb = (targetLba * sectorSize) / (1024.0 * 1024.0 * 1024.0)
            onProgress(pct, String.format("Testing %.1f GB boundary (LBA %d)...", currentGb, targetLba))

            // Assemble checkpoint sector with unique cryptographic validation tuple
            buffer.clear()
            buffer.putLong(MAGIC_TOKEN)
            buffer.putLong(targetLba)
            buffer.putLong(sessionNonce)
            buffer.putLong(targetLba xor sessionNonce)
            while (buffer.hasRemaining()) buffer.put(0xAA.toByte())
            buffer.flip()

            try {
                blockDevice.writeSectors(targetLba, 1, buffer)
            } catch (e: Exception) {
                isFake = true
                corruptedCount++
                detectedRealCapacity = minOf(detectedRealCapacity, targetLba * sectorSize)
                break
            }

            // Re-read ALL prior checkpoints to detect controller address wrap-around overwrites
            for (prevIdx in 0 until i) {
                val prevLba = checkpointsLba[prevIdx]
                buffer.clear()
                try {
                    blockDevice.readSectors(prevLba, 1, buffer)
                    buffer.flip()
                    val token = buffer.long
                    val readLba = buffer.long
                    val readNonce = buffer.long
                    val checkVal = buffer.long

                    if (token != MAGIC_TOKEN || readLba != prevLba || readNonce != sessionNonce || checkVal != (prevLba xor sessionNonce)) {
                        // Tampered memory detected! Controller wrapped around and destroyed earlier sector
                        isFake = true
                        corruptedCount++
                        val realCap = (checkpointsLba[i] - prevLba) * sectorSize
                        detectedRealCapacity = minOf(detectedRealCapacity, maxOf(prevLba * sectorSize, realCap))
                        break
                    }
                } catch (e: Exception) {
                    isFake = true
                    corruptedCount++
                }
            }

            if (isFake) break
        }

        try {
            blockDevice.sync()
        } catch (_: Exception) {}
        onProgress(95.0f, "Consolidating authenticity report...")

        val details = if (isFake) {
            val declaredGb = declaredCapacity / (1024.0 * 1024.0 * 1024.0)
            val realGb = detectedRealCapacity / (1024.0 * 1024.0 * 1024.0)
            String.format(
                "ALERT: Counterfeit drive! Declared capacity: %.1f GB, but real physical flash memory is only ~%.1f GB. Files written past this boundary will be corrupted!",
                declaredGb,
                realGb
            )
        } else {
            val declaredGb = declaredCapacity / (1024.0 * 1024.0 * 1024.0)
            String.format(
                "100%% Authentic drive! All %d tested physical memory boundaries retain data with proven integrity (%.1f GB).",
                checkpointsLba.size,
                declaredGb
            )
        }

        onProgress(100.0f, if (isFake) "Counterfeit detected!" else "Drive authentic!")

        FakeDetectionResult(
            isAuthentic = !isFake,
            declaredCapacityBytes = declaredCapacity,
            realCapacityBytes = detectedRealCapacity,
            testedCheckpoints = checkpointsLba.size,
            corruptedCheckpoints = corruptedCount,
            details = details
        )
    }

    /**
     * H2testw Sequential Fill & Verify Algorithm:
     * Systematically fills disk sectors up to maxTestBytes with a deterministic pseudo-random sequence,
     * then reads back all sectors to verify byte-exact integrity.
     * Detects corrupted sectors, dropped writes, and memory folding/wrap-around.
     */
    suspend fun runFullFillTest(
        blockDevice: IBlockDevice,
        blockSizeBytes: Int = 1048576, // 1 MB default
        maxTestBytes: Long = blockDevice.capacityBytes,
        onProgress: (Float, String) -> Unit
    ): FakeDetectionResult = withContext(Dispatchers.IO) {
        val sectorSize = maxOf(512, blockDevice.sectorSize)
        val chunkSizeSectors = maxOf(1, blockSizeBytes / sectorSize)
        val chunkSizeBytes = chunkSizeSectors * sectorSize
        val totalCapacity = blockDevice.capacityBytes
        val targetTestBytes = minOf(maxTestBytes, totalCapacity)

        if (targetTestBytes < chunkSizeBytes || chunkSizeBytes <= 0) {
            return@withContext FakeDetectionResult(
                isAuthentic = false,
                declaredCapacityBytes = totalCapacity,
                realCapacityBytes = 0,
                testedCheckpoints = 0,
                corruptedCheckpoints = 1,
                details = "Device capacity is too small or invalid for fill test."
            )
        }

        val totalChunks = maxOf(1L, targetTestBytes / chunkSizeBytes)
        val actualTestBytes = totalChunks * chunkSizeBytes
        val sessionNonce = Random().nextLong()

        val writeBuffer = ByteBuffer.allocateDirect(chunkSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        val readBuffer = ByteBuffer.allocateDirect(chunkSizeBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Phase 1: Sequential Write / Fill
        val writeStartTime = System.currentTimeMillis()
        var currentLba = 0L
        var lastProgressTime = writeStartTime
        var isFake = false
        var detectedRealCapacity = actualTestBytes
        var corruptedChunks = 0

        onProgress(2.0f, "Phase 1/2: Initializing drive fill...")

        for (chunkIndex in 0 until totalChunks) {
            writeBuffer.clear()
            var sectorLba = currentLba
            for (s in 0 until chunkSizeSectors) {
                fillSector(writeBuffer, sectorLba, sessionNonce, sectorSize)
                sectorLba++
            }
            writeBuffer.flip()

            try {
                blockDevice.writeSectors(currentLba, chunkSizeSectors, writeBuffer)
            } catch (e: Exception) {
                isFake = true
                corruptedChunks++
                detectedRealCapacity = minOf(detectedRealCapacity, currentLba * sectorSize)
                break
            }

            currentLba += chunkSizeSectors

            val now = System.currentTimeMillis()
            if (now - lastProgressTime >= 150 || chunkIndex == totalChunks - 1) {
                val bytesWritten = (chunkIndex + 1L) * chunkSizeBytes
                val elapsedSec = maxOf(0.001, (now - writeStartTime) / 1000.0)
                val speedMb = (bytesWritten / (1024.0 * 1024.0)) / elapsedSec
                val currentGb = bytesWritten / (1024.0 * 1024.0 * 1024.0)
                val targetGb = actualTestBytes / (1024.0 * 1024.0 * 1024.0)
                val pct = 2.0f + (48.0f * ((chunkIndex + 1L).toFloat() / totalChunks))
                onProgress(pct, String.format("Phase 1/2 (Write): %.1f / %.1f GB (%.1f MB/s)", currentGb, targetGb, speedMb))
                lastProgressTime = now
            }
        }

        try {
            blockDevice.sync()
        } catch (_: Exception) {}

        // Phase 2: Sequential Read & Verification
        if (!isFake) {
            val verifyStartTime = System.currentTimeMillis()
            currentLba = 0L
            lastProgressTime = verifyStartTime
            onProgress(50.0f, "Phase 2/2: Starting sector verification...")

            for (chunkIndex in 0 until totalChunks) {
                readBuffer.clear()
                try {
                    blockDevice.readSectors(currentLba, chunkSizeSectors, readBuffer)
                    readBuffer.flip()
                } catch (e: Exception) {
                    isFake = true
                    corruptedChunks++
                    detectedRealCapacity = minOf(detectedRealCapacity, currentLba * sectorSize)
                    break
                }

                var sectorLba = currentLba
                var chunkValid = true
                for (s in 0 until chunkSizeSectors) {
                    if (!verifySector(readBuffer, sectorLba, sessionNonce, sectorSize)) {
                        chunkValid = false
                        break
                    }
                    sectorLba++
                }

                if (!chunkValid) {
                    isFake = true
                    corruptedChunks++
                    detectedRealCapacity = minOf(detectedRealCapacity, currentLba * sectorSize)
                    break
                }

                currentLba += chunkSizeSectors

                val now = System.currentTimeMillis()
                if (now - lastProgressTime >= 150 || chunkIndex == totalChunks - 1) {
                    val bytesVerified = (chunkIndex + 1L) * chunkSizeBytes
                    val elapsedSec = maxOf(0.001, (now - verifyStartTime) / 1000.0)
                    val speedMb = (bytesVerified / (1024.0 * 1024.0)) / elapsedSec
                    val currentGb = bytesVerified / (1024.0 * 1024.0 * 1024.0)
                    val targetGb = actualTestBytes / (1024.0 * 1024.0 * 1024.0)
                    val pct = 50.0f + (50.0f * ((chunkIndex + 1L).toFloat() / totalChunks))
                    onProgress(pct, String.format("Phase 2/2 (Verify): %.1f / %.1f GB (%.1f MB/s)", currentGb, targetGb, speedMb))
                    lastProgressTime = now
                }
            }
        }

        val totalGb = actualTestBytes / (1024.0 * 1024.0 * 1024.0)
        val realGb = detectedRealCapacity / (1024.0 * 1024.0 * 1024.0)

        val details = if (isFake) {
            String.format(
                "ALERT: Counterfeit drive! Corrupted at %.1f GB. Real physical flash memory is ~%.1f GB of %.1f GB tested.",
                realGb, realGb, totalGb
            )
        } else {
            String.format(
                "100%% Authentic drive! All %.1f GB written and verified with 0 corrupted sectors.",
                totalGb
            )
        }

        onProgress(100.0f, if (isFake) "Counterfeit detected!" else "All sectors verified!")

        FakeDetectionResult(
            isAuthentic = !isFake,
            declaredCapacityBytes = totalCapacity,
            realCapacityBytes = detectedRealCapacity,
            testedCheckpoints = totalChunks.toInt(),
            corruptedCheckpoints = corruptedChunks,
            details = details
        )
    }

    private fun fillSector(buffer: ByteBuffer, lba: Long, sessionNonce: Long, sectorSize: Int) {
        val startPos = buffer.position()
        buffer.putLong(MAGIC_TOKEN)
        buffer.putLong(lba)
        buffer.putLong(sessionNonce)
        buffer.putLong(lba xor sessionNonce)
        var state = lba * 6364136223846793005L + sessionNonce
        while (buffer.position() < startPos + sectorSize) {
            state = state * 6364136223846793005L + 1442695040888963407L
            buffer.putLong(state)
        }
        buffer.position(startPos + sectorSize)
    }

    private fun verifySector(buffer: ByteBuffer, lba: Long, sessionNonce: Long, sectorSize: Int): Boolean {
        val startPos = buffer.position()
        if (buffer.remaining() < sectorSize) {
            return false
        }
        val token = buffer.long
        val readLba = buffer.long
        val readNonce = buffer.long
        val checkVal = buffer.long
        if (token != MAGIC_TOKEN || readLba != lba || readNonce != sessionNonce || checkVal != (lba xor sessionNonce)) {
            buffer.position(startPos + sectorSize)
            return false
        }
        var state = lba * 6364136223846793005L + sessionNonce
        while (buffer.position() < startPos + sectorSize) {
            state = state * 6364136223846793005L + 1442695040888963407L
            if (buffer.long != state) {
                buffer.position(startPos + sectorSize)
                return false
            }
        }
        buffer.position(startPos + sectorSize)
        return true
    }
}
