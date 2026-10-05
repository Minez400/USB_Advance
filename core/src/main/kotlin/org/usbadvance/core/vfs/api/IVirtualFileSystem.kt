package org.usbadvance.core.vfs.api

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.vfs.model.ContiguityInfo
import org.usbadvance.core.vfs.model.FsEntry
import java.io.Closeable

/**
 * High-level abstraction for a mounted filesystem operating on raw block storage
 * in userspace without requiring root privileges.
 */
interface IVirtualFileSystem : Closeable {
    val filesystemType: FilesystemType
    val volumeLabel: String
    val totalSpaceBytes: Long
    val freeSpaceBytes: Long
    val clusterSizeBytes: Int
    val isReadOnly: Boolean

    /**
     * Mounts the filesystem from the underlying block device starting at [startLba].
     */
    suspend fun mount(
        blockDevice: IBlockDevice,
        startLba: Long,
        sectorCount: Long
    ): Boolean

    /**
     * Lists child entries inside [directoryPath] (use "/" for root directory).
     */
    suspend fun listEntries(directoryPath: String = "/"): List<FsEntry>

    /**
     * Retrieves metadata for a specific entry at [path].
     */
    suspend fun getEntry(path: String): FsEntry?

    /**
     * Opens a file for reading and/or writing.
     */
    suspend fun openFile(path: String, writeMode: Boolean = false): IVfsFileHandle

    /**
     * Creates a new empty regular file at [path].
     */
    suspend fun createFile(path: String): FsEntry? = null

    /**
     * Creates a new directory at [path].
     */
    suspend fun createDirectory(path: String): Boolean

    /**
     * Deletes a file or an empty directory at [path].
     */
    suspend fun deleteEntry(path: String): Boolean

    /**
     * Renames or moves an entry from [oldPath] to [newPath].
     */
    suspend fun renameEntry(oldPath: String, newPath: String): Boolean

    /**
     * Checks cluster contiguity / fragmentation status of a file.
     * Essential for PS2 Open PS2 Loader (OPL) compatibility.
     */
    suspend fun checkContiguity(path: String): ContiguityInfo

    /**
     * Unmounts the filesystem, committing all cached FAT/Bitmap tables and closing references.
     */
    suspend fun unmount()
}
