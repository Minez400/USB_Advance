package org.usbadvance.core.vfs.fat32

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.usbadvance.core.partition.test.VirtualBlockDevice
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Fat32FileSystemTest {

    @Test
    fun testFat32MountAndRead() = runBlocking {
        val dev = VirtualBlockDevice(sectorSize = 512, totalSectors = 20000)

        // Construct synthetic FAT32 BPB at LBA 0
        val bpb = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        bpb.put(0xEB.toByte())
        bpb.put(0x58.toByte())
        bpb.put(0x90.toByte())
        bpb.position(11)
        bpb.putShort(512.toShort()) // bytes per sector
        bpb.put(8.toByte()) // sectors per cluster (4096 bytes)
        bpb.putShort(32.toShort()) // reserved sectors
        bpb.put(2.toByte()) // 2 FATs
        bpb.position(36)
        bpb.putInt(100) // fat size 32 (100 sectors)
        bpb.position(44)
        bpb.putInt(2) // root cluster = 2
        bpb.position(71)
        bpb.put("TESTFAT32  ".toByteArray(Charsets.US_ASCII))
        bpb.position(82)
        bpb.put("FAT32   ".toByteArray(Charsets.US_ASCII))
        bpb.position(510)
        bpb.put(0x55.toByte())
        bpb.put(0xAA.toByte())
        bpb.flip()
        dev.writeSectors(0L, 1, bpb)

        // Set up FAT table at LBA 32 (reservedSectors = 32)
        // FAT entries:
        // Cluster 0: Media descriptor
        // Cluster 1: EOC
        // Cluster 2 (root dir): EOC (0x0FFFFFFF)
        // Cluster 3 (file1): cluster 4
        // Cluster 4: EOC (0x0FFFFFFF) -> file1 spans cluster 3 and 4 (contiguous!)
        val fatSector = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        fatSector.putInt(0x0FFFFFF8) // cluster 0
        fatSector.putInt(0x0FFFFFFF) // cluster 1
        fatSector.putInt(0x0FFFFFFF) // cluster 2 (root dir EOF)
        fatSector.putInt(4)          // cluster 3 points to 4
        fatSector.putInt(0x0FFFFFFF) // cluster 4 EOF
        fatSector.flip()
        dev.writeSectors(32L, 1, fatSector)

        // Data start LBA = 32 + (2 * 100) = 232
        // Cluster 2 LBA = 232 + (2 - 2) * 8 = 232
        // Write Root Directory at LBA 232 containing 1 file entry: "GAME    ISO"
        val rootDirSector = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        // SFN entry:
        rootDirSector.put("GAME    ISO".toByteArray(Charsets.US_ASCII)) // name (8) + ext (3)
        rootDirSector.put(0x20.toByte()) // attribute: archive
        rootDirSector.put(ByteArray(8)) // reserved / timestamps
        rootDirSector.position(20)
        rootDirSector.putShort(0.toShort()) // cluster high = 0
        rootDirSector.position(26)
        rootDirSector.putShort(3.toShort()) // cluster low = 3 (firstCluster = 3)
        rootDirSector.putInt(5000) // file size = 5000 bytes
        rootDirSector.flip()
        dev.writeSectors(232L, 1, rootDirSector)

        // Cluster 3 LBA = 232 + (3 - 2) * 8 = 240
        // Write test data to cluster 3
        val testFileContent = ByteArray(5000) { (it % 100).toByte() }
        val cluster3Buf = ByteBuffer.wrap(testFileContent, 0, 4096)
        dev.writeSectors(240L, 8, cluster3Buf)

        // Cluster 4 LBA = 232 + (4 - 2) * 8 = 248
        val cluster4Buf = ByteBuffer.allocate(4096)
        cluster4Buf.put(testFileContent, 4096, 5000 - 4096)
        cluster4Buf.flip()
        dev.writeSectors(248L, 8, cluster4Buf)

        // Now test Fat32FileSystem
        val fs = Fat32FileSystem()
        val mounted = fs.mount(dev, 0L, 20000L)
        assertTrue("FAT32 filesystem should mount successfully", mounted)
        assertEquals("TESTFAT32", fs.volumeLabel)
        assertEquals(4096, fs.clusterSizeBytes)

        // List root directory
        val entries = fs.listEntries("/")
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("GAME.ISO", entry.name)
        assertEquals(5000L, entry.sizeBytes)
        assertEquals(3L, entry.firstCluster)
        assertTrue("Game ISO should be recognized as disc image", entry.isDiscImage)

        // Check contiguity
        val contiguity = fs.checkContiguity("/GAME.ISO")
        assertTrue("File clusters 3 and 4 are consecutive, so it should be contiguous", contiguity.isContiguous)
        assertEquals(1, contiguity.fragmentCount)

        // Read file content
        val handle = fs.openFile("/GAME.ISO")
        assertNotNull(handle)
        val readDestination = ByteBuffer.allocate(5000)
        val readBytes = handle.read(0L, readDestination)
        assertEquals(5000, readBytes)
        assertArrayEquals(testFileContent, readDestination.array())
        handle.close()

        // Test creating a directory
        val createdDir = fs.createDirectory("/NEWDIR")
        assertTrue("createDirectory should return true", createdDir)

        val rootAfterDir = fs.listEntries("/")
        println("ENTRIES: " + rootAfterDir.map { it.name })
        assertEquals("Found: " + rootAfterDir.map { it.name }, 2, rootAfterDir.size)
        assertTrue(rootAfterDir.any { it.name == "GAME.ISO" && !it.isDirectory })
        assertTrue(rootAfterDir.any { it.name == "NEWDIR" && it.isDirectory })

        // Test creating and writing a file inside /NEWDIR
        val newFileHandle = fs.openFile("/NEWDIR/TEST.TXT", writeMode = true)
        val testWriteBytes = "Hello FAT32 Virtual Userspace World!".toByteArray(Charsets.UTF_8)
        val written = newFileHandle.write(0L, ByteBuffer.wrap(testWriteBytes))
        assertEquals(testWriteBytes.size, written)
        newFileHandle.sync()
        newFileHandle.close()

        // Verify file in directory listing
        val dirEntries = fs.listEntries("/NEWDIR")
        assertEquals(1, dirEntries.size)
        assertEquals("TEST.TXT", dirEntries[0].name)
        assertEquals(testWriteBytes.size.toLong(), dirEntries[0].sizeBytes)

        // Read back written file
        val readHandle = fs.openFile("/NEWDIR/TEST.TXT")
        val readBuf = ByteBuffer.allocate(testWriteBytes.size)
        val readCount = readHandle.read(0L, readBuf)
        assertEquals(testWriteBytes.size, readCount)
        assertArrayEquals(testWriteBytes, readBuf.array())
        readHandle.close()

        // Delete file and directory
        val fileDeleted = fs.deleteEntry("/NEWDIR/TEST.TXT")
        assertTrue(fileDeleted)
        assertEquals(0, fs.listEntries("/NEWDIR").size)

        val dirDeleted = fs.deleteEntry("/NEWDIR")
        assertTrue(dirDeleted)

        val finalEntries = fs.listEntries("/")
        assertEquals(1, finalEntries.size)
        assertEquals("GAME.ISO", finalEntries[0].name)

        fs.close()
    }
}
