package org.usbadvance.core.storage.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.usbadvance.core.partition.test.VirtualBlockDevice
import java.nio.ByteBuffer

class CachedBlockDeviceTest {

    @Test
    fun testCacheHitAndInvalidation() = runBlocking {
        val virtualDev = VirtualBlockDevice(sectorSize = 512, totalSectors = 100)
        val cachedDev = CachedBlockDevice(virtualDev, maxCachedSectors = 16)

        // Write some pattern to LBA 5
        val writeBuf = ByteBuffer.allocate(512)
        for (i in 0 until 512) {
            writeBuf.put((i % 100).toByte())
        }
        writeBuf.flip()
        virtualDev.writeSectors(5L, 1, writeBuf)

        // Read 1: Cache Miss
        val readBuf1 = ByteBuffer.allocate(512)
        cachedDev.readSectors(5L, 1, readBuf1)
        assertEquals(1L, cachedDev.cacheMisses)
        assertEquals(0L, cachedDev.cacheHits)
        assertArrayEquals(writeBuf.array(), readBuf1.array())

        // Read 2: Cache Hit
        val readBuf2 = ByteBuffer.allocate(512)
        cachedDev.readSectors(5L, 1, readBuf2)
        assertEquals(1L, cachedDev.cacheMisses)
        assertEquals(1L, cachedDev.cacheHits)
        assertArrayEquals(writeBuf.array(), readBuf2.array())

        // Overwrite LBA 5 with new pattern
        val newPattern = ByteBuffer.allocate(512)
        for (i in 0 until 512) {
            newPattern.put(0x7F.toByte())
        }
        newPattern.flip()
        cachedDev.writeSectors(5L, 1, newPattern)

        // Read 3: Cache Miss because write invalidated LBA 5
        val readBuf3 = ByteBuffer.allocate(512)
        cachedDev.readSectors(5L, 1, readBuf3)
        assertEquals(2L, cachedDev.cacheMisses)
        assertEquals(1L, cachedDev.cacheHits)
        assertArrayEquals(newPattern.array(), readBuf3.array())
    }
}
