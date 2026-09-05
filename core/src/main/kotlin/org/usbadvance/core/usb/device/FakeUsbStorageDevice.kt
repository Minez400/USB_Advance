package org.usbadvance.core.usb.device

import org.usbadvance.core.storage.api.GenericStorageDevice
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.storage.api.StorageBusType
import org.usbadvance.core.storage.model.DiskGeometry
import org.usbadvance.core.storage.model.PartitionTableType
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory sparse block device implementation for Developer Mode testing.
 * Simulates sector I/O operations for a 64 GB USB drive without requiring physical hardware.
 */
class VirtualSparseBlockDevice(
    override val sectorSize: Int = 512,
    override val totalSectors: Long = 125829120L // ~64.4 GB
) : IBlockDevice {

    private val sectorMap = ConcurrentHashMap<Long, ByteArray>()

    override suspend fun readSectors(lba: Long, count: Int, destination: ByteBuffer) {
        val blankSector = ByteArray(sectorSize)
        for (i in 0 until count) {
            val currentLba = lba + i
            val sectorData = sectorMap[currentLba] ?: blankSector
            destination.put(sectorData)
        }
    }

    override suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer) {
        for (i in 0 until count) {
            val currentLba = lba + i
            val sectorData = ByteArray(sectorSize)
            source.get(sectorData)
            sectorMap[currentLba] = sectorData
        }
    }

    override suspend fun eraseSectors(lba: Long, count: Int) {
        for (i in 0 until count) {
            val currentLba = lba + i
            sectorMap.remove(currentLba)
        }
    }

    override suspend fun sync() {
        // Instant in-memory sync
    }

    override suspend fun isWriteProtected(): Boolean = false

    override suspend fun eject(): Boolean = true

    override fun close() {
        // Sector map retained for session reset or clear
    }
}

object FakeUsbStorageDeviceFactory {

    const val FAKE_DEVICE_ID = "DEV_MODE_MOCK_USB_64GB"

    fun create(): IStorageDevice {
        val virtualBlockDevice = VirtualSparseBlockDevice()
        return GenericStorageDevice(
            id = FAKE_DEVICE_ID,
            name = "Pendrive Virtual 64 GB [Dev Mode]",
            vendor = "USB Advance Dev",
            product = "Virtual Flash Drive 3.0",
            revision = "1.00",
            serialNumber = "DEV-MOCK-64GB-01",
            busType = StorageBusType.VIRTUAL,
            geometry = DiskGeometry(
                sectorSize = virtualBlockDevice.sectorSize,
                totalSectors = virtualBlockDevice.totalSectors
            ),
            partitionTableType = PartitionTableType.MBR,
            partitions = emptyList(),
            isRemovable = true,
            isWriteProtected = false,
            blockDeviceProvider = { virtualBlockDevice }
        )
    }
}
