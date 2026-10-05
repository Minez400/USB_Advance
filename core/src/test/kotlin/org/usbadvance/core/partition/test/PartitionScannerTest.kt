package org.usbadvance.core.partition.test

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.usbadvance.core.partition.PartitionScanner
import org.usbadvance.core.partition.gpt.GptEngine
import org.usbadvance.core.partition.mbr.MbrEngine
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.PartitionTableType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PartitionScannerTest {

    @Test
    fun testGptWriteAndReadPartitions() = runBlocking {
        val totalSectors = 100000L
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = totalSectors)
        val gptEngine = GptEngine()

        gptEngine.writeSinglePartitionGpt(
            blockDevice = virtualDisk,
            fsType = FilesystemType.EXFAT,
            partitionName = "GAMES_SSD"
        )

        val partitions = gptEngine.readPartitions(virtualDisk)
        assertEquals(1, partitions.size)
        assertEquals(2048L, partitions[0].startingLba)
        assertEquals("GAMES_SSD", partitions[0].name)
    }

    @Test
    fun testScanMbrDiskWithFat32() = runBlocking {
        val totalSectors = 100000L
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = totalSectors)
        val mbrEngine = MbrEngine()

        mbrEngine.writeSinglePartition(
            blockDevice = virtualDisk,
            fsType = FilesystemType.FAT32,
            bootable = false
        )

        // Write FAT32 VBR at LBA 2048
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(11)
        buf.putShort(512.toShort())
        buf.put(64.toByte()) // 32 KB clusters
        buf.putShort(32.toShort())
        buf.put(2.toByte())
        buf.position(71)
        buf.put("RETRO_GAMES".toByteArray(Charsets.US_ASCII))
        buf.position(82)
        buf.put("FAT32   ".toByteArray(Charsets.US_ASCII))
        buf.position(510)
        buf.putShort(0xAA55.toShort())
        buf.flip()
        virtualDisk.writeSectors(2048L, 1, buf)

        val scanner = PartitionScanner()
        val detected = scanner.scanPartitions(virtualDisk)

        assertEquals(1, detected.size)
        val part = detected[0]
        assertEquals(2048L, part.startLba)
        assertEquals(PartitionTableType.MBR, part.partitionTableType)
        assertEquals(FilesystemType.FAT32, part.filesystem)
        assertEquals("RETRO_GAMES", part.label)
    }

    @Test
    fun testScanGptDiskWithExFat() = runBlocking {
        val totalSectors = 100000L
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = totalSectors)
        val gptEngine = GptEngine()

        gptEngine.writeSinglePartitionGpt(
            blockDevice = virtualDisk,
            fsType = FilesystemType.EXFAT,
            partitionName = "EXFAT_OPL"
        )

        // Write exFAT VBR at LBA 2048
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(3)
        buf.put("EXFAT   ".toByteArray(Charsets.US_ASCII))
        buf.position(72)
        buf.putLong(totalSectors - 2048L)
        buf.position(108)
        buf.put(9.toByte()) // 512B sectors
        buf.put(6.toByte()) // 32 KB clusters
        buf.position(510)
        buf.putShort(0xAA55.toShort())
        buf.flip()
        virtualDisk.writeSectors(2048L, 1, buf)

        val scanner = PartitionScanner()
        val detected = scanner.scanPartitions(virtualDisk)

        assertEquals(1, detected.size)
        val part = detected[0]
        assertEquals(2048L, part.startLba)
        assertEquals(PartitionTableType.GPT, part.partitionTableType)
        assertEquals(FilesystemType.EXFAT, part.filesystem)
    }

    @Test
    fun testScanSuperfloppyDisk() = runBlocking {
        val totalSectors = 50000L
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = totalSectors)

        // Write exFAT directly at LBA 0 (no partition table)
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(3)
        buf.put("EXFAT   ".toByteArray(Charsets.US_ASCII))
        buf.position(108)
        buf.put(9.toByte())
        buf.put(3.toByte()) // 4 KB clusters
        buf.position(510)
        buf.putShort(0xAA55.toShort())
        buf.flip()
        virtualDisk.writeSectors(0L, 1, buf)

        val scanner = PartitionScanner()
        val detected = scanner.scanPartitions(virtualDisk)

        assertEquals(1, detected.size)
        val part = detected[0]
        assertEquals(0L, part.startLba)
        assertEquals(PartitionTableType.RAW_SUPERFLOPPY, part.partitionTableType)
        assertEquals(FilesystemType.EXFAT, part.filesystem)
    }

    @Test
    fun testMbrProtectivePartitionNotReportedAsData() = runBlocking {
        val totalSectors = 100000L
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = totalSectors)

        // Write an MBR with only 0xEE protective partition and no GPT
        val buf = ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(446)
        buf.put(0x00.toByte()) // not bootable
        buf.put(0.toByte()); buf.put(0.toByte()); buf.put(0.toByte()) // CHS
        buf.put(0xEE.toByte()) // Type 0xEE (GPT protective)
        buf.put(0.toByte()); buf.put(0.toByte()); buf.put(0.toByte()) // CHS
        buf.putInt(1) // start LBA 1
        buf.putInt(99999) // count
        buf.position(510)
        buf.putShort(0xAA55.toShort())
        buf.flip()
        virtualDisk.writeSectors(0L, 1, buf)

        val scanner = PartitionScanner()
        val detected = scanner.scanPartitions(virtualDisk)

        // 0xEE must NOT be returned as a valid data partition!
        assertTrue(detected.none { it.mbrType == 0xEE.toByte() })
    }
}
