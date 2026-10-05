package org.usbadvance.core.vfs.stream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.vfs.api.IVfsFileHandle
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import org.usbadvance.core.vfs.model.ContiguityInfo
import org.usbadvance.core.vfs.model.FsEntry
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import kotlin.math.min

class LocalMediaStreamServerTest {

    private class FakeVfs(val fileData: ByteArray) : IVirtualFileSystem {
        override val filesystemType: FilesystemType = FilesystemType.EXFAT
        override val volumeLabel: String = "TEST_USB"
        override val totalSpaceBytes: Long = 1000000L
        override val freeSpaceBytes: Long = 500000L
        override val clusterSizeBytes: Int = 32768
        override val isReadOnly: Boolean = true

        override suspend fun mount(blockDevice: IBlockDevice, startLba: Long, sectorCount: Long): Boolean = true

        override suspend fun listEntries(directoryPath: String): List<FsEntry> = listOf(
            FsEntry(name = "test.mp4", fullPath = "/test.mp4", sizeBytes = fileData.size.toLong(), isDirectory = false)
        )

        override suspend fun getEntry(path: String): FsEntry? = if (path == "/test.mp4") {
            FsEntry(name = "test.mp4", fullPath = "/test.mp4", sizeBytes = fileData.size.toLong(), isDirectory = false)
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
        override suspend fun checkContiguity(path: String): ContiguityInfo = ContiguityInfo("test.mp4", 1, 1)
        override suspend fun unmount() {}
        override fun close() {}
    }

    @Test
    fun testHttpRangeRequestPartialContent() = runBlocking {
        // Create 10,000 bytes of test data
        val testData = ByteArray(10000) { (it % 256).toByte() }
        val fakeVfs = FakeVfs(testData)

        val server = LocalMediaStreamServer(fakeVfs)
        server.start()

        try {
            assertTrue("Server should be running", server.isRunning)
            assertTrue("Server should be assigned an ephemeral port", server.port > 0)

            val streamUrl = server.getStreamUrl("/test.mp4")
            val url = URL(streamUrl)

            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", "bytes=500-999")
            conn.connect()

            val responseCode = conn.responseCode
            assertEquals(206, responseCode)
            assertEquals("bytes 500-999/10000", conn.getHeaderField("Content-Range"))
            assertEquals(500, conn.contentLength)

            val receivedBytes = conn.inputStream.readBytes()
            assertEquals(500, receivedBytes.size)

            // Verify content matches expected slice
            for (i in 0 until 500) {
                assertEquals(testData[500 + i], receivedBytes[i])
            }
        } finally {
            server.close()
        }
    }
}
