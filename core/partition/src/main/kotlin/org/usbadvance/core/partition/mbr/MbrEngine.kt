package org.usbadvance.core.partition.mbr

import org.usbadvance.core.partition.align.PartitionAligner
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * Motor de leitura, validação e gravação de tabelas de partição MBR (Master Boot Record).
 */
class MbrEngine {

    companion object {
        const val MBR_SIGNATURE: Short = 0xAA55.toShort()
        const val PARTITION_TABLE_OFFSET = 446
        const val DISK_SIGNATURE_OFFSET = 440
        const val SIGNATURE_OFFSET = 510
    }

    /**
     * Lê e decodifica as partições MBR presentes no LBA 0 do dispositivo.
     */
    suspend fun readPartitions(blockDevice: IBlockDevice): List<MbrPartitionRecord> {
        val buffer = ByteBuffer.allocateDirect(blockDevice.sectorSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        blockDevice.readSectors(0L, 1, buffer)
        buffer.flip()

        // Verifica a assinatura final 0x55 0xAA
        buffer.position(SIGNATURE_OFFSET)
        val signature = buffer.short
        if (signature != MBR_SIGNATURE) {
            return emptyList() // Disco não particionado em MBR ou corrompido
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
     * Grava uma nova tabela MBR contendo uma única partição primária ocupando todo o disco,
     * alinhada no LBA 2048 (1 MiB) para máxima performance em memórias Flash.
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
            "O disco é pequeno demais para particionamento MBR alinhado. Total: $totalSectors setores."
        }

        val rawSectorCount = totalSectors - startLba
        // MBR é limitado a endereçamento de 32 bits (máximo 0xFFFFFFFF setores = 2 TB em 512B)
        val sectorCount = if (rawSectorCount > 0xFFFFFFFFL) 0xFFFFFFFFL else rawSectorCount
        val partitionRecord = MbrPartitionRecord(
            bootable = bootable,
            typeByte = MbrPartitionRecord.getTypeByteFor(fsType),
            startLba = startLba,
            sectorCount = sectorCount
        )

        val buffer = ByteBuffer.allocateDirect(sectorSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Limpa todo o setor 0
        while (buffer.hasRemaining()) {
            buffer.put(0.toByte())
        }
        buffer.clear()

        // Gera Disk Signature pseudo-aleatória de 4 bytes no offset 440
        buffer.position(DISK_SIGNATURE_OFFSET)
        buffer.putInt(Random().nextInt())

        // Escreve a primeira partição primária no offset 446
        buffer.position(PARTITION_TABLE_OFFSET)
        partitionRecord.writeTo(buffer)

        // As entradas 2, 3 e 4 permanecem zeradas

        // Grava a assinatura 0x55 0xAA no offset 510
        buffer.position(SIGNATURE_OFFSET)
        buffer.putShort(MBR_SIGNATURE)

        buffer.flip()
        blockDevice.writeSectors(0L, 1, buffer)
        blockDevice.sync()

        return partitionRecord
    }

    /**
     * Apaga os primeiros setores do disco para remover tabelas anteriores.
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
