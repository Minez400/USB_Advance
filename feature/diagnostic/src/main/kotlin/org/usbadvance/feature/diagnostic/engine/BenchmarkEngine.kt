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

class BenchmarkEngine {

    suspend fun runBenchmark(
        blockDevice: IBlockDevice,
        testSizeMb: Int = 32,
        onProgress: (Float, String) -> Unit
    ): BenchmarkResult = withContext(Dispatchers.IO) {
        val sectorSize = blockDevice.sectorSize
        val chunkSizeSectors = 128 // 64 KB por transação
        val chunkSizeBytes = chunkSizeSectors * sectorSize
        val totalTestBytes = testSizeMb.toLong() * 1024 * 1024
        val totalChunks = totalTestBytes / chunkSizeBytes

        val buffer = ByteBuffer.allocateDirect(chunkSizeBytes)

        // 1. Teste de Leitura Sequencial
        onProgress(10.0f, "Executando teste de leitura sequencial...")
        val readStartTime = System.currentTimeMillis()
        var currentLba = 2048L // Inicia no LBA alinhado

        for (i in 0 until totalChunks) {
            buffer.clear()
            blockDevice.readSectors(currentLba, chunkSizeSectors, buffer)
            currentLba += chunkSizeSectors
            val pct = 10.0f + (40.0f * (i.toFloat() / totalChunks))
            onProgress(pct, "Lendo blocos USB...")
        }
        val readElapsedSec = (System.currentTimeMillis() - readStartTime) / 1000.0
        val readSpeedMb = (totalTestBytes / (1024.0 * 1024.0)) / readElapsedSec

        // 2. Teste de Escrita Sequencial
        onProgress(50.0f, "Executando teste de escrita sequencial...")
        val writeStartTime = System.currentTimeMillis()
        currentLba = 2048L

        for (i in 0 until totalChunks) {
            buffer.position(0)
            blockDevice.writeSectors(currentLba, chunkSizeSectors, buffer)
            currentLba += chunkSizeSectors
            val pct = 50.0f + (50.0f * (i.toFloat() / totalChunks))
            onProgress(pct, "Gravando blocos de teste...")
        }
        blockDevice.sync()
        val writeElapsedSec = (System.currentTimeMillis() - writeStartTime) / 1000.0
        val writeSpeedMb = (totalTestBytes / (1024.0 * 1024.0)) / writeElapsedSec

        onProgress(100.0f, "Benchmark concluído!")

        BenchmarkResult(
            readSpeedMbPerSec = readSpeedMb,
            writeSpeedMbPerSec = writeSpeedMb,
            totalTestedMb = testSizeMb.toDouble()
        )
    }
}
