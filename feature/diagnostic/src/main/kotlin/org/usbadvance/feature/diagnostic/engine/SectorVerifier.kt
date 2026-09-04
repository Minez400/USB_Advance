package org.usbadvance.feature.diagnostic.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.IBlockDevice
import java.nio.ByteBuffer

data class VerificationResult(
    val totalSectorsScanned: Long,
    val badSectorsCount: Long,
    val isHealthy: Boolean = (badSectorsCount == 0L)
)

class SectorVerifier {

    suspend fun verifySectors(
        blockDevice: IBlockDevice,
        maxSectorsToScan: Long = 100000L,
        onProgress: (Float, String) -> Unit
    ): VerificationResult = withContext(Dispatchers.IO) {
        val sectorSize = blockDevice.sectorSize
        val chunkSectors = 128
        val buffer = ByteBuffer.allocateDirect(chunkSectors * sectorSize)

        val totalToScan = minOf(blockDevice.totalSectors, maxSectorsToScan)
        var scanned = 0L
        var badSectors = 0L

        while (scanned < totalToScan) {
            val count = minOf(chunkSectors.toLong(), totalToScan - scanned).toInt()
            buffer.clear()
            try {
                blockDevice.readSectors(scanned, count, buffer)
            } catch (e: Exception) {
                badSectors += count
            }
            scanned += count
            val pct = (scanned.toFloat() / totalToScan) * 100.0f
            onProgress(pct, "Verificando LBA $scanned de $totalToScan...")
        }

        VerificationResult(
            totalSectorsScanned = scanned,
            badSectorsCount = badSectors
        )
    }
}
