package org.usbadvance.core.vfs.fat32

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.nio.ByteOrder
import java.util.Arrays
import kotlin.math.min

/**
 * Pure Kotlin userspace FAT32 Virtual File System driver with VFAT (LFN) support.
 * Operates directly on [IBlockDevice] over USB OTG Host APIs.
 * Supports full read and write, file import/export, directory creation and deletion.
 */
class Fat32FileSystem : IVirtualFileSystem {

    override val filesystemType: FilesystemType = FilesystemType.FAT32
    override var volumeLabel: String = "FAT32 DRIVE"
        private set
    override var totalSpaceBytes: Long = 0L
        private set
    override var freeSpaceBytes: Long = 0L
        private set
    override var clusterSizeBytes: Int = 4096
        private set
    override val isReadOnly: Boolean = false

    private var blockDevice: IBlockDevice? = null
    private var partitionStartLba: Long = 0L
    private var sectorsPerCluster: Int = 8
    private var sectorSize: Int = 512
    private var reservedSectors: Int = 32
    private var numFats: Int = 2
    private var fatSizeSectors: Long = 0L
    private var rootCluster: Long = 2L
    private var fatStartLba: Long = 0L
    private var dataStartLba: Long = 0L

    // FAT sector cache
    private var cachedFatLba: Long = -1L
    private val cachedFatSector = ByteArray(4096)

    override suspend fun mount(
        blockDevice: IBlockDevice,
        startLba: Long,
        sectorCount: Long
    ): Boolean = withContext(Dispatchers.IO) {
        unmount()
        this@Fat32FileSystem.blockDevice = blockDevice
        this@Fat32FileSystem.partitionStartLba = startLba

        sectorSize = blockDevice.sectorSize
        val bootBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        blockDevice.readSectors(startLba, 1, bootBuf)
        bootBuf.position(0)

        val bootBytes = bootBuf.array()
        if (bootBytes[510] != 0x55.toByte() || bootBytes[511] != 0xAA.toByte()) {
            return@withContext false
        }

        sectorsPerCluster = bootBytes[13].toInt() and 0xFF
        if (sectorsPerCluster == 0) return@withContext false

        clusterSizeBytes = sectorSize * sectorsPerCluster
        reservedSectors = bootBuf.getShort(14).toInt() and 0xFFFF
        numFats = bootBytes[16].toInt() and 0xFF
        fatSizeSectors = bootBuf.getInt(36).toLong() and 0xFFFFFFFFL
        rootCluster = bootBuf.getInt(44).toLong() and 0x0FFFFFFFL

        if (fatSizeSectors == 0L || rootCluster < 2L) {
            return@withContext false
        }

        fatStartLba = startLba + reservedSectors
        dataStartLba = startLba + reservedSectors + (numFats * fatSizeSectors)
        totalSpaceBytes = sectorCount * sectorSize
        freeSpaceBytes = totalSpaceBytes

        // Read Volume Label from BPB
        val labelBytes = ByteArray(11)
        System.arraycopy(bootBytes, 71, labelBytes, 0, 11)
        val bpbLabel = String(labelBytes, Charsets.US_ASCII).trim()
        if (bpbLabel.isNotBlank() && !bpbLabel.equals("NO NAME", ignoreCase = true)) {
            volumeLabel = bpbLabel
        }

        cachedFatLba = -1L
        lastAllocatedCluster = 2L
        true
    }

    private fun clusterToLba(cluster: Long): Long {
        if (cluster < 2L) return dataStartLba
        return dataStartLba + (cluster - 2L) * sectorsPerCluster
    }

    internal suspend fun getNextCluster(cluster: Long): Long {
        val dev = blockDevice ?: return 0L
        if (cluster < 2L) return 0L

        val fatByteOffset = cluster * 4L
        val fatSectorOffset = fatByteOffset / sectorSize
        val offsetInSector = (fatByteOffset % sectorSize).toInt()
        val targetFatLba = fatStartLba + fatSectorOffset

        if (cachedFatLba != targetFatLba) {
            val buf = ByteBuffer.wrap(cachedFatSector, 0, sectorSize)
            dev.readSectors(targetFatLba, 1, buf)
            cachedFatLba = targetFatLba
        }

        val buf = ByteBuffer.wrap(cachedFatSector, offsetInSector, 4).order(ByteOrder.LITTLE_ENDIAN)
        val next = buf.getInt().toLong() and 0x0FFFFFFFL
        return next
    }

