package org.usbadvance.core.vfs.api

import org.usbadvance.core.vfs.model.FsEntry
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Handle to an opened file on a mounted virtual file system, supporting
 * random access reads and writes without loading the whole file into RAM.
 */
interface IVfsFileHandle : Closeable {
    val entry: FsEntry
    val sizeBytes: Long get() = entry.sizeBytes

    /**
     * Reads up to [destination.remaining()] bytes starting from [fileOffset] into [destination].
     * Returns the actual number of bytes read, or -1 if [fileOffset] is at or past EOF.
     */
    suspend fun read(fileOffset: Long, destination: ByteBuffer): Int

    /**
     * Writes [source.remaining()] bytes from [source] into the file starting at [fileOffset].
     * Returns the actual number of bytes written.
     */
    suspend fun write(fileOffset: Long, source: ByteBuffer): Int

    /**
     * Flushes any pending writes and commits metadata.
     */
    suspend fun sync()
}
