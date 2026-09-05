package org.usbadvance.core.partition.mbr

import org.usbadvance.core.partition.align.PartitionAligner
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * Engine for reading, validating, and writing Master Boot Record (MBR) partition tables.
 */
class MbrEngine {

    companion object {
        const val MBR_SIGNATURE: Short = 0xAA55.toShort()
        const val PARTITION_TABLE_OFFSET = 446
        const val DISK_SIGNATURE_OFFSET = 440
        const val SIGNATURE_OFFSET = 510
    }

    /**
     * Reads and decodes MBR partition records located at LBA 0 of the block device.
     */
    suspend fun readPartitions(blockDevice: IBlockDevice): List<MbrPartitionRecord> {
        val buffer = ByteBuffer.allocateDirect(blockDevice.sectorSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        blockDevice.readSectors(0L, 1, buffer)
        buffer.flip()

        // Validate standard 0x55AA boot record signature at offset 510
        buffer.position(SIGNATURE_OFFSET)
        val signature = buffer.short
        if (signature != MBR_SIGNATURE) {
            return emptyList() // Drive does not contain a valid MBR table
        }

        val partitions = mutableListOf<MbrPartitionRecord>()
        for (i in 0 until 4) {
            buffer.position(PARTITION_TABLE_OFFSET + (i * 16))
            val record = MbrPartitionRecord.parseFrom(buffer)
            if (record != null) {
                partitions.add(record)
            }
        }
        return partitions
    }

    /**
     * Writes a new MBR table containing a single primary partition spanning usable disk space,
     * aligned to LBA 2048 (1 MiB boundary) to maximize Flash NAND read/write efficiency.
     */
    suspend fun writeSinglePartition(
        blockDevice: IBlockDevice,
        fsType: FilesystemType,
        bootable: Boolean = false
    ): MbrPartitionRecord {
        val sectorSize = blockDevice.sectorSize
        val totalSectors = blockDevice.totalSectors

        val startLba = PartitionAligner.getFirstAlignedLba(sectorSize)
        require(totalSectors > startLba + 2048) {
            "Drive capacity is too small for aligned MBR partitioning. Total: $totalSectors sectors."
        }

        val rawSectorCount = totalSectors - startLba
        // MBR is constrained to 32-bit sector addressing (max 0xFFFFFFFF sectors = 2 TiB with 512B sectors)
        val sectorCount = if (rawSectorCount > 0xFFFFFFFFL) 0xFFFFFFFFL else rawSectorCount
        val partitionRecord = MbrPartitionRecord(
            bootable = bootable,
            typeByte = MbrPartitionRecord.getTypeByteFor(fsType),
            startLba = startLba,
            sectorCount = sectorCount
        )

        val buffer = ByteBuffer.allocateDirect(sectorSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Clear sector 0
        while (buffer.hasRemaining()) {
            buffer.put(0.toByte())
        }
        buffer.clear()

        // Generate pseudo-random 4-byte NT disk signature at offset 440
        buffer.position(DISK_SIGNATURE_OFFSET)
        buffer.putInt(Random().nextInt())

        // Write first primary partition record at offset 446
        buffer.position(PARTITION_TABLE_OFFSET)
        partitionRecord.writeTo(buffer)

        // Partition slots 2, 3, and 4 remain zeroed

        // Write 0x55AA boot signature at offset 510
        buffer.position(SIGNATURE_OFFSET)
        buffer.putShort(MBR_SIGNATURE)

        buffer.flip()
        blockDevice.writeSectors(0L, 1, buffer)
        blockDevice.sync()

        return partitionRecord
    }

    /**
     * Wipes LBA 0 to invalidate previous partition tables.
     */
    suspend fun wipeMbr(blockDevice: IBlockDevice) {
        val buffer = ByteBuffer.allocateDirect(blockDevice.sectorSize)
        while (buffer.hasRemaining()) {
            buffer.put(0.toByte())
        }
        buffer.flip()
        blockDevice.writeSectors(0L, 1, buffer)
        blockDevice.sync()
    }
}
