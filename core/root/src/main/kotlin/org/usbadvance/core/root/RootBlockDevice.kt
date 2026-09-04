package org.usbadvance.core.root

import com.topjohnwu.superuser.io.SuFile
import com.topjohnwu.superuser.io.SuRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.IBlockDevice
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Implementação de IBlockDevice para dispositivos com Root, acessando diretamente nós /dev/block/sdX.
 */
class RootBlockDevice(
    private val blockDevicePath: String,
    override val sectorSize: Int = 512,
    override val totalSectors: Long
) : IBlockDevice {

    private var suRaf: SuRandomAccessFile? = null

    init {
        // Validação de segurança anti-desastre: IMPEDE rigorosamente acesso à memória interna do sistema!
        val normalized = blockDevicePath.lowercase()
        if (normalized.contains("bootdevice") ||
            normalized.contains("userdata") ||
            normalized.contains("system") ||
            normalized.contains("vendor") ||
            normalized.contains("mmcblk0") // eMMC/UFS interno
        ) {
            throw SecurityException("ACESSO BLOQUEADO: Tentativa de abrir nó de bloco do sistema interno: $blockDevicePath")
        }
        val file = SuFile(blockDevicePath)
        suRaf = SuRandomAccessFile.open(file, "rws")
    }

    override suspend fun readSectors(lba: Long, count: Int, destination: ByteBuffer) = withContext(Dispatchers.IO) {
        val raf = suRaf ?: throw IOException("Dispositivo fechado")
        val offset = lba * sectorSize
        raf.seek(offset)

        val tempArray = ByteArray(count * sectorSize)
        val read = raf.read(tempArray)
        if (read < tempArray.size) {
            throw IOException("Falha de leitura no bloco root: lidos $read de ${tempArray.size} bytes")
        }
        destination.put(tempArray)
    }

    override suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer) = withContext(Dispatchers.IO) {
        val raf = suRaf ?: throw IOException("Dispositivo fechado")
        val offset = lba * sectorSize
        raf.seek(offset)

        val tempArray = ByteArray(count * sectorSize)
        source.get(tempArray)
        raf.write(tempArray)
    }

    override suspend fun eraseSectors(lba: Long, count: Int) = withContext(Dispatchers.IO) {
        val raf = suRaf ?: throw IOException("Dispositivo fechado")
        val offset = lba * sectorSize
        raf.seek(offset)

        val chunkSize = minOf(count, 2048) * sectorSize
        val zeroBuffer = ByteArray(chunkSize)
        var remainingBytes = count.toLong() * sectorSize

        while (remainingBytes > 0) {
            val toWrite = minOf(remainingBytes, chunkSize.toLong()).toInt()
            raf.write(zeroBuffer, 0, toWrite)
            remainingBytes -= toWrite
        }
    }

    override suspend fun sync() = withContext(Dispatchers.IO) {
        // O modo "rws" do SuRandomAccessFile força sincronização a cada escrita
    }

    override suspend fun isWriteProtected(): Boolean = withContext(Dispatchers.IO) {
        return@withContext false
    }

    override fun close() {
        try {
            suRaf?.close()
            suRaf = null
        } catch (ignored: Exception) {}
    }
}
