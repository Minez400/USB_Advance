package org.usbadvance.core.partition.gpt

import org.usbadvance.core.partition.align.PartitionAligner
import org.usbadvance.core.partition.mbr.MbrEngine
import org.usbadvance.core.partition.mbr.MbrPartitionRecord
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Engine for reading, validating, and writing GUID Partition Tables (GPT)
 * strictly compliant with UEFI Specification 2.10.
 */
class GptEngine {

    companion object {
        const val GPT_SIGNATURE = 0x5452415020494645L // "EFI PART" in Little Endian
        const val GPT_REVISION = 0x00010000 // 1.0
        const val HEADER_SIZE = 92
        const val PARTITION_ENTRY_SIZE = 128
        const val PARTITION_ENTRY_COUNT = 128
        const val PARTITION_ENTRIES_SECTORS = (PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE) / 512 // 32 sectors for 512B
    }

    /**
     * Writes a standard GPT layout with a single 1 MiB-aligned partition (LBA 2048).
     * Includes:
     * - Protective MBR (LBA 0) with 0xEE partition
     * - Primary GPT Header (LBA 1)
     * - Primary Partition Entry Array (LBAs 2-33 for 512B sectors)
     * - Secondary / Backup Partition Entry Array at end of drive
     * - Secondary / Backup GPT Header at the last addressable LBA (totalSectors - 1)
     */
    suspend fun writeSinglePartitionGpt(
        blockDevice: IBlockDevice,
        fsType: FilesystemType,
        partitionName: String = "USB_DATA"
    ): GptPartitionEntry {
        val sectorSize = blockDevice.sectorSize
        require(sectorSize == 512 || sectorSize == 4096) { "Supported sector sizes: 512 or 4096 bytes" }
        val totalSectors = blockDevice.totalSectors

        val firstUsableLba = PartitionAligner.getFirstAlignedLba(sectorSize) // LBA 2048 (1 MiB alignment)
        val entriesSectors = (PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE) / sectorSize
        val lastUsableLba = totalSectors - entriesSectors - 2 // Reserve space for secondary entries and header

        require(lastUsableLba > firstUsableLba) {
            "Insufficient drive capacity for GPT partition table layout. Total sectors: $totalSectors"
        }

        val diskGuid = UUID.randomUUID()
        val partitionGuid = UUID.randomUUID()
        val typeGuid = when (fsType) {
            FilesystemType.EXT4 -> GptPartitionEntry.LINUX_DATA_GUID
            else -> GptPartitionEntry.BASIC_DATA_GUID
        }

        val entry = GptPartitionEntry(
            typeGuid = typeGuid,
            uniqueGuid = partitionGuid,
            startingLba = firstUsableLba,
            endingLba = lastUsableLba,
            attributes = 0L,
            name = partitionName
        )

        // 1. Write Protective MBR at LBA 0 to prevent legacy partition utilities from corrupting GPT
        writeProtectiveMbr(blockDevice, totalSectors)

        // 2. Prepare the 128-entry partition array (16 KB total)
        val entriesBuffer = ByteBuffer.allocateDirect(PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE)
        entriesBuffer.order(ByteOrder.LITTLE_ENDIAN)
        entry.writeTo(entriesBuffer)
        // Remaining 127 partition slots are zero-filled
        while (entriesBuffer.hasRemaining()) {
            entriesBuffer.put(0.toByte())
        }
        entriesBuffer.flip()

        // Calculate CRC32 of the entire partition entries array
        val crcCalculator = CRC32()
        val entriesBytes = ByteArray(PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE)
        entriesBuffer.get(entriesBytes)
        crcCalculator.update(entriesBytes)
        val entriesCrc32 = crcCalculator.value.toInt()
        entriesBuffer.flip()

        // 3. Write Primary GPT Header at LBA 1
        val primaryHeaderBuffer = createGptHeader(
            currentLba = 1L,
            backupLba = totalSectors - 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            entriesLba = 2L,
            entriesCrc32 = entriesCrc32,
            sectorSize = sectorSize
        )
        blockDevice.writeSectors(1L, 1, primaryHeaderBuffer)

        // 4. Write Primary Partition Entries (LBAs 2 to 33 for 512-byte sectors)
        blockDevice.writeSectors(2L, entriesSectors, entriesBuffer)

        // 5. Write Secondary / Backup Partition Entries near end of drive
        val backupEntriesLba = totalSectors - entriesSectors - 1L
        entriesBuffer.rewind()
        blockDevice.writeSectors(backupEntriesLba, entriesSectors, entriesBuffer)

        // 6. Write Secondary GPT Header at the last addressable sector (totalSectors - 1)
        val backupHeaderBuffer = createGptHeader(
            currentLba = totalSectors - 1L,
            backupLba = 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            entriesLba = backupEntriesLba,
            entriesCrc32 = entriesCrc32,
            sectorSize = sectorSize
        )
        blockDevice.writeSectors(totalSectors - 1L, 1, backupHeaderBuffer)

        blockDevice.sync()
        return entry
    }

