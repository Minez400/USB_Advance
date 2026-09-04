package org.usbadvance.core.storage.model

/**
 * Geometric and dimensional representation of a physical block storage unit.
 */
data class DiskGeometry(
    val sectorSize: Int, // Typically 512 or 4096 bytes
    val totalSectors: Long, // Total number of Logical Block Addresses (LBA)
    val capacityBytes: Long = totalSectors * sectorSize
) {
    init {
        require(sectorSize > 0 && (sectorSize and (sectorSize - 1)) == 0) {
            "Sector size must be a power of 2 (e.g. 512, 4096). Found: $sectorSize"
        }
        require(totalSectors >= 0) {
            "Total sectors cannot be negative. Found: $totalSectors"
        }
    }

    /**
     * Returns human-readable formatted capacity string (e.g., 32.00 GB, 447.13 GB).
     */
    fun getFormattedCapacity(): String {
        val bytes = capacityBytes
        if (bytes <= 0) return "Tap to Connect"
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format("%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }
}