    internal suspend fun setNextCluster(cluster: Long, next: Long) {
        val dev = blockDevice ?: return
        if (cluster < 2L) return

        val fatByteOffset = cluster * 4L
        val fatSectorOffset = fatByteOffset / sectorSize
        val offsetInSector = (fatByteOffset % sectorSize).toInt()
        val targetFatLba = fatStartLba + fatSectorOffset

        val sectorBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        if (cachedFatLba == targetFatLba) {
            System.arraycopy(cachedFatSector, 0, sectorBuf.array(), 0, sectorSize)
        } else {
            dev.readSectors(targetFatLba, 1, sectorBuf)
        }

        val currentVal = sectorBuf.getInt(offsetInSector)
        val newVal = (currentVal and 0xF0000000.toInt()) or (next.toInt() and 0x0FFFFFFF)
        sectorBuf.putInt(offsetInSector, newVal)
        sectorBuf.position(0)

        // Write back to FAT1
        dev.writeSectors(targetFatLba, 1, sectorBuf)

        // Mirror to FAT2
        if (numFats > 1) {
            val fat2Lba = targetFatLba + fatSizeSectors
            sectorBuf.position(0)
            dev.writeSectors(fat2Lba, 1, sectorBuf)
        }

        if (cachedFatLba == targetFatLba) {
            val cacheBuf = ByteBuffer.wrap(cachedFatSector, offsetInSector, 4).order(ByteOrder.LITTLE_ENDIAN)
            cacheBuf.putInt(newVal)
        }
    }

    private var lastAllocatedCluster: Long = 2L

    internal suspend fun allocateCluster(initialValue: Long = 0x0FFFFFFFL): Long {
        val dev = blockDevice ?: throw IOException("Device not mounted")
        val maxClusters = minOf(totalSpaceBytes / clusterSizeBytes, 67108864L)

        // Start scanning from the last allocated cluster to avoid O(N^2) free space scanning
        for (c in lastAllocatedCluster until maxClusters) {
            if (getNextCluster(c) == 0L) {
                setNextCluster(c, initialValue)
                val zeroBuf = ByteBuffer.allocate(clusterSizeBytes)
                val lba = clusterToLba(c)
                dev.writeSectors(lba, sectorsPerCluster, zeroBuf)
                lastAllocatedCluster = c + 1
                return c
            }
        }

        // Wrap around
        for (c in 2L until lastAllocatedCluster) {
            if (getNextCluster(c) == 0L) {
                setNextCluster(c, initialValue)
                val zeroBuf = ByteBuffer.allocate(clusterSizeBytes)
                val lba = clusterToLba(c)
                dev.writeSectors(lba, sectorsPerCluster, zeroBuf)
                lastAllocatedCluster = c + 1
                return c
            }
        }

        throw IOException("Espaço insuficiente na unidade FAT32")
    }

