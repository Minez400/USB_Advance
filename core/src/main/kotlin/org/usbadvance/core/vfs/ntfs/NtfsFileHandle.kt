package org.usbadvance.core.vfs.ntfs

import org.usbadvance.core.vfs.api.IVfsFileHandle
import org.usbadvance.core.vfs.model.ClusterExtent
import org.usbadvance.core.vfs.model.ContiguityInfo
import org.usbadvance.core.vfs.model.FsEntry
import java.nio.ByteBuffer

/**
 * Handle to an open NTFS file for streaming and contiguity inspection.
 */
class NtfsFileHandle(
    override val entry: FsEntry,
    internal val extents: List<ClusterExtent>,
    internal val residentData: ByteArray?,
    private val fs: NtfsFileSystem
) : IVfsFileHandle {

    override val sizeBytes: Long = entry.sizeBytes

    override suspend fun read(fileOffset: Long, destination: ByteBuffer): Int {
        if (fileOffset >= sizeBytes) return -1
        val remaining = destination.remaining()
        if (remaining <= 0) return 0
        val bytesToRead = minOf(remaining.toLong(), sizeBytes - fileOffset).toInt()

        if (residentData != null) {
            val off = fileOffset.toInt()
            val available = residentData.size - off
            if (available <= 0) return -1
            val chunk = minOf(bytesToRead, available)
            destination.put(residentData, off, chunk)
            return chunk
        }

        return fs.readFromExtents(extents, fileOffset, bytesToRead, destination)
    }

    override suspend fun write(fileOffset: Long, source: ByteBuffer): Int {
        throw UnsupportedOperationException("NTFS write operations are not supported in read-only VFS")
    }

    override suspend fun sync() {
        // Read-only handle, no-op
    }

    suspend fun getContiguityInfo(): ContiguityInfo {
        if (residentData != null) {
            return ContiguityInfo(
                fileName = entry.name,
                totalClusters = 1L,
                fragmentCount = 1,
                fragments = extents
            )
        }

        val totalClusters = extents.sumOf { it.clusterCount }
        return ContiguityInfo(
            fileName = entry.name,
            totalClusters = maxOf(1L, totalClusters),
            fragmentCount = maxOf(1, extents.size),
            fragments = extents
        )
    }

    override fun close() {
        // No-op for read-only userspace handle
    }
}
