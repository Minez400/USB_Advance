package org.usbadvance.core.vfs.ntfs

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

/**
 * Pure Kotlin userspace NTFS Virtual File System driver.
 * Operates directly on [IBlockDevice] over USB OTG Host APIs without requiring root.
 * Supports directory trees, resident/non-resident files, and extent contiguity inspection.
 */
class NtfsFileSystem : IVirtualFileSystem {

    override val filesystemType: FilesystemType = FilesystemType.NTFS
    override var volumeLabel: String = "NTFS DRIVE"
        private set
    override var totalSpaceBytes: Long = 0L
        private set
    override var freeSpaceBytes: Long = 0L
        private set
    override var clusterSizeBytes: Int = 4096
        private set
    override val isReadOnly: Boolean = true

    private var blockDevice: IBlockDevice? = null
    private var partitionStartLba: Long = 0L
    private var sectorsPerCluster: Int = 8
    private var sectorSize: Int = 512
    private var mftStartCluster: Long = 0L
    private var mftRecordSizeBytes: Int = 1024

    private val mftExtents = mutableListOf<ClusterExtent>()

    override suspend fun mount(
        blockDevice: IBlockDevice,
        startLba: Long,
        sectorCount: Long
    ): Boolean = withContext(Dispatchers.IO) {
        unmount()
        this@NtfsFileSystem.blockDevice = blockDevice
        this@NtfsFileSystem.partitionStartLba = startLba
        this@NtfsFileSystem.sectorSize = blockDevice.sectorSize

        val vbrBuf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        blockDevice.readSectors(startLba, 1, vbrBuf)
        vbrBuf.position(0)

        // Validate NTFS signature: "NTFS    " at offset 3
        val oem = ByteArray(8)
        vbrBuf.position(3)
        vbrBuf.get(oem)
        val oemStr = String(oem, Charsets.US_ASCII)
        if (!oemStr.startsWith("NTFS")) {
            return@withContext false
        }

        val bps = vbrBuf.getShort(11).toInt() and 0xFFFF
        if (bps > 0) sectorSize = bps

        val spcRaw = vbrBuf.get(13).toInt()
        sectorsPerCluster = if (spcRaw > 0) {
            spcRaw
        } else {
            val clusterBytes = 1 shl (-spcRaw)
            maxOf(1, clusterBytes / sectorSize)
        }
        clusterSizeBytes = sectorSize * sectorsPerCluster

        val totalSec = vbrBuf.getLong(40)
        totalSpaceBytes = if (totalSec in 1..sectorCount) totalSec * sectorSize else sectorCount * sectorSize
        freeSpaceBytes = totalSpaceBytes

        mftStartCluster = vbrBuf.getLong(48)

        val mftClustersRaw = vbrBuf.get(64).toInt()
        mftRecordSizeBytes = if (mftClustersRaw < 0) {
            1 shl (-mftClustersRaw)
        } else {
            maxOf(1024, mftClustersRaw * clusterSizeBytes)
        }

        val mftInitialLba = partitionStartLba + (mftStartCluster * sectorsPerCluster)
        mftExtents.clear()
        mftExtents.add(ClusterExtent(mftStartCluster, 1024L, mftInitialLba))

        // Read Record 0 ($MFT) to find complete data runs of $MFT if available
        try {
            val mft0Buf = readMftRecordRaw(0L)
            if (mft0Buf != null) {
                val mftDataRuns = extractDataRuns(mft0Buf, 0x80)
                if (mftDataRuns.isNotEmpty()) {
                    mftExtents.clear()
                    mftExtents.addAll(mftDataRuns)
                }
            }
        } catch (_: Exception) {}

        // Read Record 3 ($Volume) to get the Volume Label if present
        try {
            val volBuf = readMftRecord(3L)
            if (volBuf != null) {
                val label = extractVolumeLabel(volBuf)
                if (!label.isNullOrBlank()) {
                    volumeLabel = label
                }
            }
        } catch (_: Exception) {}

        true
    }