    override suspend fun listEntries(directoryPath: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val dev = blockDevice ?: throw IOException("Device not mounted")

        val targetDirCluster = if (directoryPath == "/" || directoryPath.isEmpty()) {
            rootCluster
        } else {
            val dirEntry = getEntry(directoryPath) ?: throw FileNotFoundException("Directory not found: $directoryPath")
            if (!dirEntry.isDirectory) throw IOException("Path is not a directory: $directoryPath")
            dirEntry.firstCluster
        }

        val entries = mutableListOf<FsEntry>()
        val clusterBuf = ByteBuffer.allocate(clusterSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        var currentCluster = targetDirCluster

        val lfnParts = mutableMapOf<Int, String>()
        val normalizedDir = if (directoryPath.startsWith("/")) directoryPath else "/$directoryPath"

        while (currentCluster >= 2L) {
            val lba = clusterToLba(currentCluster)
            clusterBuf.clear()
            dev.readSectors(lba, sectorsPerCluster, clusterBuf)
            val bytes = clusterBuf.array()

            var offset = 0
            while (offset < clusterSizeBytes) {
                val firstByte = bytes[offset].toInt() and 0xFF
                if (firstByte == 0x00) {
                    return@withContext entries
                }

                if (firstByte == 0xE5) {
                    lfnParts.clear()
                    offset += 32
                    continue
                }

                val attr = bytes[offset + 11].toInt() and 0xFF

                if (attr == 0x0F) {
                    // VFAT LFN Entry
                    val seq = firstByte and 0x3F
                    val lfnChars = StringBuilder()

                    // Characters 1-5 (offset 1-10)
                    for (c in 0 until 5) {
                        val ch = (bytes[offset + 1 + c * 2].toInt() and 0xFF) or
                                ((bytes[offset + 2 + c * 2].toInt() and 0xFF) shl 8)
                        if (ch != 0 && ch != 0xFFFF) lfnChars.append(ch.toChar())
                    }
                    // Characters 6-11 (offset 14-25)
                    for (c in 0 until 6) {
                        val ch = (bytes[offset + 14 + c * 2].toInt() and 0xFF) or
                                ((bytes[offset + 15 + c * 2].toInt() and 0xFF) shl 8)
                        if (ch != 0 && ch != 0xFFFF) lfnChars.append(ch.toChar())
                    }
                    // Characters 12-13 (offset 28-31)
                    for (c in 0 until 2) {
                        val ch = (bytes[offset + 28 + c * 2].toInt() and 0xFF) or
                                ((bytes[offset + 29 + c * 2].toInt() and 0xFF) shl 8)
                        if (ch != 0 && ch != 0xFFFF) lfnChars.append(ch.toChar())
                    }

                    lfnParts[seq] = lfnChars.toString()
                    offset += 32
                    continue
                }

                if (attr and 0x08 != 0 && attr and 0x10 == 0) {
                    // Volume Label Entry
                    val lbl = String(bytes, offset, 11, Charsets.US_ASCII).trim()
                    if (lbl.isNotBlank()) volumeLabel = lbl
                    lfnParts.clear()
                    offset += 32
                    continue
                }

                // Standard SFN File or Directory Entry
                val isDir = (attr and 0x10) != 0
                val clusterHigh = (bytes[offset + 20].toInt() and 0xFF) or ((bytes[offset + 21].toInt() and 0xFF) shl 8)
                val clusterLow = (bytes[offset + 26].toInt() and 0xFF) or ((bytes[offset + 27].toInt() and 0xFF) shl 8)
                val entryFirstCluster = ((clusterHigh.toLong() shl 16) or clusterLow.toLong()) and 0x0FFFFFFFL

                val buf = ByteBuffer.wrap(bytes, offset + 28, 4).order(ByteOrder.LITTLE_ENDIAN)
                val fileSize = if (isDir) 0L else buf.getInt().toLong() and 0xFFFFFFFFL

                val entryName = if (lfnParts.isNotEmpty()) {
                    val sorted = lfnParts.toSortedMap().values.joinToString("")
                    lfnParts.clear()
                    sorted
                } else {
                    val rawName = String(bytes, offset, 8, Charsets.US_ASCII).trim()
                    val rawExt = String(bytes, offset + 8, 3, Charsets.US_ASCII).trim()
                    if (rawExt.isNotEmpty()) "$rawName.$rawExt" else rawName
                }

                if (entryName != "." && entryName != "..") {
                    val fullPath = if (normalizedDir == "/") "/$entryName" else "$normalizedDir/$entryName"
                    entries.add(
                        FsEntry(
                            name = entryName,
                            fullPath = fullPath,
                            sizeBytes = fileSize,
                            isDirectory = isDir,
                            firstCluster = entryFirstCluster,
                            isReadOnly = (attr and 0x01) != 0,
                            isHidden = (attr and 0x02) != 0,
                            isSystem = (attr and 0x04) != 0
                        )
                    )
                }

                offset += 32
            }

            val next = getNextCluster(currentCluster)
            if (next < 2L || next >= 0x0FFFFFF8L) break
            currentCluster = next
        }

        entries
    }

    override suspend fun getEntry(path: String): FsEntry? = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        if (normalized == "/" || normalized.isEmpty()) {
            return@withContext FsEntry("/", "/", 0L, true, firstCluster = rootCluster)
        }

        val parentPath = normalized.substringBeforeLast('/', "/")
        val targetName = normalized.substringAfterLast('/')

        val entries = try {
            listEntries(parentPath)
        } catch (_: Exception) {
            return@withContext null
        }

        entries.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
    }

    override suspend fun createFile(path: String): FsEntry? = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val existing = getEntry(normalized)
        if (existing != null) return@withContext existing

        val parentPath = normalized.substringBeforeLast('/', "/")
        val fileName = normalized.substringAfterLast('/')

        val targetParentCluster = if (parentPath == "/" || parentPath.isEmpty()) rootCluster else {
            val pEntry = getEntry(parentPath) ?: return@withContext null
            pEntry.firstCluster
        }

        val success = insertDirectoryEntry(
            parentDirCluster = targetParentCluster,
            name = fileName,
            isDirectory = false,
            firstCluster = 0L,
            fileSizeBytes = 0L
        )
        if (!success) return@withContext null

