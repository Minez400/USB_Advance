package org.usbadvance.core.storage.api

import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.PartitionTableType

/**
 * Abstract representation of a partition either existing on or targeted for a storage unit.
 */
interface IPartition {
    val index: Int
    val startLba: Long
    val sectorCount: Long
    val sizeBytes: Long
    val partitionTableType: PartitionTableType
    val typeGuid: String?
    val mbrType: Byte?
    val filesystem: FilesystemType?
    val label: String?
    val uuid: String?
    val isBootable: Boolean
}

data class GenericPartition(
    override val index: Int,
    override val startLba: Long,
    override val sectorCount: Long,
    override val sizeBytes: Long,
    override val partitionTableType: PartitionTableType,
    override val typeGuid: String? = null,
    override val mbrType: Byte? = null,
    override val filesystem: FilesystemType? = null,
    override val label: String? = null,
    override val uuid: String? = null,
    override val isBootable: Boolean = false
) : IPartition
