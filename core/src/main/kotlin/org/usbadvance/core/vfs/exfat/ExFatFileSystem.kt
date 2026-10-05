package org.usbadvance.core.vfs.exfat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.usbadvance.core.fs.nativebridge.ExFatContiguityVisitor
import org.usbadvance.core.fs.nativebridge.ExFatDirectoryVisitor
import org.usbadvance.core.fs.nativebridge.ExFatNativeBridge
import org.usbadvance.core.fs.nativebridge.NativeVfsBlockReader
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.vfs.api.IVfsFileHandle
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import org.usbadvance.core.vfs.model.ClusterExtent
import org.usbadvance.core.vfs.model.ContiguityInfo
import org.usbadvance.core.vfs.model.FsEntry
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Userspace exFAT Virtual File System driver powered by native C++20 engine.
 * Fully supports direct sector streaming over Android USB OTG Host APIs.
 */
class ExFatFileSystem : IVirtualFileSystem {

    override val filesystemType: FilesystemType = FilesystemType.EXFAT
    override var volumeLabel: String = "USB ADVANCE"
        private set
    override var totalSpaceBytes: Long = 0L
        private set
    override var freeSpaceBytes: Long = 0L
        private set
    override var clusterSizeBytes: Int = 32768
        private set
    override val isReadOnly: Boolean = true

    private var engineHandle: Long = 0L
    private var blockDevice: IBlockDevice? = null

    override suspend fun mount(
        blockDevice: IBlockDevice,
        startLba: Long,
        sectorCount: Long
    ): Boolean = withContext(Dispatchers.IO) {
        unmount()
        this@ExFatFileSystem.blockDevice = blockDevice

        val reader = object : NativeVfsBlockReader {
            override fun onReadSectors(lba: Long, count: Int, destination: ByteBuffer): Boolean {
                return try {
                    runBlocking {
                        blockDevice.readSectors(lba, count, destination)
                    }
                    true
                } catch (e: Exception) {
                    System.err.println("ExFatFileSystem: error reading sectors at LBA $lba: ${e.message}")
                    false
                }
            }
        }

        val handle = ExFatNativeBridge.nativeMount(
            startLba = startLba,
            totalSectors = sectorCount,
            sectorSize = blockDevice.sectorSize,
            reader = reader
        )

        if (handle == 0L) {
            return@withContext false
        }

        engineHandle = handle
        volumeLabel = ExFatNativeBridge.nativeGetVolumeLabel(handle)
        clusterSizeBytes = ExFatNativeBridge.nativeGetClusterSize(handle)
        totalSpaceBytes = sectorCount * blockDevice.sectorSize
        freeSpaceBytes = totalSpaceBytes // exFAT bitmap calculation can refine this

        true
    }

    override suspend fun listEntries(directoryPath: String): List<FsEntry> = withContext(Dispatchers.IO) {
        if (engineHandle == 0L) throw IOException("exFAT filesystem is not mounted")

        val results = mutableListOf<FsEntry>()
        val normalizedDir = if (directoryPath.startsWith("/")) directoryPath else "/$directoryPath"

        val visitor = object : ExFatDirectoryVisitor {
            override fun onEntry(
                name: String,
                sizeBytes: Long,
                isDirectory: Boolean,
                modifiedTimestamp: Long,
                isContiguous: Boolean,
                firstCluster: Long,
                isReadOnly: Boolean,
                isHidden: Boolean,
                isSystem: Boolean
            ) {
                val fullPath = if (normalizedDir == "/") "/$name" else "$normalizedDir/$name"
                results.add(
                    FsEntry(
                        name = name,
                        fullPath = fullPath,
                        sizeBytes = sizeBytes,
                        isDirectory = isDirectory,
                        modifiedTimestamp = modifiedTimestamp,
                        isContiguous = isContiguous,
                        firstCluster = firstCluster,
                        isReadOnly = isReadOnly,
                        isHidden = isHidden,
                        isSystem = isSystem
                    )
                )
            }
        }

        val success = ExFatNativeBridge.nativeListDirectory(
            handle = engineHandle,
            path = normalizedDir,
            visitor = visitor
        )

        if (!success) {
            throw FileNotFoundException("Directory not found or could not be read: $directoryPath")
        }

        results
    }

    override suspend fun getEntry(path: String): FsEntry? = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        if (normalized == "/" || normalized.isEmpty()) {
            return@withContext FsEntry(
                name = "/",
                fullPath = "/",
                sizeBytes = 0L,
                isDirectory = true,
                firstCluster = 2L
            )
        }

        val parentPath = normalized.substringBeforeLast('/', "/")
        val targetName = normalized.substringAfterLast('/')

        val entries = try {
            listEntries(parentPath)
        } catch (e: Exception) {
            return@withContext null
        }

        entries.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
    }

    override suspend fun openFile(path: String, writeMode: Boolean): IVfsFileHandle = withContext(Dispatchers.IO) {
        if (writeMode) {
            throw UnsupportedOperationException("exFAT writeMode is currently not supported")
        }

        val entry = getEntry(path) ?: throw FileNotFoundException("File not found: $path")
        if (entry.isDirectory) {
            throw IOException("Path is a directory, not a regular file: $path")
        }

        ExFatFileHandle(
            engineHandle = engineHandle,
            entry = entry,
            noFatChain = entry.isContiguous
        )
    }

    override suspend fun checkContiguity(path: String): ContiguityInfo = withContext(Dispatchers.IO) {
        val entry = getEntry(path) ?: throw FileNotFoundException("File not found: $path")
        val fragments = mutableListOf<ClusterExtent>()

        val visitor = object : ExFatContiguityVisitor {
            override fun onExtent(startCluster: Long, clusterCount: Long, physicalLba: Long) {
                fragments.add(
                    ClusterExtent(
                        startCluster = startCluster,
                        clusterCount = clusterCount,
                        physicalStartLba = physicalLba
                    )
                )
            }
        }

        val totalClusters = if (clusterSizeBytes > 0) {
            (entry.sizeBytes + clusterSizeBytes - 1) / clusterSizeBytes
        } else 0L

        val success = ExFatNativeBridge.nativeAnalyzeContiguity(
            handle = engineHandle,
            firstCluster = entry.firstCluster,
            fileSize = entry.sizeBytes,
            noFatChain = entry.isContiguous,
            visitor = visitor
        )

        if (!success) {
            // Fallback estimation
            return@withContext ContiguityInfo(
                fileName = entry.name,
                totalClusters = totalClusters,
                fragmentCount = if (entry.isContiguous) 1 else 2,
                fragments = emptyList()
            )
        }

        ContiguityInfo(
            fileName = entry.name,
            totalClusters = totalClusters,
            fragmentCount = fragments.size,
            fragments = fragments
        )
    }

    override suspend fun createDirectory(path: String): Boolean {
        throw UnsupportedOperationException("createDirectory not implemented in read-only mode")
    }

    override suspend fun deleteEntry(path: String): Boolean {
        throw UnsupportedOperationException("deleteEntry not implemented in read-only mode")
    }

    override suspend fun renameEntry(oldPath: String, newPath: String): Boolean {
        throw UnsupportedOperationException("renameEntry not implemented in read-only mode")
    }

    override suspend fun unmount() = withContext(Dispatchers.IO) {
        if (engineHandle != 0L) {
            ExFatNativeBridge.nativeUnmount(engineHandle)
            engineHandle = 0L
        }
        blockDevice = null
    }

    override fun close() {
        if (engineHandle != 0L) {
            ExFatNativeBridge.nativeUnmount(engineHandle)
            engineHandle = 0L
        }
        blockDevice = null
    }
}