        getEntry(normalized)
    }

    override suspend fun openFile(path: String, writeMode: Boolean): IVfsFileHandle = withContext(Dispatchers.IO) {
        val normalized = if (path.startsWith("/")) path else "/$path"
        var entry = getEntry(normalized)

        if (entry == null) {
            if (!writeMode) throw FileNotFoundException("Arquivo não encontrado: $path")
            entry = createFile(normalized) ?: throw IOException("Falha ao criar arquivo no disco: $path")
        } else if (entry.isDirectory) {
            throw IOException("O caminho especificado é uma pasta: $path")
        }

        val location = findEntryLocation(normalized)

        Fat32FileHandle(
            fileSystem = this@Fat32FileSystem,
            entry = entry,
            dirCluster = location.cluster,
            dirSectorLba = location.sectorLba,
            sfnOffsetInSector = location.offsetInSector
        )
    }

    private data class EntryLocation(val cluster: Long, val sectorLba: Long, val offsetInSector: Int)

    private suspend fun findEntryLocation(path: String): EntryLocation {
        val dev = blockDevice ?: throw IOException("Device not mounted")
        val normalized = if (path.startsWith("/")) path else "/$path"
        val parentPath = normalized.substringBeforeLast('/', "/")
        val targetName = normalized.substringAfterLast('/')

        val parentDirCluster = if (parentPath == "/" || parentPath.isEmpty()) rootCluster else {
            val p = getEntry(parentPath) ?: throw FileNotFoundException("Parent directory not found: $parentPath")
            p.firstCluster
        }

        val targetEntry = getEntry(normalized)

        val clusterBuf = ByteBuffer.allocate(clusterSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        var currentCluster = parentDirCluster

        while (currentCluster >= 2L) {
            val lba = clusterToLba(currentCluster)
            clusterBuf.clear()
            dev.readSectors(lba, sectorsPerCluster, clusterBuf)
            val bytes = clusterBuf.array()

            var offset = 0
            while (offset < clusterSizeBytes) {
                val firstByte = bytes[offset].toInt() and 0xFF
                if (firstByte == 0x00) break
                if (firstByte == 0xE5) {
                    offset += 32
                    continue
                }

                val attr = bytes[offset + 11].toInt() and 0xFF
                if (attr == 0x0F || attr and 0x08 != 0) {
                    offset += 32
                    continue
                }

                val clusterHigh = (bytes[offset + 20].toInt() and 0xFF) or ((bytes[offset + 21].toInt() and 0xFF) shl 8)
                val clusterLow = (bytes[offset + 26].toInt() and 0xFF) or ((bytes[offset + 27].toInt() and 0xFF) shl 8)
                val firstCluster = ((clusterHigh.toLong() shl 16) or clusterLow.toLong()) and 0x0FFFFFFFL

                val rawName = String(bytes, offset, 8, Charsets.US_ASCII).trim()
                val rawExt = String(bytes, offset + 8, 3, Charsets.US_ASCII).trim()
                val sfnName = if (rawExt.isNotEmpty()) "$rawName.$rawExt" else rawName

                if ((targetEntry != null && targetEntry.firstCluster > 0 && targetEntry.firstCluster == firstCluster) ||
                    sfnName.equals(targetName, ignoreCase = true)
                ) {
                    val sectorOffset = offset / sectorSize
                    val offsetInSector = offset % sectorSize
                    return EntryLocation(currentCluster, lba + sectorOffset, offsetInSector)
                }

                offset += 32
            }

            val next = getNextCluster(currentCluster)
            if (next < 2L || next >= 0x0FFFFFF8L) break
            currentCluster = next
        }

        val fallbackLba = clusterToLba(parentDirCluster)
        return EntryLocation(parentDirCluster, fallbackLba, 0)
    }

    internal suspend fun syncFileEntry(handle: Fat32FileHandle) {
        val dev = blockDevice ?: return
        if (handle.dirSectorLba <= 0L) return

        val buf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        dev.readSectors(handle.dirSectorLba, 1, buf)

        val firstCluster = handle.entry.firstCluster
        val offset = handle.sfnOffsetInSector

        buf.putShort(offset + 20, (firstCluster ushr 16).toShort())
        buf.putShort(offset + 26, (firstCluster and 0xFFFF).toShort())
        buf.putInt(offset + 28, handle.sizeBytes.toInt())

        buf.position(0)
        dev.writeSectors(handle.dirSectorLba, 1, buf)
        dev.sync()
    }

    internal suspend fun writeFileBytes(
        handle: Fat32FileHandle,
        fileOffset: Long,
        source: ByteBuffer
    ): Int {
        val dev = blockDevice ?: return -1
        val bytesToWrite = source.remaining()
        if (bytesToWrite == 0) return 0

        var firstCluster = handle.entry.firstCluster
        if (firstCluster < 2L) {
            firstCluster = allocateCluster()
            handle.entry = handle.entry.copy(firstCluster = firstCluster)
            handle.lastClusterIdx = 0
            handle.lastClusterVal = firstCluster
            syncFileEntry(handle)
        }

        val targetClusterIdx = (fileOffset / clusterSizeBytes).toInt()
        var currentCluster = firstCluster
        var startIdx = 0

        // Use cached cluster if it's before or at our target index
        if (handle.lastClusterVal >= 2L && handle.lastClusterIdx <= targetClusterIdx) {
            currentCluster = handle.lastClusterVal
            startIdx = handle.lastClusterIdx
        }

        for (i in startIdx until targetClusterIdx) {
            var next = getNextCluster(currentCluster)
            if (next < 2L || next >= 0x0FFFFFF8L) {
                next = allocateCluster()
                setNextCluster(currentCluster, next)
            }
            currentCluster = next
        }
        
        handle.lastClusterIdx = targetClusterIdx
        handle.lastClusterVal = currentCluster

        var offsetInCluster = (fileOffset % clusterSizeBytes).toInt()
        var transferred = 0
        val clusterBuf = ByteBuffer.allocate(clusterSizeBytes)

        while (transferred < bytesToWrite) {
            val available = clusterSizeBytes - offsetInCluster
            val chunk = min(bytesToWrite - transferred, available)
            val lba = clusterToLba(currentCluster)

            if (offsetInCluster == 0 && chunk == clusterSizeBytes) {
                val slice = source.slice()
                slice.limit(chunk)
                dev.writeSectors(lba, sectorsPerCluster, slice)
                source.position(source.position() + chunk)
            } else {
                clusterBuf.clear()
                dev.readSectors(lba, sectorsPerCluster, clusterBuf)
                val temp = ByteArray(chunk)
                source.get(temp)
                System.arraycopy(temp, 0, clusterBuf.array(), offsetInCluster, chunk)
                clusterBuf.position(0)
                dev.writeSectors(lba, sectorsPerCluster, clusterBuf)
            }

            transferred += chunk
            offsetInCluster = 0

            if (transferred < bytesToWrite) {
                var next = getNextCluster(currentCluster)
                if (next < 2L || next >= 0x0FFFFFF8L) {
                    next = allocateCluster()
                    setNextCluster(currentCluster, next)
                }
                currentCluster = next
                handle.lastClusterIdx++
                handle.lastClusterVal = currentCluster
            }
        }

        return transferred
    }

    override suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val dev = blockDevice ?: return@withContext false
        val normalized = if (path.startsWith("/")) path else "/$path"
        if (normalized == "/" || normalized.isEmpty()) return@withContext false

        val parentPath = normalized.substringBeforeLast('/', "/")
        val dirName = normalized.substringAfterLast('/')

        if (getEntry(normalized) != null) return@withContext false

        val targetParentCluster = if (parentPath == "/" || parentPath.isEmpty()) rootCluster else {
            val pEntry = getEntry(parentPath) ?: return@withContext false
            pEntry.firstCluster
        }

        // 1. Allocate new cluster for this directory
        val newDirCluster = allocateCluster()

        // 2. Initialize new directory cluster with . and .. entries
        val dirSectorBuf = ByteBuffer.allocate(clusterSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        val dirBytes = dirSectorBuf.array()

        // "." entry
        System.arraycopy(".          ".toByteArray(Charsets.US_ASCII), 0, dirBytes, 0, 11)
        dirBytes[11] = 0x10.toByte() // DIRECTORY attribute
        dirBytes[20] = (newDirCluster ushr 16).toByte()
        dirBytes[21] = (newDirCluster ushr 24).toByte()
        dirBytes[26] = (newDirCluster and 0xFF).toByte()
        dirBytes[27] = ((newDirCluster ushr 8) and 0xFF).toByte()

        // ".." entry (0 if parent is root)
        val parentClusterRef = if (targetParentCluster == rootCluster) 0L else targetParentCluster
        System.arraycopy("..         ".toByteArray(Charsets.US_ASCII), 0, dirBytes, 32, 11)
        dirBytes[32 + 11] = 0x10.toByte()
        dirBytes[32 + 20] = (parentClusterRef ushr 16).toByte()
        dirBytes[32 + 21] = (parentClusterRef ushr 24).toByte()
        dirBytes[32 + 26] = (parentClusterRef and 0xFF).toByte()
        dirBytes[32 + 27] = ((parentClusterRef ushr 8) and 0xFF).toByte()

        dirSectorBuf.position(0)
        dev.writeSectors(clusterToLba(newDirCluster), sectorsPerCluster, dirSectorBuf)

        // 3. Insert record in parent directory
        val inserted = insertDirectoryEntry(
            parentDirCluster = targetParentCluster,
            name = dirName,
            isDirectory = true,
            firstCluster = newDirCluster,
            fileSizeBytes = 0L
        )

        if (inserted) {
            dev.sync()
            true
        } else {
            false
        }
    }

    override suspend fun deleteEntry(path: String): Boolean = withContext(Dispatchers.IO) {
        val dev = blockDevice ?: return@withContext false
        val normalized = if (path.startsWith("/")) path else "/$path"
        if (normalized == "/" || normalized.isEmpty()) return@withContext false

        val targetEntry = getEntry(normalized) ?: return@withContext false
        if (targetEntry.isDirectory) {
            val children = listEntries(normalized)
            if (children.isNotEmpty()) {
                throw IOException("A pasta não está vazia")
            }
        }

        val parentPath = normalized.substringBeforeLast('/', "/")
        val targetName = normalized.substringAfterLast('/')

        val targetParentCluster = if (parentPath == "/" || parentPath.isEmpty()) rootCluster else {
            val pEntry = getEntry(parentPath) ?: return@withContext false
            pEntry.firstCluster
        }

        val clusterBuf = ByteBuffer.allocate(clusterSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        var currentCluster = targetParentCluster

        while (currentCluster >= 2L) {
            val lba = clusterToLba(currentCluster)
            clusterBuf.clear()
            dev.readSectors(lba, sectorsPerCluster, clusterBuf)
            val bytes = clusterBuf.array()

            var offset = 0
            var lfnStartOffset = -1
            while (offset < clusterSizeBytes) {
                val firstByte = bytes[offset].toInt() and 0xFF
                if (firstByte == 0x00) break
                if (firstByte == 0xE5) {
                    offset += 32
                    lfnStartOffset = -1
                    continue
                }

                val attr = bytes[offset + 11].toInt() and 0xFF
                if (attr == 0x0F) {
                    if (lfnStartOffset == -1) lfnStartOffset = offset
                    offset += 32
                    continue
                }

                val clusterHigh = (bytes[offset + 20].toInt() and 0xFF) or ((bytes[offset + 21].toInt() and 0xFF) shl 8)
                val clusterLow = (bytes[offset + 26].toInt() and 0xFF) or ((bytes[offset + 27].toInt() and 0xFF) shl 8)
                val firstCluster = ((clusterHigh.toLong() shl 16) or clusterLow.toLong()) and 0x0FFFFFFFL

                val rawName = String(bytes, offset, 8, Charsets.US_ASCII).trim()
                val rawExt = String(bytes, offset + 8, 3, Charsets.US_ASCII).trim()
                val sfnName = if (rawExt.isNotEmpty()) "$rawName.$rawExt" else rawName

                if ((targetEntry.firstCluster > 0 && targetEntry.firstCluster == firstCluster) ||
                    sfnName.equals(targetName, ignoreCase = true)
                ) {
                    val startDel = if (lfnStartOffset != -1) lfnStartOffset else offset
                    var delPos = startDel
                    while (delPos <= offset) {
                        bytes[delPos] = 0xE5.toByte()
                        delPos += 32
                    }

                    clusterBuf.position(0)
                    dev.writeSectors(lba, sectorsPerCluster, clusterBuf)

                    // Free cluster chain
                    var c = targetEntry.firstCluster
                    while (c >= 2L) {
                        val next = getNextCluster(c)
                        setNextCluster(c, 0L)
                        if (next < 2L || next >= 0x0FFFFFF8L) break
                        c = next
                    }

                    dev.sync()
                    return@withContext true
                }

                lfnStartOffset = -1
                offset += 32
            }

            val next = getNextCluster(currentCluster)
            if (next < 2L || next >= 0x0FFFFFF8L) break
            currentCluster = next
        }

        false
    }

    override suspend fun renameEntry(oldPath: String, newPath: String): Boolean = false

    private suspend fun insertDirectoryEntry(
        parentDirCluster: Long,
        name: String,
        isDirectory: Boolean,
        firstCluster: Long,
        fileSizeBytes: Long
    ): Boolean {
        val dev = blockDevice ?: return false

        val sfnBytes = generateSfn(name)
        val lfnList = generateLfnEntries(name, sfnBytes)
        val totalSlotsNeeded = lfnList.size + 1

        var currentCluster = parentDirCluster
        var lastCluster = parentDirCluster
        val clusterBuf = ByteBuffer.allocate(clusterSizeBytes).order(ByteOrder.LITTLE_ENDIAN)

        while (currentCluster >= 2L) {
            lastCluster = currentCluster
            val lba = clusterToLba(currentCluster)
            clusterBuf.clear()
            dev.readSectors(lba, sectorsPerCluster, clusterBuf)
            val bytes = clusterBuf.array()

            var freeCount = 0
            var freeStartIndex = -1

            var offset = 0
            while (offset < clusterSizeBytes) {
                val firstByte = bytes[offset].toInt() and 0xFF
                if (firstByte == 0x00 || firstByte == 0xE5) {
                    if (freeCount == 0) freeStartIndex = offset
                    freeCount++
                    if (freeCount == totalSlotsNeeded) {
                        writeEntrySlots(bytes, freeStartIndex, lfnList, sfnBytes, isDirectory, firstCluster, fileSizeBytes)
                        clusterBuf.position(0)
                        dev.writeSectors(lba, sectorsPerCluster, clusterBuf)
                        return true
                    }
                } else {
                    freeCount = 0
                    freeStartIndex = -1
                }
                offset += 32
            }

            val next = getNextCluster(currentCluster)
            if (next < 2L || next >= 0x0FFFFFF8L) break
            currentCluster = next
        }

        // Allocate a new cluster for directory expansion
        val newDirCluster = allocateCluster()
        setNextCluster(lastCluster, newDirCluster)

        clusterBuf.clear()
        val bytes = clusterBuf.array()
        Arrays.fill(bytes, 0.toByte())
        writeEntrySlots(bytes, 0, lfnList, sfnBytes, isDirectory, firstCluster, fileSizeBytes)
        clusterBuf.position(0)
        dev.writeSectors(clusterToLba(newDirCluster), sectorsPerCluster, clusterBuf)
        return true
    }

    private fun writeEntrySlots(
        bytes: ByteArray,
        startOffset: Int,
        lfnList: List<ByteArray>,
        sfnBytes: ByteArray,
        isDirectory: Boolean,
        firstCluster: Long,
        fileSizeBytes: Long
    ) {
        var curOffset = startOffset
        for (lfn in lfnList) {
            System.arraycopy(lfn, 0, bytes, curOffset, 32)
            curOffset += 32
        }

        System.arraycopy(sfnBytes, 0, bytes, curOffset, 11)
        bytes[curOffset + 11] = if (isDirectory) 0x10.toByte() else 0x20.toByte()
        // Timestamps (create / write)
        bytes[curOffset + 20] = ((firstCluster ushr 16) and 0xFF).toByte()
        bytes[curOffset + 21] = ((firstCluster ushr 24) and 0xFF).toByte()
        bytes[curOffset + 26] = (firstCluster and 0xFF).toByte()
        bytes[curOffset + 27] = ((firstCluster ushr 8) and 0xFF).toByte()

        val sizeBuf = ByteBuffer.wrap(bytes, curOffset + 28, 4).order(ByteOrder.LITTLE_ENDIAN)
        sizeBuf.putInt(fileSizeBytes.toInt())
    }

    private fun generateSfn(fullName: String): ByteArray {
        val dotIndex = fullName.lastIndexOf('.')
        val baseName = if (dotIndex >= 0) fullName.substring(0, dotIndex) else fullName
        val extension = if (dotIndex >= 0) fullName.substring(dotIndex + 1) else ""

        val cleanBase = baseName.replace("[^a-zA-Z0-9_\\-]".toRegex(), "").uppercase()
        val cleanExt = extension.replace("[^a-zA-Z0-9_\\-]".toRegex(), "").uppercase()

        val sfnBase = when {
            cleanBase.length in 1..8 && !fullName.contains(' ') -> cleanBase.padEnd(8, ' ')
            cleanBase.isEmpty() -> "FILE~1  "
            else -> (cleanBase.take(6) + "~1").padEnd(8, ' ')
        }
        val sfnExt = cleanExt.take(3).padEnd(3, ' ')
        return (sfnBase + sfnExt).toByteArray(Charsets.US_ASCII)
    }

    private fun calculateSfnChecksum(sfn: ByteArray): Int {
        var sum = 0
        for (i in 0 until 11) {
            sum = (((sum and 1) shl 7) + (sum shr 1) + (sfn[i].toInt() and 0xFF)) and 0xFF
        }
        return sum
    }

    private fun generateLfnEntries(fullName: String, sfn: ByteArray): List<ByteArray> {
        val dotIndex = fullName.lastIndexOf('.')
        val base = if (dotIndex >= 0) fullName.substring(0, dotIndex) else fullName
        val ext = if (dotIndex >= 0) fullName.substring(dotIndex + 1) else ""
        val isPureSfn = base.length <= 8 && ext.length <= 3 &&
                fullName.all { (it in 'A'..'Z') || (it in '0'..'9') || it == '_' || it == '-' || it == '.' }
        if (isPureSfn) return emptyList()

        val checksum = calculateSfnChecksum(sfn)
        val entries = mutableListOf<ByteArray>()
        val chars = fullName.toCharArray()
        val totalChars = chars.size
        val totalSlots = (totalChars + 12) / 13

        for (seq in 1..totalSlots) {
            val entry = ByteArray(32)
            val isLast = (seq == totalSlots)
            entry[0] = (seq or (if (isLast) 0x40 else 0x00)).toByte()

            val startCharIdx = (seq - 1) * 13

            fun putChar(offset: Int, idx: Int) {
                val code = when {
                    idx < totalChars -> chars[idx].code
                    idx == totalChars -> 0x0000
                    else -> 0xFFFF
                }
                entry[offset] = (code and 0xFF).toByte()
                entry[offset + 1] = ((code ushr 8) and 0xFF).toByte()
            }

            for (i in 0 until 5) putChar(1 + i * 2, startCharIdx + i)
            entry[11] = 0x0F.toByte() // ATTR_LONG_NAME
            entry[12] = 0x00.toByte()
            entry[13] = checksum.toByte()
            for (i in 0 until 6) putChar(14 + i * 2, startCharIdx + 5 + i)
            entry[26] = 0x00.toByte()
            entry[27] = 0x00.toByte()
            for (i in 0 until 2) putChar(28 + i * 2, startCharIdx + 11 + i)

            entries.add(entry)
        }
        return entries.reversed()
    }

    override suspend fun checkContiguity(path: String): ContiguityInfo = withContext(Dispatchers.IO) {
        val entry = getEntry(path) ?: throw FileNotFoundException("File not found: $path")
        if (entry.firstCluster < 2L || entry.sizeBytes == 0L) {
            return@withContext ContiguityInfo(entry.name, 0, 1)
        }

        val totalClusters = (entry.sizeBytes + clusterSizeBytes - 1) / clusterSizeBytes
        val fragments = mutableListOf<ClusterExtent>()

        var currentCluster = entry.firstCluster
        var extentStart = currentCluster
        var extentCount = 1L
        var visited = 1L

        while (visited < totalClusters) {
            val next = getNextCluster(currentCluster)
            if (next < 2L || next >= 0x0FFFFFF8L) break

            if (next == currentCluster + 1L) {
                extentCount++
            } else {
                fragments.add(
                    ClusterExtent(
                        startCluster = extentStart,
                        clusterCount = extentCount,
                        physicalStartLba = clusterToLba(extentStart)
                    )
                )
                extentStart = next
                extentCount = 1L
            }
            currentCluster = next
            visited++
        }

        fragments.add(
            ClusterExtent(
                startCluster = extentStart,
                clusterCount = extentCount,
                physicalStartLba = clusterToLba(extentStart)
            )
        )

        ContiguityInfo(
            fileName = entry.name,
            totalClusters = totalClusters,
            fragmentCount = fragments.size,
            fragments = fragments
        )
    }

    internal suspend fun readFileBytes(
        handle: Fat32FileHandle,
        fileOffset: Long,
        destination: ByteBuffer
    ): Int {
        val dev = blockDevice ?: return -1
        val totalFileSize = handle.sizeBytes
        if (fileOffset >= totalFileSize) return -1

        val bytesToRead = min(destination.remaining().toLong(), totalFileSize - fileOffset).toInt()
        val targetClusterIdx = (fileOffset / clusterSizeBytes).toInt()
        var offsetInCluster = (fileOffset % clusterSizeBytes).toInt()

        var currentCluster = handle.entry.firstCluster
        var startIdx = 0

        // Use cached cluster if it's before or at our target index
        if (handle.lastClusterVal >= 2L && handle.lastClusterIdx <= targetClusterIdx) {
            currentCluster = handle.lastClusterVal
            startIdx = handle.lastClusterIdx
        }

        for (i in startIdx until targetClusterIdx) {
            currentCluster = getNextCluster(currentCluster)
            if (currentCluster < 2L || currentCluster >= 0x0FFFFFF8L) return -1
        }
        
        handle.lastClusterIdx = targetClusterIdx
        handle.lastClusterVal = currentCluster

        val clusterBuf = ByteBuffer.allocate(clusterSizeBytes)
        var transferred = 0

        while (transferred < bytesToRead && currentCluster >= 2L) {
            val lba = clusterToLba(currentCluster)
            clusterBuf.clear()
            dev.readSectors(lba, sectorsPerCluster, clusterBuf)

            val available = clusterSizeBytes - offsetInCluster
            val chunk = min(bytesToRead - transferred, available)

            destination.put(clusterBuf.array(), offsetInCluster, chunk)
            transferred += chunk

            offsetInCluster = 0
            
            if (transferred < bytesToRead) {
                val next = getNextCluster(currentCluster)
                if (next < 2L || next >= 0x0FFFFFF8L) break
                currentCluster = next
                handle.lastClusterIdx++
                handle.lastClusterVal = currentCluster
            }
        }

        return transferred
    }

    override suspend fun unmount() {
        blockDevice = null
        cachedFatLba = -1L
    }

    override fun close() {
        blockDevice = null
        cachedFatLba = -1L
    }
}
