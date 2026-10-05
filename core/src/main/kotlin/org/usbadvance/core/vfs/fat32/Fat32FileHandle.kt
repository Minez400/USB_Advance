package org.usbadvance.core.vfs.fat32

import org.usbadvance.core.vfs.api.IVfsFileHandle
import org.usbadvance.core.vfs.model.FsEntry
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Handle to an opened file on a FAT32 filesystem supporting random access reads and writes.
 */
class Fat32FileHandle(
    private val fileSystem: Fat32FileSystem,
    override var entry: FsEntry,
    internal val dirCluster: Long = 0L,
    internal val dirSectorLba: Long = 0L,
    internal val sfnOffsetInSector: Int = 0
) : IVfsFileHandle {

    private var isClosed = false
    private var currentSize: Long = entry.sizeBytes
    override val sizeBytes: Long get() = currentSize
    
    internal var lastClusterIdx: Int = 0
    internal var lastClusterVal: Long = entry.firstCluster

    override suspend fun read(fileOffset: Long, destination: ByteBuffer): Int {
        if (isClosed) throw IOException("File handle is closed")
        if (fileOffset >= currentSize) return -1
        return fileSystem.readFileBytes(this, fileOffset, destination)
    }

    override suspend fun write(fileOffset: Long, source: ByteBuffer): Int {
        if (isClosed) throw IOException("File handle is closed")
        val written = fileSystem.writeFileBytes(this, fileOffset, source)
        if (fileOffset + written > currentSize) {
            currentSize = fileOffset + written
            entry = entry.copy(sizeBytes = currentSize)
        }
        return written
    }

    override suspend fun sync() {
        if (isClosed) return
        fileSystem.syncFileEntry(this)
    }

    override fun close() {
        isClosed = true
    }
}