    override suspend fun unmount() {
        blockDevice = null
        partitionStartLba = 0L
        mftExtents.clear()
        volumeLabel = "NTFS DRIVE"
    }

    override fun close() {
        blockDevice = null
        partitionStartLba = 0L
        mftExtents.clear()
    }

    override suspend fun listEntries(directoryPath: String): List<FsEntry> = withContext(Dispatchers.IO) {
        val targetNode = resolveDirectoryNode(directoryPath)
            ?: throw FileNotFoundException("Directory not found: $directoryPath")

        val entries = mutableListOf<FsEntry>()
        val recordBuf = readMftRecord(targetNode.mftIndex)
            ?: return@withContext emptyList()

        val normalizedDir = if (directoryPath.endsWith("/")) directoryPath else "$directoryPath/"

        // Parse $INDEX_ROOT (Attribute 0x90)
        val indexRoot = findAttribute(recordBuf, 0x90)
        if (indexRoot != null && indexRoot.nonResident == 0.toByte()) {
            val valOffset = indexRoot.valueOffset
            val valLength = indexRoot.valueLength
            if (valLength >= 32) {
                recordBuf.position(valOffset)
                val nodeHeaderOffset = valOffset + 16
                val entriesOffset = recordBuf.getInt(nodeHeaderOffset)
                val totalEntriesSize = recordBuf.getInt(nodeHeaderOffset + 4)

                val startEntriesPos = nodeHeaderOffset + entriesOffset
                val endEntriesPos = minOf(recordBuf.capacity(), nodeHeaderOffset + totalEntriesSize)

                parseIndexEntries(recordBuf, startEntriesPos, endEntriesPos, normalizedDir, entries)
            }
        }

        // Parse $INDEX_ALLOCATION (Attribute 0xA0) for large directories
        val indexAlloc = findAttribute(recordBuf, 0xA0)
        if (indexAlloc != null && indexAlloc.nonResident == 1.toByte()) {
            val runs = parseDataRuns(recordBuf, indexAlloc.dataRunsOffset)
            if (runs.isNotEmpty()) {
                val indexBlockSize = 4096
                val blockBuf = ByteBuffer.allocate(indexBlockSize).order(ByteOrder.LITTLE_ENDIAN)
                val dev = blockDevice ?: return@withContext entries

                for (extent in runs) {
                    val extentSectors = extent.clusterCount * sectorsPerCluster
                    var secOffset = 0L
                    while (secOffset + (indexBlockSize / sectorSize) <= extentSectors) {
                        blockBuf.clear()
                        dev.readSectors(extent.physicalStartLba + secOffset, indexBlockSize / sectorSize, blockBuf)
                        blockBuf.flip()

                        if (blockBuf.remaining() >= 24 && blockBuf.getInt(0) == 0x58444E49) {
                            applyNtfsFixup(blockBuf)
                            val nodeHeaderOffset = 24
                            val entriesOffset = blockBuf.getInt(nodeHeaderOffset)
                            val totalEntriesSize = blockBuf.getInt(nodeHeaderOffset + 4)
                            val startPos = nodeHeaderOffset + entriesOffset
                            val endPos = minOf(blockBuf.capacity(), nodeHeaderOffset + totalEntriesSize)
                            parseIndexEntries(blockBuf, startPos, endPos, normalizedDir, entries)
                        }
                        secOffset += (indexBlockSize / sectorSize)
                    }
                }
            }
        }

        val deduplicated = entries
            .filter { it.name != "." && it.name != ".." && !it.name.startsWith("$") }
            .distinctBy { it.name.lowercase() }

        deduplicated
    }

