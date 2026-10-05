package org.usbadvance.core.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.vfs.api.IVfsFileHandle
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import org.usbadvance.core.vfs.model.ContiguityInfo
import org.usbadvance.core.vfs.model.FsEntry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min

class TransferManagerTest {

    private class TestVfs(val fileData: ByteArray) : IVirtualFileSystem {
        override val filesystemType: FilesystemType = FilesystemType.EXFAT
        override val volumeLabel: String = "TRANSFER_TEST"
        override val totalSpaceBytes: Long = 1000000L
        override val freeSpaceBytes: Long = 500000L
        override val clusterSizeBytes: Int = 32768
        override val isReadOnly: Boolean = true

        override suspend fun mount(blockDevice: IBlockDevice, startLba: Long, sectorCount: Long): Boolean = true

        override suspend fun listEntries(directoryPath: String): List<FsEntry> = listOf(
            FsEntry(name = "sample.bin", fullPath = "/sample.bin", sizeBytes = fileData.size.toLong(), isDirectory = false)
        )

        override suspend fun getEntry(path: String): FsEntry? = if (path == "/sample.bin") {
            FsEntry(name = "sample.bin", fullPath = "/sample.bin", sizeBytes = fileData.size.toLong(), isDirectory = false)
        } else null

        override suspend fun openFile(path: String, writeMode: Boolean): IVfsFileHandle {
            val resolvedEntry = getEntry(path)!!
            return object : IVfsFileHandle {
                override val entry: FsEntry = resolvedEntry

            override suspend fun read(fileOffset: Long, destination: ByteBuffer): Int {
                if (fileOffset >= fileData.size) return -1
                val remaining = destination.remaining()
                val toRead = min(remaining, fileData.size - fileOffset.toInt())
                destination.put(fileData, fileOffset.toInt(), toRead)
                return toRead
            }

            override suspend fun write(fileOffset: Long, source: ByteBuffer): Int = 0
            override suspend fun sync() {}
            override fun close() {}
        }
    }

        override suspend fun createDirectory(path: String): Boolean = false
        override suspend fun deleteEntry(path: String): Boolean = false
        override suspend fun renameEntry(oldPath: String, newPath: String): Boolean = false
        override suspend fun checkContiguity(path: String): ContiguityInfo = ContiguityInfo("sample.bin", 1, 1)
        override suspend fun unmount() {}
        override fun close() {}
    }

    @Test
    fun testExportFileToStream() = runBlocking {
        val originalBytes = ByteArray(512 * 1024) { (it % 127).toByte() } // 512 KB
        val vfs = TestVfs(originalBytes)

        val manager = TransferManager()
        val out = ByteArrayOutputStream()

        var lastProgressItem: TransferItem? = null
        val success = manager.exportFile(vfs, "/sample.bin", out) { item ->
            lastProgressItem = item
        }

        assertTrue("Transfer should succeed", success)
        assertEquals(TransferStatus.COMPLETED, lastProgressItem?.status)
        assertEquals(originalBytes.size.toLong(), lastProgressItem?.transferredBytes)
        assertArrayEquals(originalBytes, out.toByteArray())
    }
}
