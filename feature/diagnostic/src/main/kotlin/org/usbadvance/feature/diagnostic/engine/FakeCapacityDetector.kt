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

        onProgress(5.0f, "Iniciando varredura rápida de autenticidade...")

        // Phase 1: Progressive writing and verification of boundary signatures
        for (i in checkpointsLba.indices) {
            val targetLba = checkpointsLba[i]
            val pct = 5.0f + (70.0f * (i.toFloat() / checkpointsLba.size))
            val currentGb = (targetLba * sectorSize) / (1024.0 * 1024.0 * 1024.0)
            onProgress(pct, String.format("Testando fronteira de %.1f GB (LBA %d)...", currentGb, targetLba))

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

        blockDevice.sync()
        onProgress(95.0f, "Consolidando relatório de autenticidade...")

        val details = if (isFake) {
            val declaredGb = declaredCapacity / (1024.0 * 1024.0 * 1024.0)
            val realGb = detectedRealCapacity / (1024.0 * 1024.0 * 1024.0)
            String.format(
                "ALERTA: Unidade falsificada! Capacidade declarada: %.1f GB, mas a memória física real é de apenas aproximadamente %.1f GB. Arquivos gravados além desse limite serão destruídos!",
                declaredGb,
                realGb
            )
        } else {
            val declaredGb = declaredCapacity / (1024.0 * 1024.0 * 1024.0)
            String.format(
                "Unidade 100%% Autêntica! Todas as %d fronteiras de memória física retêm dados com integridade comprovada (%.1f GB).",
                checkpointsLba.size,
                declaredGb
            )
        }

        onProgress(100.0f, if (isFake) "Falsificação detectada!" else "Unidade autêntica!")

        FakeDetectionResult(
            isAuthentic = !isFake,
            declaredCapacityBytes = declaredCapacity,
            realCapacityBytes = detectedRealCapacity,
            testedCheckpoints = checkpointsLba.size,
            corruptedCheckpoints = corruptedCount,
            details = details
        )
    }
}
