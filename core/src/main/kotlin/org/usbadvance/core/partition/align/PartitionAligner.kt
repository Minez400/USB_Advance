package org.usbadvance.core.partition.align

/**
 * Partition alignment calculator optimized for NAND Flash memories (USB drives and SSDs).
 *
 * Flash devices organize storage in physical erase blocks ranging from 1 MiB to 4 MiB.
 * Starting a partition unaligned causes read-modify-write performance penalties and flash wear amplification.
 * This aligner guarantees every partition starts on an exact 1 MiB boundary (LBA 2048 for 512-byte sectors).
 */
object PartitionAligner {
    const val DEFAULT_ALIGNMENT_BYTES: Long = 1024 * 1024 // 1 MiB (1,048,576 bytes)

    /**
     * Calculates the first 1 MiB aligned LBA for the given sector size.
     * For 512-byte sectors: 1048576 / 512 = 2048 sectors.
     * For 4096-byte (4Kn) sectors: 1048576 / 4096 = 256 sectors.
     */
    fun getFirstAlignedLba(sectorSize: Int): Long {
        require(sectorSize > 0) { "Invalid sector size: $sectorSize" }
        return DEFAULT_ALIGNMENT_BYTES / sectorSize
    }

    /**
     * Aligns an arbitrary LBA upward to the nearest 1 MiB boundary.
     */
    fun alignUp(lba: Long, sectorSize: Int): Long {
        val sectorsPerMib = DEFAULT_ALIGNMENT_BYTES / sectorSize
        val remainder = lba % sectorsPerMib
        return if (remainder == 0L) lba else lba + (sectorsPerMib - remainder)
    }

    /**
     * Aligns an arbitrary LBA downward to the nearest 1 MiB boundary.
     */
    fun alignDown(lba: Long, sectorSize: Int): Long {
        val sectorsPerMib = DEFAULT_ALIGNMENT_BYTES / sectorSize
        return lba - (lba % sectorsPerMib)
    }
}
