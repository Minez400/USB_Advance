package org.usbadvance.core.vfs.sniffer

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Non-destructive sniffer that detects filesystem formats by inspecting magic bytes,
 * Boot Records, and Superblocks at the partition level.
 * Hardened with defensive bounds checking and error handling against damaged sectors.
 */
class FilesystemSniffer {

    data class SniffResult(
        val type: FilesystemType,
        val volumeLabel: String? = null,
        val clusterSizeBytes: Int = 4096,
        val totalSectors: Long = 0L
    )

    suspend fun detectFilesystem(
        blockDevice: IBlockDevice,
        startLba: Long,
        sectorCount: Long
    ): SniffResult? {
        if (sectorCount <= 0 || startLba < 0 || startLba >= blockDevice.totalSectors) return null

        return try {
            val sectorSize = blockDevice.sectorSize
            val buffer = ByteBuffer.allocateDirect(sectorSize).order(ByteOrder.LITTLE_ENDIAN)

            // Read Sector 0 of partition
            blockDevice.readSectors(startLba, 1, buffer)
            buffer.flip()

            // 1. Check exFAT (Magic "EXFAT   " at offset 3)
            val exFatSignature = readAscii(buffer, 3, 8)
            if (exFatSignature.startsWith("EXFAT")) {
                val sectorShift = buffer.get(108).toInt() and 0xFF
                val clusterShift = buffer.get(109).toInt() and 0xFF
                val clusterSize = if (sectorShift in 9..12 && clusterShift in 0..25) {
                    (1 shl sectorShift) * (1 shl clusterShift)
                } else {
                    32768
                }
                buffer.position(72)
                val volumeLengthSectors = buffer.long

                // Attempt to read Volume Label from exFAT Root Directory (Entry Type 0x83)
                var volumeLabel: String? = null
                try {
                    buffer.position(88)
                    val clusterHeapOffset = buffer.int.toLong() and 0xFFFFFFFFL
                    buffer.position(96)
                    val firstClusterOfRoot = buffer.int.toLong() and 0xFFFFFFFFL
                    val sectorsPerCluster = 1L shl clusterShift

                    if (clusterHeapOffset > 0 && firstClusterOfRoot >= 2 && sectorCount > clusterHeapOffset) {
                        val rootLba = startLba + clusterHeapOffset + (firstClusterOfRoot - 2) * sectorsPerCluster
                        if (rootLba < blockDevice.totalSectors) {
                            val rootBuf = ByteBuffer.allocateDirect(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
                            blockDevice.readSectors(rootLba, 1, rootBuf)
                            rootBuf.flip()

                            for (entryOffset in 0 until (sectorSize - 32) step 32) {
                                val entryType = rootBuf.get(entryOffset).toInt() and 0xFF
                                if (entryType == 0x00) break
                                if (entryType == 0x83) { // Volume Label Entry
                                    val charCount = rootBuf.get(entryOffset + 1).toInt() and 0xFF
                                    if (charCount in 1..11) {
                                        val labelChars = CharArray(charCount)
                                        for (c in 0 until charCount) {
                                            labelChars[c] = rootBuf.getChar(entryOffset + 2 + (c * 2))
                                        }
                                        volumeLabel = String(labelChars).trim()
                                    }
                                    break
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                return SniffResult(
                    type = FilesystemType.EXFAT,
                    volumeLabel = volumeLabel,
                    clusterSizeBytes = clusterSize,
                    totalSectors = if (volumeLengthSectors in 1..sectorCount) volumeLengthSectors else sectorCount
                )
            }

            // 2. Check NTFS (Magic "NTFS    " at offset 3)
            val ntfsSignature = readAscii(buffer, 3, 8)
            if (ntfsSignature.startsWith("NTFS")) {
                val bps = buffer.getShort(11).toInt() and 0xFFFF
                val spc = buffer.get(13).toInt() and 0xFF
                val clusterSize = if (bps in setOf(512, 1024, 2048, 4096) && spc > 0) bps * spc else 4096
                buffer.position(40)
                val totalNtfsSectors = buffer.long
                return SniffResult(
                    type = FilesystemType.NTFS,
                    volumeLabel = null,
                    clusterSizeBytes = clusterSize,
                    totalSectors = if (totalNtfsSectors in 1..sectorCount) totalNtfsSectors else sectorCount
                )
            }

            // 3. Check FAT32 (Magic "FAT32   " at offset 82 or valid BPB geometry)
            val fat32Signature = readAscii(buffer, 82, 8)
            if (fat32Signature.startsWith("FAT32")) {
                val bps = buffer.getShort(11).toInt() and 0xFFFF
                val spc = buffer.get(13).toInt() and 0xFF
                val clusterSize = if (bps in setOf(512, 1024, 2048, 4096) && spc > 0) bps * spc else 4096
                val label = readAscii(buffer, 71, 11).trim()
                val totalSec = buffer.getInt(32).toLong() and 0xFFFFFFFFL
                return SniffResult(
                    type = FilesystemType.FAT32,
                    volumeLabel = label.ifEmpty { null },
                    clusterSizeBytes = clusterSize,
                    totalSectors = if (totalSec in 1..sectorCount) totalSec else sectorCount
                )
            }

            // 4. Check FAT16 (Magic "FAT16   " or "FAT12   " at offset 54)
            val fat16Signature = readAscii(buffer, 54, 8)
            if (fat16Signature.startsWith("FAT16") || fat16Signature.startsWith("FAT12")) {
                val bps = buffer.getShort(11).toInt() and 0xFFFF
                val spc = buffer.get(13).toInt() and 0xFF
                val clusterSize = if (bps in setOf(512, 1024, 2048, 4096) && spc > 0) bps * spc else 2048
                val label = readAscii(buffer, 43, 11).trim()
                val totalSec16 = buffer.getShort(19).toInt() and 0xFFFF
                val totalSec = if (totalSec16 > 0) totalSec16.toLong() else buffer.getInt(32).toLong() and 0xFFFFFFFFL
                return SniffResult(
                    type = FilesystemType.FAT16,
                    volumeLabel = label.ifEmpty { null },
                    clusterSizeBytes = clusterSize,
                    totalSectors = if (totalSec in 1..sectorCount) totalSec else sectorCount
                )
            }

            // 5. Check ext2/3/4 (Superblock located at offset 1024 from partition start)
            val extLbaOffset = 1024L / sectorSize
            if (sectorCount > extLbaOffset && (startLba + extLbaOffset) < blockDevice.totalSectors) {
                val extBuffer = ByteBuffer.allocateDirect(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
                blockDevice.readSectors(startLba + extLbaOffset, 1, extBuffer)
                extBuffer.flip()

                val offsetInSector = (1024 % sectorSize).toInt()
                if (extBuffer.remaining() >= offsetInSector + 128) {
                    extBuffer.position(offsetInSector + 56)
                    val extMagic = extBuffer.short.toInt() and 0xFFFF
                    if (extMagic == 0xEF53) {
                        extBuffer.position(offsetInSector + 24)
                        val logBlockSize = extBuffer.int
                        val blockSize = 1024 shl logBlockSize
                        val volumeName = readAscii(extBuffer, offsetInSector + 120, 16).trim()
                        extBuffer.position(offsetInSector + 4)
                        val blocksCount = extBuffer.int.toLong() and 0xFFFFFFFFL
                        val totalSec = (blocksCount * blockSize) / sectorSize
                        return SniffResult(
                            type = FilesystemType.EXT4,
                            volumeLabel = volumeName.ifEmpty { null },
                            clusterSizeBytes = blockSize,
                            totalSectors = if (totalSec in 1..sectorCount) totalSec else sectorCount
                        )
                    }
                }
            }

            // 6. Generic FAT fallback verification
            if (buffer.limit() >= 512) {
                val bootSig = buffer.getShort(510).toInt() and 0xFFFF
                if (bootSig == 0xAA55) {
                    val bps = buffer.getShort(11).toInt() and 0xFFFF
                    val spc = buffer.get(13).toInt() and 0xFF
                    val reserved = buffer.getShort(14).toInt() and 0xFFFF
                    val fats = buffer.get(16).toInt() and 0xFF
                    if (bps in setOf(512, 1024, 2048, 4096) && (spc and (spc - 1)) == 0 && spc > 0 && reserved > 0 && fats in 1..2) {
                        val rootEntries = buffer.getShort(17).toInt() and 0xFFFF
                        val totalSec16 = buffer.getShort(19).toInt() and 0xFFFF
                        val totalSec32 = buffer.getInt(32).toLong() and 0xFFFFFFFFL
                        val isFat32 = rootEntries == 0 && totalSec16 == 0 && totalSec32 > 0
                        return SniffResult(
                            type = if (isFat32) FilesystemType.FAT32 else FilesystemType.FAT16,
                            volumeLabel = null,
                            clusterSizeBytes = bps * spc,
                            totalSectors = sectorCount
                        )
                    }
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readAscii(buffer: ByteBuffer, offset: Int, length: Int): String {
        if (buffer.limit() < offset + length) return ""
        val bytes = ByteArray(length)
        val originalPos = buffer.position()
        buffer.position(offset)
        buffer.get(bytes)
        buffer.position(originalPos)
        return String(bytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
    }
}
