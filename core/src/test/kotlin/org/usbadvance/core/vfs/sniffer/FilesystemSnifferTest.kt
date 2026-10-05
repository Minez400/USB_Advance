package org.usbadvance.core.vfs.sniffer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.usbadvance.core.partition.test.VirtualBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FilesystemSnifferTest {

    @Test
    fun testSniffExFat() = runBlocking {
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = 10000L)
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)

        // Write exFAT VBR
        buf.position(3)
        buf.put("EXFAT   ".toByteArray(Charsets.US_ASCII))
        buf.position(72)
        buf.putLong(10000L) // VolumeLength
        buf.position(108)
        buf.put(9.toByte()) // 2^9 = 512 bytes per sector
        buf.put(6.toByte()) // 2^6 = 64 sectors per cluster (32 KB)
        buf.position(510)
        buf.putShort(0xAA55.toShort())
        buf.flip()

        virtualDisk.writeSectors(2048L, 1, buf)

        val sniffer = FilesystemSniffer()
        val result = sniffer.detectFilesystem(virtualDisk, 2048L, 8000L)

        assertNotNull(result)
        assertEquals(FilesystemType.EXFAT, result?.type)
        assertEquals(32768, result?.clusterSizeBytes)
    }

    @Test
    fun testSniffExFatWithLabel() = runBlocking {
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = 10000L)
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)

        // Write exFAT VBR
        buf.position(3)
        buf.put("EXFAT   ".toByteArray(Charsets.US_ASCII))
        buf.position(72)
        buf.putLong(10000L) // VolumeLength
        buf.position(88)
        buf.putInt(24) // ClusterHeapOffset = 24 sectors after partition start
        buf.position(96)
        buf.putInt(2) // FirstClusterOfRootDir = cluster 2
        buf.position(108)
        buf.put(9.toByte()) // 2^9 = 512 bytes per sector
        buf.put(6.toByte()) // 2^6 = 64 sectors per cluster (32 KB)
        buf.position(510)
        buf.putShort(0xAA55.toShort())
        buf.flip()

        virtualDisk.writeSectors(2048L, 1, buf)

        // Write Root Directory at LBA 2048 + 24 + (2 - 2) * 64 = 2072L
        val rootBuf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)
        rootBuf.put(0x83.toByte()) // Entry Type: Volume Label
        val labelStr = "PS2_OPL_USB"
        rootBuf.put(labelStr.length.toByte())
        for (ch in labelStr) {
            rootBuf.putChar(ch)
        }
        rootBuf.flip()
        virtualDisk.writeSectors(2048L + 24L, 1, rootBuf)

        val sniffer = FilesystemSniffer()
        val result = sniffer.detectFilesystem(virtualDisk, 2048L, 8000L)

        assertNotNull(result)
        assertEquals(FilesystemType.EXFAT, result?.type)
        assertEquals("PS2_OPL_USB", result?.volumeLabel)
    }

    @Test
    fun testSniffFat32() = runBlocking {
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = 10000L)
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)

        buf.position(11)
        buf.putShort(512.toShort()) // BPS
        buf.put(8.toByte()) // SPC -> 4 KB clusters
        buf.putShort(32.toShort()) // Reserved
        buf.put(2.toByte()) // FATs
        buf.putShort(0.toShort()) // Root entries = 0 (FAT32)
        buf.putShort(0.toShort()) // TotalSec16 = 0
        buf.position(32)
        buf.putInt(8000) // TotalSec32
        buf.position(71)
        buf.put("USB_DRIVE  ".toByteArray(Charsets.US_ASCII)) // Volume label
        buf.position(82)
        buf.put("FAT32   ".toByteArray(Charsets.US_ASCII))
        buf.position(510)
        buf.putShort(0xAA55.toShort())
        buf.flip()

        virtualDisk.writeSectors(2048L, 1, buf)

        val sniffer = FilesystemSniffer()
        val result = sniffer.detectFilesystem(virtualDisk, 2048L, 8000L)

        assertNotNull(result)
        assertEquals(FilesystemType.FAT32, result?.type)
        assertEquals("USB_DRIVE", result?.volumeLabel)
        assertEquals(4096, result?.clusterSizeBytes)
    }

    @Test
    fun testSniffNtfs() = runBlocking {
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = 10000L)
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)

        buf.position(3)
        buf.put("NTFS    ".toByteArray(Charsets.US_ASCII))
        buf.position(11)
        buf.putShort(512.toShort())
        buf.put(8.toByte()) // 4096 bytes per cluster
        buf.position(40)
        buf.putLong(8000L)
        buf.position(510)
        buf.putShort(0xAA55.toShort())
        buf.flip()

        virtualDisk.writeSectors(2048L, 1, buf)

        val sniffer = FilesystemSniffer()
        val result = sniffer.detectFilesystem(virtualDisk, 2048L, 8000L)

        assertNotNull(result)
        assertEquals(FilesystemType.NTFS, result?.type)
        assertEquals(4096, result?.clusterSizeBytes)
    }

    @Test
    fun testSniffExt4() = runBlocking {
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = 10000L)
        // ext4 superblock is at byte offset 1024 (LBA + 2 for 512B sectors)
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)

        buf.position(4)
        buf.putInt(2000) // blocks count
        buf.position(24)
        buf.putInt(2) // log block size (1024 << 2 = 4096 bytes)
        buf.position(56)
        buf.putShort(0xEF53.toShort()) // ext4 magic
        buf.position(120)
        buf.put("LINUX_DATA\u0000\u0000\u0000\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII))
        buf.flip()

        virtualDisk.writeSectors(2048L + 2L, 1, buf)

        val sniffer = FilesystemSniffer()
        val result = sniffer.detectFilesystem(virtualDisk, 2048L, 8000L)

        assertNotNull(result)
        assertEquals(FilesystemType.EXT4, result?.type)
        assertEquals("LINUX_DATA", result?.volumeLabel)
        assertEquals(4096, result?.clusterSizeBytes)
    }
}
