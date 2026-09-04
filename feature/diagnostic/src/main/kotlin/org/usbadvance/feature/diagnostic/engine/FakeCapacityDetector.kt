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
 * Motor de detecção de pendrives falsos e memória adulterada (estilo H2testw e FakeFlashTest).
 *
 * Pendrives falsos (ex: vendidos como 2 TB, mas com chip real de 16 GB ou 32 GB)
 * possuem firmware manipulado onde o controlador faz wrap-around: gravações além
 * da capacidade real sobrescrevem silenciosamente os primeiros setores da memória física.
 */
class FakeCapacityDetector {

    companion object {
        private const val MAGIC_TOKEN = 0x55414456L // "UADV" em Little Endian
    }

    /**
     * Teste Rápido Inteligente (Smart Probe):
     * Testa fronteiras logarítmicas de capacidade (1 GB, 2 GB, 4 GB, 8 GB, 16 GB, 32 GB, etc.).
     * Em cada fronteira, escreve uma assinatura única e relê todas as anteriores.
     * Se uma gravação em LBA alto sobrescrever uma assinatura anterior, a falsificação é comprovada.
     */
    suspend fun runQuickProbe(
        blockDevice: IBlockDevice,
        onProgress: (Float, String) -> Unit
    ): FakeDetectionResult = withContext(Dispatchers.IO) {
        val sectorSize = blockDevice.sectorSize
        val totalSectors = blockDevice.totalSectors
        val declaredCapacity = blockDevice.capacityBytes
        val sessionNonce = Random().nextLong()

        // Lista de checkpoints estratégicos (em bytes): 512 MB, 1 GB, 2 GB, 4 GB, 8 GB, 16 GB, 32 GB, 64 GB, etc.
        val checkpointsBytes = mutableListOf<Long>()
        var currentBytes = 512L * 1024 * 1024 // Começa em 512 MB
        while (currentBytes < declaredCapacity) {
            checkpointsBytes.add(currentBytes)
            currentBytes *= 2
        }
        if (checkpointsBytes.isEmpty() || checkpointsBytes.last() < declaredCapacity - (100L * 1024 * 1024)) {
            checkpointsBytes.add(maxOf(0L, declaredCapacity - (16L * 1024 * 1024))) // Teste próximo ao final
        }

        val checkpointsLba = checkpointsBytes.map { it / sectorSize }.filter { it < totalSectors }
        val buffer = ByteBuffer.allocateDirect(sectorSize).order(ByteOrder.LITTLE_ENDIAN)

        var detectedRealCapacity = declaredCapacity
        var isFake = false
        var corruptedCount = 0

        onProgress(5.0f, "Iniciando varredura rápida de autenticidade...")

        // Fase 1: Gravação e verificação progressiva de assinaturas
        for (i in checkpointsLba.indices) {
            val targetLba = checkpointsLba[i]
            val pct = 5.0f + (70.0f * (i.toFloat() / checkpointsLba.size))
            val currentGb = (targetLba * sectorSize) / (1024.0 * 1024.0 * 1024.0)
            onProgress(pct, String.format("Testando fronteira de %.1f GB (LBA %d)...", currentGb, targetLba))

            // Monta o setor com a assinatura deste checkpoint
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

            // Relê TODOS os checkpoints anteriores para verificar se foram sobrescritos
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
                        // Falsificação detectada! O chip deu a volta (wrap-around)
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
