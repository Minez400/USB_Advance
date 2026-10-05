package org.usbadvance.core.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Orchestrates background high-throughput transfers between local Android storage
 * (via SAF / File I/O) and Virtual File Systems on physical USB OTG storage.
 */
class TransferManager {

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    private val cancelledTransferIds = mutableSetOf<String>()

    /**
     * Imports a file from an external InputStream (e.g. Android internal storage / SAF) into USB VFS.
     */
    suspend fun importFile(
        vfs: IVirtualFileSystem,
        destinationDir: String,
        fileName: String,
        inputStream: InputStream,
        totalBytes: Long,
        onProgress: ((TransferItem) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val targetPath = if (destinationDir == "/" || destinationDir.isEmpty()) "/$fileName" else "$destinationDir/$fileName"
        val item = TransferItem(
            sourceName = fileName,
            destinationPath = targetPath,
            totalBytes = totalBytes,
            status = TransferStatus.IN_PROGRESS
        )

        _transfers.update { it + item }
        onProgress?.invoke(item)

        var transferred = 0L
        val startTime = System.currentTimeMillis()
        var lastReportTime = startTime
        var lastTransferred = 0L
        val chunkSize = 256 * 1024 // 256 KB chunk
        val tempArray = ByteArray(chunkSize)
        val buffer = ByteBuffer.allocateDirect(chunkSize)

        try {
            val handle = vfs.openFile(targetPath, writeMode = true)
            var wasCancelled = false
            handle.use { h ->
                inputStream.use { stream ->
                    while (true) {
                        if (cancelledTransferIds.contains(item.id)) {
                            wasCancelled = true
                            break
                        }

                        val bytesRead = stream.read(tempArray, 0, chunkSize)
                        if (bytesRead <= 0) break

                        buffer.clear()
                        buffer.put(tempArray, 0, bytesRead)
                        buffer.flip()

                        val written = h.write(transferred, buffer)
                        if (written <= 0) break
                        transferred += written

                        val now = System.currentTimeMillis()
                        val deltaMs = now - lastReportTime
                        if (deltaMs >= 400) {
                            val speed = ((transferred - lastTransferred) * 1000L) / maxOf(deltaMs, 1L)
                            lastReportTime = now
                            lastTransferred = transferred

                            val updated = item.copy(
                                transferredBytes = transferred,
                                speedBytesPerSec = speed,
                                status = TransferStatus.IN_PROGRESS
                            )
                            updateTransferItem(updated)
                            onProgress?.invoke(updated)
                        }
                    }
                }
                h.sync()
            }

            if (wasCancelled) {
                cancelledTransferIds.remove(item.id)
                vfs.deleteEntry(targetPath)
                val cancelledItem = item.copy(
                    transferredBytes = transferred,
                    speedBytesPerSec = 0L,
                    status = TransferStatus.CANCELLED
                )
                updateTransferItem(cancelledItem)
                onProgress?.invoke(cancelledItem)
                return@withContext false
            }

            val finalItem = item.copy(
                transferredBytes = transferred,
                speedBytesPerSec = 0L,
                status = TransferStatus.COMPLETED
            )
            updateTransferItem(finalItem)
            onProgress?.invoke(finalItem)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    try {
                        vfs.deleteEntry(targetPath)
                    } catch (ignore: Exception) {}
                }
                throw e
            }
            val failedItem = item.copy(
                transferredBytes = transferred,
                speedBytesPerSec = 0L,
                status = TransferStatus.FAILED,
                errorMessage = e.message ?: "Erro na transferência"
            )
            updateTransferItem(failedItem)
            onProgress?.invoke(failedItem)
            false
        }
    }

    /**
     * Exports a file from USB VFS to an external OutputStream (e.g. Android internal storage / Download folder).
     */
    suspend fun exportFile(
        vfs: IVirtualFileSystem,
        sourceVfsPath: String,
        outputStream: OutputStream,
        onProgress: ((TransferItem) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val entry = vfs.getEntry(sourceVfsPath) ?: return@withContext false
        val item = TransferItem(
            sourceName = entry.name,
            destinationPath = "Armazenamento Interno",
            totalBytes = entry.sizeBytes,
            status = TransferStatus.IN_PROGRESS
        )

        _transfers.update { it + item }
        onProgress?.invoke(item)

        var transferred = 0L
        val startTime = System.currentTimeMillis()
        var lastReportTime = startTime
        var lastTransferred = 0L
        val chunkSize = 256 * 1024 // 256 KB chunk
        val buffer = ByteBuffer.allocateDirect(chunkSize)
        val tempArray = ByteArray(chunkSize)

        try {
            val handle = vfs.openFile(sourceVfsPath, writeMode = false)
            handle.use { h ->
                outputStream.use { out ->
                    while (transferred < entry.sizeBytes) {
                        if (cancelledTransferIds.contains(item.id)) {
                            val cancelledItem = item.copy(
                                transferredBytes = transferred,
                                speedBytesPerSec = 0L,
                                status = TransferStatus.CANCELLED
                            )
                            updateTransferItem(cancelledItem)
                            onProgress?.invoke(cancelledItem)
                            cancelledTransferIds.remove(item.id)
                            return@withContext false
                        }

                        val toRead = min(entry.sizeBytes - transferred, chunkSize.toLong()).toInt()
                        buffer.clear()
                        buffer.limit(toRead)

                        val read = h.read(transferred, buffer)
                        if (read <= 0) break

                        buffer.position(0)
                        buffer.get(tempArray, 0, read)
                        out.write(tempArray, 0, read)

                        transferred += read

                        val now = System.currentTimeMillis()
                        val deltaMs = now - lastReportTime
                        if (deltaMs >= 400) {
                            val speed = ((transferred - lastTransferred) * 1000L) / maxOf(deltaMs, 1L)
                            lastReportTime = now
                            lastTransferred = transferred

                            val updated = item.copy(
                                transferredBytes = transferred,
                                speedBytesPerSec = speed,
                                status = TransferStatus.IN_PROGRESS
                            )
                            updateTransferItem(updated)
                            onProgress?.invoke(updated)
                        }
                    }
                    out.flush()
                }
            }

            val finalItem = item.copy(
                transferredBytes = transferred,
                speedBytesPerSec = 0L,
                status = TransferStatus.COMPLETED
            )
            updateTransferItem(finalItem)
            onProgress?.invoke(finalItem)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val failedItem = item.copy(
                transferredBytes = transferred,
                speedBytesPerSec = 0L,
                status = TransferStatus.FAILED,
                errorMessage = e.message ?: "Erro na transferência"
            )
            updateTransferItem(failedItem)
            onProgress?.invoke(failedItem)
            false
        }
    }

    fun cancelTransfer(id: String) {
        cancelledTransferIds.add(id)
        _transfers.update { list ->
            list.map { if (it.id == id) it.copy(status = TransferStatus.CANCELLED) else it }
        }
    }

    private fun updateTransferItem(updated: TransferItem) {
        _transfers.update { list ->
            list.map { if (it.id == updated.id) updated else it }
        }
    }
}
