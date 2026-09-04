package org.usbadvance.core.partition

import org.usbadvance.core.partition.gpt.GptEngine
import org.usbadvance.core.partition.mbr.MbrEngine
import org.usbadvance.core.storage.api.GenericPartition
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.api.IPartition
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.PartitionTableType

/**
 * Gerenciador unificado de particionamento responsável por orquestrar
 * a criação, inspeção e exclusão de tabelas MBR e GPT.
 */
class PartitionManager(
    private val mbrEngine: MbrEngine = MbrEngine(),
    private val gptEngine: GptEngine = GptEngine()
) {
    /**
     * Aplica o particionamento solicitado criando uma partição principal que engloba o disco.
     */
    suspend fun createSinglePartition(
        blockDevice: IBlockDevice,
        tableType: PartitionTableType,
        fsType: FilesystemType,
        volumeLabel: String
    ): IPartition {
        return when (tableType) {
            PartitionTableType.MBR -> {
                val record = mbrEngine.writeSinglePartition(blockDevice, fsType)
                GenericPartition(
                    index = 1,
                    startLba = record.startLba,
                    sectorCount = record.sectorCount,
                    sizeBytes = record.sectorCount * blockDevice.sectorSize,
                    partitionTableType = PartitionTableType.MBR,
                    mbrType = record.typeByte,
                    filesystem = fsType,
                    label = volumeLabel,
                    isBootable = record.bootable
                )
            }
            PartitionTableType.GPT -> {
                val entry = gptEngine.writeSinglePartitionGpt(blockDevice, fsType, volumeLabel)
                val sectorCount = (entry.endingLba - entry.startingLba) + 1
                GenericPartition(
                    index = 1,
                    startLba = entry.startingLba,
                    sectorCount = sectorCount,
                    sizeBytes = sectorCount * blockDevice.sectorSize,
                    partitionTableType = PartitionTableType.GPT,
                    typeGuid = entry.typeGuid.toString(),
                    uuid = entry.uniqueGuid.toString(),
                    filesystem = fsType,
                    label = entry.name
                )
            }
            PartitionTableType.RAW_SUPERFLOPPY -> {
                // Sem tabela de partição: o filesystem começa no LBA 0
                GenericPartition(
                    index = 0,
                    startLba = 0L,
                    sectorCount = blockDevice.totalSectors,
                    sizeBytes = blockDevice.capacityBytes,
                    partitionTableType = PartitionTableType.RAW_SUPERFLOPPY,
                    filesystem = fsType,
                    label = volumeLabel
                )
            }
        }
    }
}
