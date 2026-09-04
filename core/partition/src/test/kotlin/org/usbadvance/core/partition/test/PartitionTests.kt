package org.usbadvance.core.partition.test

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.usbadvance.core.partition.align.PartitionAligner
import org.usbadvance.core.partition.gpt.GptEngine
import org.usbadvance.core.partition.mbr.MbrEngine
import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PartitionTests {

    @Test
    fun testPartitionAligner() {
        // Setores de 512 bytes: 1 MiB = 2048 setores
        assertEquals(2048L, PartitionAligner.getFirstAlignedLba(512))

        // Setores de 4096 bytes: 1 MiB = 256 setores
        assertEquals(256L, PartitionAligner.getFirstAlignedLba(4096))

        // Alinhamento para cima
        assertEquals(2048L, PartitionAligner.alignUp(100L, 512))
        assertEquals(2048L, PartitionAligner.alignUp(2048L, 512))
        assertEquals(4096L, PartitionAligner.alignUp(2049L, 512))
    }

    @Test
    fun testMbrWriteAndRead() = runBlocking {
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = 100000L)
        val mbrEngine = MbrEngine()

        val written = mbrEngine.writeSinglePartition(
            blockDevice = virtualDisk,
            fsType = FilesystemType.FAT32,
            bootable = false
        )

        // Valida que a partição começa no LBA 2048 (1 MiB alignment)
        assertEquals(2048L, written.startLba)
        assertEquals(100000L - 2048L, written.sectorCount)
        assertEquals(0x0C.toByte(), written.typeByte) // FAT32 LBA

        // Lê de volta do disco virtual
        val partitions = mbrEngine.readPartitions(virtualDisk)
        assertEquals(1, partitions.size)
        assertEquals(2048L, partitions[0].startLba)
        assertEquals(100000L - 2048L, partitions[0].sectorCount)
        assertTrue(virtualDisk.syncCalled)
    }

    @Test
    fun testGptWrite() = runBlocking {
        val totalSectors = 100000L
        val virtualDisk = VirtualBlockDevice(sectorSize = 512, totalSectors = totalSectors)
        val gptEngine = GptEngine()

        val entry = gptEngine.writeSinglePartitionGpt(
            blockDevice = virtualDisk,
            fsType = FilesystemType.EXFAT,
            partitionName = "TEST_EXFAT"
        )

        assertEquals(2048L, entry.startingLba)
        assertEquals("TEST_EXFAT", entry.name)

        // Valida que o LBA 1 possui a assinatura "EFI PART" (0x5452415020494645L)
        val headerBuf = ByteBuffer.allocateDirect(512)
        headerBuf.order(ByteOrder.LITTLE_ENDIAN)
        virtualDisk.readSectors(1L, 1, headerBuf)
        headerBuf.flip()

        val signature = headerBuf.long
        assertEquals(GptEngine.GPT_SIGNATURE, signature)

        // Valida que o último LBA possui o Backup GPT Header
        val backupBuf = ByteBuffer.allocateDirect(512)
        backupBuf.order(ByteOrder.LITTLE_ENDIAN)
        virtualDisk.readSectors(totalSectors - 1L, 1, backupBuf)
        backupBuf.flip()

        val backupSig = backupBuf.long
        assertEquals(GptEngine.GPT_SIGNATURE, backupSig)
        assertTrue(virtualDisk.syncCalled)
    }
}