    override suspend fun getEntry(path: String): FsEntry? = withContext(Dispatchers.IO) {
        val trimmed = path.trim().trimStart('/')
        if (trimmed.isEmpty()) {
            return@withContext FsEntry(
                name = "/",
                fullPath = "/",
                sizeBytes = 0L,
                isDirectory = true
            )
        }
        val lastSlash = trimmed.lastIndexOf('/')
        val dirPath = if (lastSlash >= 0) "/" + trimmed.substring(0, lastSlash) else "/"
        val list = listEntries(dirPath)
        val name = if (lastSlash >= 0) trimmed.substring(lastSlash + 1) else trimmed
        list.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    override suspend fun openFile(path: String, writeMode: Boolean): IVfsFileHandle = withContext(Dispatchers.IO) {
        if (writeMode) {
            throw UnsupportedOperationException("NTFS write operations are not supported in read-only VFS")
        }

        val node = resolveFileNode(path)
            ?: throw FileNotFoundException("File not found: $path")

        val recordBuf = readMftRecord(node.mftIndex)
            ?: throw IOException("Failed to read MFT record for file $path")

        val dataAttr = findAttribute(recordBuf, 0x80)
            ?: throw IOException("No data stream found for file $path")

        val entry = FsEntry(
            name = node.name,
            fullPath = path,
            sizeBytes = node.sizeBytes,
            isDirectory = false,
            modifiedTimestamp = node.modifiedTimestamp
        )

        if (dataAttr.nonResident == 0.toByte()) {
            val dataBytes = ByteArray(dataAttr.valueLength)
            recordBuf.position(dataAttr.valueOffset)
            recordBuf.get(dataBytes)
            return@withContext NtfsFileHandle(entry, emptyList(), dataBytes, this@NtfsFileSystem)
        } else {
            val extents = parseDataRuns(recordBuf, dataAttr.dataRunsOffset)
            return@withContext NtfsFileHandle(entry, extents, null, this@NtfsFileSystem)
        }
    }

    override suspend fun createDirectory(path: String): Boolean = false
    override suspend fun deleteEntry(path: String): Boolean = false
    override suspend fun renameEntry(oldPath: String, newPath: String): Boolean = false

    override suspend fun checkContiguity(path: String): ContiguityInfo = withContext(Dispatchers.IO) {
        val handle = openFile(path) as? NtfsFileHandle
            ?: return@withContext ContiguityInfo(
                fileName = path.substringAfterLast('/'),
                totalClusters = 1L,
                fragmentCount = 1,
                fragments = emptyList()
            )
        handle.getContiguityInfo()
    }

    internal suspend fun readFromExtents(
        extents: List<ClusterExtent>,
        fileOffset: Long,
        length: Int,
        destination: ByteBuffer
    ): Int {
        val dev = blockDevice ?: return -1
        val clusterBytes = clusterSizeBytes.toLong()

        var remainingOffset = fileOffset
        var remainingLength = length
        var totalRead = 0

        for (extent in extents) {
            val extentBytes = extent.clusterCount * clusterBytes
            if (remainingOffset >= extentBytes) {
                remainingOffset -= extentBytes
                continue
            }

            val readFromThisExtent = minOf(remainingLength.toLong(), extentBytes - remainingOffset).toInt()
            val startSector = extent.physicalStartLba + (remainingOffset / sectorSize)
            val offsetInFirstSector = (remainingOffset % sectorSize).toInt()

            val sectorsToRead = ((offsetInFirstSector + readFromThisExtent + sectorSize - 1) / sectorSize)
            val tempBuf = ByteBuffer.allocateDirect(sectorsToRead * sectorSize).order(ByteOrder.LITTLE_ENDIAN)
            dev.readSectors(startSector, sectorsToRead, tempBuf)
            tempBuf.position(offsetInFirstSector)
            tempBuf.limit(offsetInFirstSector + readFromThisExtent)

            destination.put(tempBuf)

            totalRead += readFromThisExtent
            remainingLength -= readFromThisExtent
            remainingOffset = 0L

            if (remainingLength <= 0) break
        }

        return if (totalRead > 0) totalRead else -1
    }

    private fun parseIndexEntries(
        buf: ByteBuffer,
        startPos: Int,
        endPos: Int,
        parentDirPath: String,
        outList: MutableList<FsEntry>
    ) {
        var pos = startPos
        while (pos + 16 <= endPos) {
            val entryLength = buf.getShort(pos + 8).toInt() and 0xFFFF
            val contentLength = buf.getShort(pos + 10).toInt() and 0xFFFF
            val flags = buf.getShort(pos + 12).toInt() and 0xFFFF

            if (entryLength <= 0 || (flags and 0x02) != 0) break

            if (contentLength >= 66 && pos + 16 + contentLength <= endPos) {
                val fnOffset = pos + 16
                val modifiedWindowsTime = buf.getLong(fnOffset + 16)
                val realSize = buf.getLong(fnOffset + 56)
                val fileFlags = buf.getInt(fnOffset + 64)
                val isDir = (fileFlags and 0x10000000) != 0 || (fileFlags and 0x10) != 0
                val fnLen = buf.get(fnOffset + 64 + 2).toInt() and 0xFF
                val namespace = buf.get(fnOffset + 64 + 3).toInt() and 0xFF

                if (fnLen in 1..255 && fnOffset + 66 + (fnLen * 2) <= endPos) {
                    val nameChars = CharArray(fnLen)
                    for (c in 0 until fnLen) {
                        nameChars[c] = buf.getChar(fnOffset + 66 + (c * 2))
                    }
                    val name = String(nameChars)
                    val epochMs = (modifiedWindowsTime - 116444736000000000L) / 10000L
                    val full = if (parentDirPath == "/") "/$name" else "$parentDirPath$name"

                    if (namespace != 2 || outList.none { it.name.equals(name, ignoreCase = true) }) {
                        outList.add(
                            FsEntry(
                                name = name,
                                fullPath = full,
                                sizeBytes = if (isDir) 0L else maxOf(0L, realSize),
                                isDirectory = isDir,
                                modifiedTimestamp = maxOf(0L, epochMs)
                            )
                        )
                    }
                }
            }

            pos += entryLength
        }
    }

    private data class InternalNode(
        val name: String,
        val mftIndex: Long,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val modifiedTimestamp: Long
    )

    private suspend fun resolveDirectoryNode(path: String): InternalNode? {
        val trimmed = path.trim().trimStart('/').trimEnd('/')
        if (trimmed.isEmpty()) {
            return InternalNode("/", 5L, true, 0L, 0L)
        }

        var currentNode = InternalNode("/", 5L, true, 0L, 0L)
        val segments = trimmed.split('/')
        for (seg in segments) {
            if (seg.isEmpty() || seg == ".") continue
            val entries = listInternalEntries(currentNode.mftIndex)
            val match = entries.firstOrNull { it.name.equals(seg, ignoreCase = true) } ?: return null
            currentNode = match
        }
        return if (currentNode.isDirectory) currentNode else null
    }

    private suspend fun resolveFileNode(path: String): InternalNode? {
        val trimmed = path.trim().trimStart('/')
        val lastSlash = trimmed.lastIndexOf('/')
        val dirPath = if (lastSlash >= 0) trimmed.substring(0, lastSlash) else ""
        val fileName = if (lastSlash >= 0) trimmed.substring(lastSlash + 1) else trimmed

        val dirNode = resolveDirectoryNode(dirPath) ?: return null
        val entries = listInternalEntries(dirNode.mftIndex)
        return entries.firstOrNull { it.name.equals(fileName, ignoreCase = true) && !it.isDirectory }
    }

    private suspend fun listInternalEntries(dirMftIndex: Long): List<InternalNode> {
        val recordBuf = readMftRecord(dirMftIndex) ?: return emptyList()
        val results = mutableListOf<InternalNode>()

        val indexRoot = findAttribute(recordBuf, 0x90)
        if (indexRoot != null && indexRoot.nonResident == 0.toByte()) {
            val valOffset = indexRoot.valueOffset
            val valLength = indexRoot.valueLength
            if (valLength >= 32) {
                recordBuf.position(valOffset)
                val nodeHeaderOffset = valOffset + 16
                val entriesOffset = recordBuf.getInt(nodeHeaderOffset)
                val totalEntriesSize = recordBuf.getInt(nodeHeaderOffset + 4)
                val startPos = nodeHeaderOffset + entriesOffset
                val endPos = minOf(recordBuf.capacity(), nodeHeaderOffset + totalEntriesSize)

                var pos = startPos
                while (pos + 16 <= endPos) {
                    val mftRef = recordBuf.getLong(pos) and 0x0000FFFFFFFFFFFFL
                    val entryLength = recordBuf.getShort(pos + 8).toInt() and 0xFFFF
                    val contentLength = recordBuf.getShort(pos + 10).toInt() and 0xFFFF
                    val flags = recordBuf.getShort(pos + 12).toInt() and 0xFFFF
                    if (entryLength <= 0 || (flags and 0x02) != 0) break

                    if (contentLength >= 66 && pos + 16 + contentLength <= endPos) {
                        val fnOffset = pos + 16
                        val modTime = recordBuf.getLong(fnOffset + 16)
                        val realSize = recordBuf.getLong(fnOffset + 56)
                        val fileFlags = recordBuf.getInt(fnOffset + 64)
                        val isDir = (fileFlags and 0x10000000) != 0 || (fileFlags and 0x10) != 0
                        val fnLen = recordBuf.get(fnOffset + 66).toInt() and 0xFF
                        if (fnLen in 1..255 && fnOffset + 66 + (fnLen * 2) <= endPos) {
                            val nameChars = CharArray(fnLen)
                            for (c in 0 until fnLen) {
                                nameChars[c] = recordBuf.getChar(fnOffset + 66 + (c * 2))
                            }
                            val name = String(nameChars)
                            val epochMs = (modTime - 116444736000000000L) / 10000L
                            results.add(InternalNode(name, mftRef, isDir, maxOf(0L, realSize), maxOf(0L, epochMs)))
                        }
                    }
                    pos += entryLength
                }
            }
        }

        return results
    }

    private suspend fun readMftRecord(recordIndex: Long): ByteBuffer? {
        val dev = blockDevice ?: return null
        val buf = readMftRecordRaw(recordIndex) ?: return null
        applyNtfsFixup(buf)
        return buf
    }

    private suspend fun readMftRecordRaw(recordIndex: Long): ByteBuffer? {
        val dev = blockDevice ?: return null
        val recordByteOffset = recordIndex * mftRecordSizeBytes

        var lba: Long? = null
        var currentOffset = 0L
        for (ext in mftExtents) {
            val extBytes = ext.clusterCount * clusterSizeBytes
            if (recordByteOffset in currentOffset until (currentOffset + extBytes)) {
                val offsetInExt = recordByteOffset - currentOffset
                lba = ext.physicalStartLba + (offsetInExt / sectorSize)
                break
            }
            currentOffset += extBytes
        }

        if (lba == null) {
            lba = partitionStartLba + (mftStartCluster * sectorsPerCluster) + (recordByteOffset / sectorSize)
        }

        val sectorsToRead = maxOf(1, mftRecordSizeBytes / sectorSize)
        val buf = ByteBuffer.allocate(mftRecordSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        dev.readSectors(lba, sectorsToRead, buf)
        buf.position(0)

        if (buf.remaining() >= 4 && buf.getInt(0) == 0x454C4946) {
            return buf
        }
        return null
    }

    private fun applyNtfsFixup(buf: ByteBuffer) {
        val origPos = buf.position()
        try {
            val fixupOffset = buf.getShort(4).toInt() and 0xFFFF
            val fixupCount = buf.getShort(6).toInt() and 0xFFFF
            if (fixupOffset + (fixupCount * 2) > buf.capacity()) return

            val signature = buf.getShort(fixupOffset)
            for (i in 1 until fixupCount) {
                val sectorEndPos = (i * 512) - 2
                if (sectorEndPos + 2 <= buf.capacity()) {
                    val replacement = buf.getShort(fixupOffset + (i * 2))
                    buf.putShort(sectorEndPos, replacement)
                }
            }
        } catch (_: Exception) {
        } finally {
            buf.position(origPos)
        }
    }

    private data class AttrHeader(
        val type: Int,
        val length: Int,
        val nonResident: Byte,
        val valueLength: Int,
        val valueOffset: Int,
        val dataRunsOffset: Int
    )

    private fun findAttribute(buf: ByteBuffer, targetType: Int): AttrHeader? {
        val firstAttrOffset = buf.getShort(20).toInt() and 0xFFFF
        var offset = firstAttrOffset

        while (offset + 16 <= buf.capacity()) {
            val attrType = buf.getInt(offset)
            if (attrType == -1 || attrType == 0xFFFFFFFF.toInt()) break
            val attrLength = buf.getInt(offset + 4)
            if (attrLength <= 0 || offset + attrLength > buf.capacity()) break

            val nonResident = buf.get(offset + 8)
            if (attrType == targetType) {
                val valLen = if (nonResident == 0.toByte()) buf.getInt(offset + 16) else 0
                val valOff = if (nonResident == 0.toByte()) offset + (buf.getShort(offset + 20).toInt() and 0xFFFF) else 0
                val runsOff = if (nonResident == 1.toByte()) offset + (buf.getShort(offset + 32).toInt() and 0xFFFF) else 0

                return AttrHeader(attrType, attrLength, nonResident, valLen, valOff, runsOff)
            }

            offset += attrLength
        }
        return null
    }

    private fun extractDataRuns(buf: ByteBuffer, targetType: Int): List<ClusterExtent> {
        val attr = findAttribute(buf, targetType) ?: return emptyList()
        if (attr.nonResident != 1.toByte()) return emptyList()
        return parseDataRuns(buf, attr.dataRunsOffset)
    }

    private fun parseDataRuns(buf: ByteBuffer, startOffset: Int): List<ClusterExtent> {
        val extents = mutableListOf<ClusterExtent>()
        var offset = startOffset
        var previousLcn = 0L

        while (offset < buf.capacity()) {
            val header = buf.get(offset).toInt() and 0xFF
            if (header == 0) break
            offset++

            val lenBytes = header and 0x0F
            val offBytes = (header shr 4) and 0x0F

            if (lenBytes == 0 || offset + lenBytes + offBytes > buf.capacity()) break

            var runLength = 0L
            for (i in 0 until lenBytes) {
                val b = buf.get(offset + i).toLong() and 0xFFL
                runLength = runLength or (b shl (i * 8))
            }
            offset += lenBytes

            var runOffsetDelta = 0L
            if (offBytes > 0) {
                for (i in 0 until offBytes) {
                    val b = buf.get(offset + i).toLong() and 0xFFL
                    runOffsetDelta = runOffsetDelta or (b shl (i * 8))
                }
                val signBit = 1L shl (offBytes * 8 - 1)
                if ((runOffsetDelta and signBit) != 0L) {
                    val mask = (-1L) shl (offBytes * 8)
                    runOffsetDelta = runOffsetDelta or mask
                }
                offset += offBytes
            }

            val currentLcn = previousLcn + runOffsetDelta
            previousLcn = currentLcn

            val extentLba = partitionStartLba + (currentLcn * sectorsPerCluster)
            extents.add(ClusterExtent(currentLcn, runLength, extentLba))
        }

        return extents
    }

    private fun extractVolumeLabel(buf: ByteBuffer): String? {
        val attr = findAttribute(buf, 0x60) ?: return null
        if (attr.nonResident == 0.toByte() && attr.valueLength > 0 && attr.valueOffset + attr.valueLength <= buf.capacity()) {
            val charCount = attr.valueLength / 2
            val chars = CharArray(charCount)
            for (i in 0 until charCount) {
                chars[i] = buf.getChar(attr.valueOffset + (i * 2))
            }
            return String(chars).trim()
        }
        return null
    }
}
