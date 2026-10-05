package org.usbadvance.core.partition

import org.usbadvance.core.partition.gpt.GptEngine
import org.usbadvance.core.partition.mbr.MbrEngine
import org.usbadvance.core.storage.api.GenericPartition
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.PartitionTableType
import org.usbadvance.core.vfs.sniffer.FilesystemSniffer

/**
 * High-level partition scanner that inspects storage media on connection
 * and identifies all existing partitions and their underlying filesystem formats.
 * Hardened with protective partition filtering and bounds verification.
 */
class PartitionScanner(
    private val mbrEngine: MbrEngine = MbrEngine(),
    private val gptEngine: GptEngine = GptEngine(),
    private val fsSniffer: FilesystemSniffer = FilesystemSniffer()
) {
    suspend fun scanPartitions(blockDevice: IBlockDevice): List<IPartition> {
        val totalSectors = blockDevice.totalSectors
        if (totalSectors <= 0) return emptyList()

        val detectedPartitions = mutableListOf<IPartition>()

        return try {
            // 1. Check for Superfloppy / RAW unpartitioned media (filesystem starts directly at LBA 0)
            // If LBA 0 contains an explicit filesystem magic (exFAT, FAT32, NTFS, ext4),
            // it is definitively a raw/superfloppy volume, NOT a partitioned MBR disk.
            val superfloppySniff = fsSniffer.detectFilesystem(blockDevice, 0L, totalSectors)
            if (superfloppySniff != null) {
                return listOf(
                    GenericPartition(
                        index = 0,
                        startLba = 0L,
                        sectorCount = totalSectors,
                        sizeBytes = blockDevice.capacityBytes,
                        partitionTableType = PartitionTableType.RAW_SUPERFLOPPY,
                        filesystem = superfloppySniff.type,
                        label = superfloppySniff.volumeLabel
                    )
                )
            }

            // 2. Check MBR at LBA 0
            val mbrRecords = mbrEngine.readPartitions(blockDevice)
            val isGptProtective = mbrRecords.any { (it.typeByte.toInt() and 0xFF) == 0xEE }

            if (isGptProtective) {
                // 3. Drive uses GUID Partition Table (GPT)
                val gptEntries = gptEngine.readPartitions(blockDevice)
                for ((index, entry) in gptEntries.withIndex()) {
                    val startLba = entry.startingLba
                    if (startLba < 0 || startLba >= totalSectors || entry.endingLba < startLba) continue

                    val rawCount = (entry.endingLba - startLba) + 1
                    val sectorCount = minOf(rawCount, totalSectors - startLba)
                    val sniffResult = fsSniffer.detectFilesystem(blockDevice, startLba, sectorCount)

                    val fsType = sniffResult?.type ?: entry.toFilesystemType()
                    val label = sniffResult?.volumeLabel ?: entry.name.ifEmpty { null }

                    detectedPartitions.add(
                        GenericPartition(
                            index = index + 1,
                            startLba = startLba,
                            sectorCount = sectorCount,
                            sizeBytes = sectorCount * blockDevice.sectorSize,
                            partitionTableType = PartitionTableType.GPT,
                            typeGuid = entry.typeGuid.toString(),
                            uuid = entry.uniqueGuid.toString(),
                            filesystem = fsType,
                            label = label,
                            isBootable = false
                        )
                    )
                }
                if (detectedPartitions.isNotEmpty()) return detectedPartitions
            }

            // 4. Filter valid MBR records: ignore empty (0x00) and GPT protective (0xEE)
            val validMbrRecords = mbrRecords.filter {
                val type = it.typeByte.toInt() and 0xFF
                type != 0x00 && type != 0xEE && it.startLba in 0 until totalSectors && it.sectorCount > 0
            }

            if (validMbrRecords.isNotEmpty()) {
                for ((index, record) in validMbrRecords.withIndex()) {
                    val startLba = record.startLba
                    val sectorCount = minOf(record.sectorCount, totalSectors - startLba)
                    val sniffResult = fsSniffer.detectFilesystem(blockDevice, startLba, sectorCount)

                    val fsType = sniffResult?.type ?: record.toFilesystemType()
                    val label = sniffResult?.volumeLabel

                    detectedPartitions.add(
                        GenericPartition(
                            index = index + 1,
                            startLba = startLba,
                            sectorCount = sectorCount,
                            sizeBytes = sectorCount * blockDevice.sectorSize,
                            partitionTableType = PartitionTableType.MBR,
                            mbrType = record.typeByte,
                            filesystem = fsType,
                            label = label,
                            isBootable = record.bootable
                        )
                    )
                }
                if (detectedPartitions.isNotEmpty()) return detectedPartitions
            }

            detectedPartitions
        } catch (_: Exception) {
            emptyList()
        }
    }
}
