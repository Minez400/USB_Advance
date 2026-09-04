package org.usbadvance.core.storage.api

import org.usbadvance.core.storage.model.DiskGeometry
import org.usbadvance.core.storage.model.PartitionTableType

enum class StorageBusType {
    USB,
    SD_MMC,
    NVME,
    SATA,
    VIRTUAL
}

/**
 * High-level abstraction for an attached storage drive in the system.
 */
interface IStorageDevice {
    val id: String
    val name: String
    val vendor: String
    val product: String
    val revision: String
    val serialNumber: String
    val busType: StorageBusType
    val geometry: DiskGeometry
    val partitionTableType: PartitionTableType
    val partitions: List<IPartition>
    val isRemovable: Boolean
    val isWriteProtected: Boolean

    /**
     * Opens a low-level sector I/O session with the storage device.
     */
    suspend fun openBlockDevice(): IBlockDevice
}

data class GenericStorageDevice(
    override val id: String,
    override val name: String,
    override val vendor: String,
    override val product: String,
    override val revision: String,
    override val serialNumber: String,
    override val busType: StorageBusType,
    override val geometry: DiskGeometry,
    override val partitionTableType: PartitionTableType,
    override val partitions: List<IPartition>,
    override val isRemovable: Boolean = true,
    override val isWriteProtected: Boolean = false,
    private val blockDeviceProvider: (suspend () -> IBlockDevice)? = null
) : IStorageDevice {
    override suspend fun openBlockDevice(): IBlockDevice {
        return blockDeviceProvider?.invoke()
            ?: throw UnsupportedOperationException("IBlockDevice provider not configured for $name")
    }
}
