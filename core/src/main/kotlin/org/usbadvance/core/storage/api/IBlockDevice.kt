package org.usbadvance.core.storage.api

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * Low-level abstraction for a direct block storage device addressed via LBA (Logical Block Addressing).
 * Can be implemented over userspace USB Mass Storage (SCSI over Bulk-Only Transport) or
 * kernel block device nodes (/dev/block/sdX) when running with root privileges.
 */
interface IBlockDevice : Closeable {
    /**
     * Size in bytes of each physical or logical sector (typically 512 or 4096 bytes).
     */
    val sectorSize: Int

    /**
     * Total count of logical block addresses (LBAs) available on the storage medium.
     */
    val totalSectors: Long

    /**
     * Total physical media capacity in bytes (totalSectors * sectorSize).
     */
    val capacityBytes: Long get() = totalSectors * sectorSize

    /**
     * Reads [count] sectors starting from address [lba] into destination buffer [destination].
     * The destination buffer must have at least count * sectorSize remaining capacity.
     */
    suspend fun readSectors(lba: Long, count: Int, destination: ByteBuffer)

    /**
     * Writes [count] sectors starting from address [lba] consuming data from [source].
     * The source buffer must have at least count * sectorSize remaining bytes.
     */
    suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer)

    /**
     * Clears or discards (TRIM / SCSI UNMAP) a contiguous range of sectors.
     */
    suspend fun eraseSectors(lba: Long, count: Int)

    /**
     * Flushes volatile drive caches (SCSI SYNCHRONIZE CACHE or kernel flush).
     * Ensures all previous write operations are physically committed to non-volatile Flash NAND.
     */
    suspend fun sync()

    /**
     * Checks if the device has a physical switch or flag indicating write-protection.
     */
    suspend fun isWriteProtected(): Boolean

    /**
     * Flushes caches and spins down / prepares the storage unit for safe removal.
     */
    suspend fun eject(): Boolean = false
}
