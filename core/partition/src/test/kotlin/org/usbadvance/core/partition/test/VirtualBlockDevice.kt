package org.usbadvance.core.partition.test

import org.usbadvance.core.storage.api.IBlockDevice
import java.nio.ByteBuffer

/**
 * Dispositivo de bloco virtual em memória para testes unitários ultrarrápidos em JVM.
 */
class VirtualBlockDevice(
    override val sectorSize: Int = 512,
    override val totalSectors: Long = 100000L // ~51.2 MB
) : IBlockDevice {

    private val storage = ByteArray((totalSectors * sectorSize).toInt())
    var syncCalled = false
        private set

    override suspend fun readSectors(lba: Long, count: Int, destination: ByteBuffer) {
        val offset = (lba * sectorSize).toInt()
        val length = count * sectorSize
        destination.put(storage, offset, length)
    }

    override suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer) {
        val offset = (lba * sectorSize).toInt()
        val length = count * sectorSize
        source.get(storage, offset, length)
    }

    override suspend fun eraseSectors(lba: Long, count: Int) {
        val offset = (lba * sectorSize).toInt()
        val length = count * sectorSize
        java.util.Arrays.fill(storage, offset, offset + length, 0.toByte())
    }

    override suspend fun sync() {
        syncCalled = true
    }

    override suspend fun isWriteProtected(): Boolean = false

    override fun close() {}

    fun getRawBytes(offset: Int, length: Int): ByteArray {
        return storage.copyOfRange(offset, offset + length)
    }
}