    /**
     * Writes Protective MBR at LBA 0 with a single 0xEE partition covering the disk.
     */
    private suspend fun writeProtectiveMbr(blockDevice: IBlockDevice, totalSectors: Long) {
        val buffer = ByteBuffer.allocateDirect(blockDevice.sectorSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Clear sector with zeros
        while (buffer.hasRemaining()) buffer.put(0.toByte())
        buffer.clear()

        // Move to offset 446 (first MBR partition entry slot)
        buffer.position(MbrEngine.PARTITION_TABLE_OFFSET)
        val protectiveSize = if (totalSectors - 1 > 0xFFFFFFFFL) 0xFFFFFFFFL else (totalSectors - 1)
        val record = MbrPartitionRecord(
            bootable = false,
            typeByte = 0xEE.toByte(), // GPT Protective Partition Type
            startLba = 1L,
            sectorCount = protectiveSize
        )
        record.writeTo(buffer)

        // Write standard 0x55AA boot signature at offset 510
        buffer.position(MbrEngine.SIGNATURE_OFFSET)
        buffer.putShort(MbrEngine.MBR_SIGNATURE)

        buffer.flip()
        blockDevice.writeSectors(0L, 1, buffer)
    }

    private fun createGptHeader(
        currentLba: Long,
        backupLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: UUID,
        entriesLba: Long,
        entriesCrc32: Int,
        sectorSize: Int
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(sectorSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.hasRemaining()) buffer.put(0.toByte())
        buffer.clear()

        buffer.putLong(GPT_SIGNATURE)
        buffer.putInt(GPT_REVISION)
        buffer.putInt(HEADER_SIZE)
        buffer.putInt(0) // Header CRC32 (temporarily 0 for checksum calculation)
        buffer.putInt(0) // Reserved
        buffer.putLong(currentLba)
        buffer.putLong(backupLba)
        buffer.putLong(firstUsableLba)
        buffer.putLong(lastUsableLba)

        // Write Disk GUID in mixed-endian format per UEFI specification
        writeUuid(buffer, diskGuid)

        buffer.putLong(entriesLba)
        buffer.putInt(PARTITION_ENTRY_COUNT)
        buffer.putInt(PARTITION_ENTRY_SIZE)
        buffer.putInt(entriesCrc32)

        // Calculate CRC32 of the 92-byte header
        val headerBytes = ByteArray(HEADER_SIZE)
        buffer.position(0)
        buffer.get(headerBytes)
        val crc = CRC32()
        crc.update(headerBytes)
        val headerCrc32 = crc.value.toInt()

        // Write computed CRC32 at offset 16
        buffer.position(16)
        buffer.putInt(headerCrc32)

        buffer.clear()
        return buffer
    }

    private fun writeUuid(buffer: ByteBuffer, uuid: UUID) {
        val mostSig = uuid.mostSignificantBits
        val timeLow = (mostSig ushr 32).toInt()
        val timeMid = (mostSig ushr 16).toShort()
        val timeHiAndVersion = mostSig.toShort()

        buffer.putInt(timeLow)
        buffer.putShort(timeMid)
        buffer.putShort(timeHiAndVersion)

        val leastSig = uuid.leastSignificantBits
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(leastSig)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }
}
