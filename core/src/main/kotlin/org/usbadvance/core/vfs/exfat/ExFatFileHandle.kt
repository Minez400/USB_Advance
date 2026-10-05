package org.usbadvance.core.vfs.exfat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.usbadvance.core.fs.nativebridge.ExFatNativeBridge
import org.usbadvance.core.vfs.api.IVfsFileHandle
import org.usbadvance.core.vfs.model.FsEntry
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Handle to an opened file on an exFAT filesystem supporting zero-copy streaming
 * and O(1) contiguous block retrieval.
 */
class ExFatFileHandle(
    private val engineHandle: Long,
    override val entry: FsEntry,
    private val noFatChain: Boolean
) : IVfsFileHandle {

    private var isClosed = false

    override suspend fun read(fileOffset: Long, destination: ByteBuffer): Int = withContext(Dispatchers.IO) {
        if (isClosed) throw IOException("File handle is closed")
        if (engineHandle == 0L) throw IOException("exFAT engine is not initialized")
        if (fileOffset >= sizeBytes) return@withContext -1

        val remainingCapacity = destination.remaining()
        if (remainingCapacity == 0) return@withContext 0

        val maxAllowed = min(remainingCapacity.toLong(), sizeBytes - fileOffset).toInt()

        if (destination.isDirect) {
            val bytesRead = ExFatNativeBridge.nativeReadFile(
                handle = engineHandle,
                firstCluster = entry.firstCluster,
                fileSize = entry.sizeBytes,
                noFatChain = noFatChain,
                fileOffset = fileOffset,
                destinationBuffer = destination,
                bufferOffset = destination.position(),
                readLength = maxAllowed
            )
            if (bytesRead > 0) {
                destination.position(destination.position() + bytesRead)
            }
            bytesRead
        } else {
            // Allocate temporary direct buffer for JVM heap target
            val directBuffer = ByteBuffer.allocateDirect(maxAllowed)
            val bytesRead = ExFatNativeBridge.nativeReadFile(
                handle = engineHandle,
                firstCluster = entry.firstCluster,
                fileSize = entry.sizeBytes,
                noFatChain = noFatChain,
                fileOffset = fileOffset,
                destinationBuffer = directBuffer,
                bufferOffset = 0,
                readLength = maxAllowed
            )
            if (bytesRead > 0) {
                directBuffer.position(0)
                directBuffer.limit(bytesRead)
                destination.put(directBuffer)
            }
            bytesRead
        }
    }

    override suspend fun write(fileOffset: Long, source: ByteBuffer): Int {
        throw UnsupportedOperationException("exFAT write operation is not currently supported in read-only mode")
    }

    override suspend fun sync() {
        // No-op for read-only handles
    }

    override fun close() {
        isClosed = true
    }
}
