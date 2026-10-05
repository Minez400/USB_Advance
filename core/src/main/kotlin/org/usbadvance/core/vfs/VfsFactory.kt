package org.usbadvance.core.vfs

import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.cache.CachedBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import org.usbadvance.core.vfs.exfat.ExFatFileSystem
import org.usbadvance.core.vfs.fat32.Fat32FileSystem
import org.usbadvance.core.vfs.ntfs.NtfsFileSystem
import org.usbadvance.core.vfs.sniffer.FilesystemSniffer

/**
 * Intelligent VFS Factory that inspects partition boot sectors
 * and mounts the optimal filesystem driver (exFAT C++20, FAT32/VFAT, or NTFS).
 */
object VfsFactory {

    /**
     * Inspects and mounts a virtual filesystem on [blockDevice] starting at [startLba].
     * Automatically enables LRU block caching for USB OTG acceleration.
     */
    suspend fun mount(
        blockDevice: IBlockDevice,
        startLba: Long,
        sectorCount: Long,
        enableSectorCache: Boolean = true
    ): Result<IVirtualFileSystem> {
        val effectiveDevice = if (enableSectorCache && blockDevice !is CachedBlockDevice) {
            CachedBlockDevice(blockDevice, maxCachedSectors = 4096)
        } else {
            blockDevice
        }

        // Sniff filesystem magic signature at partition offset
        val sniffer = FilesystemSniffer()
        val sniffResult = sniffer.detectFilesystem(effectiveDevice, startLba, sectorCount)
        val detected = sniffResult?.type

        val driversToTry = when (detected) {
            FilesystemType.EXFAT -> listOf(
                { ExFatFileSystem() },
                { Fat32FileSystem() },
                { NtfsFileSystem() }
            )
            FilesystemType.FAT32, FilesystemType.FAT16 -> listOf(
                { Fat32FileSystem() },
                { ExFatFileSystem() },
                { NtfsFileSystem() }
            )
            FilesystemType.NTFS -> listOf(
                { NtfsFileSystem() },
                { ExFatFileSystem() },
                { Fat32FileSystem() }
            )
            else -> listOf(
                { ExFatFileSystem() },
                { Fat32FileSystem() },
                { NtfsFileSystem() }
            )
        }

        for (driverFactory in driversToTry) {
            try {
                val driver = driverFactory()
                if (driver.mount(effectiveDevice, startLba, sectorCount)) {
                    return Result.success(driver)
                }
            } catch (_: Throwable) {
            }
        }

        return Result.failure(IllegalStateException("Não foi possível montar a partição (tipo detectado: ${detected ?: "desconhecido"}) em LBA $startLba."))
    }
}
