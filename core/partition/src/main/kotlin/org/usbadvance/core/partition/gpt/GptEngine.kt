package org.usbadvance.core.partition.gpt

import org.usbadvance.core.partition.align.PartitionAligner
import org.usbadvance.core.partition.mbr.MbrEngine
import org.usbadvance.core.partition.mbr.MbrPartitionRecord
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/**
 * Motor de leitura, validação e gravação de tabelas de partição GPT (GUID Partition Table)
 * em conformidade com as especificações UEFI 2.10.
 */
class GptEngine {

    companion object {
        const val GPT_SIGNATURE = 0x5452415020494645L // "EFI PART" em Little Endian
        const val GPT_REVISION = 0x00010000 // 1.0
        const val HEADER_SIZE = 92
        const val PARTITION_ENTRY_SIZE = 128
        const val PARTITION_ENTRY_COUNT = 128
        const val PARTITION_ENTRIES_SECTORS = (PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE) / 512 // 32 setores
    }

    /**
     * Grava uma tabela GPT moderna com uma única partição alinhada a 1 MiB (LBA 2048),
     * incluindo MBR de proteção (LBA 0), GPT Primário (LBAs 1-33) e GPT Secundário/Backup no final do disco.
     */
    suspend fun writeSinglePartitionGpt(
        blockDevice: IBlockDevice,
        fsType: FilesystemType,
        partitionName: String = "USB_DATA"
    ): GptPartitionEntry {
        val sectorSize = blockDevice.sectorSize
        require(sectorSize == 512 || sectorSize == 4096) { "Setores suportados: 512 ou 4096" }
        val totalSectors = blockDevice.totalSectors

        val firstUsableLba = PartitionAligner.getFirstAlignedLba(sectorSize) // LBA 2048 (1 MiB)
        val entriesSectors = (PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE) / sectorSize
        val lastUsableLba = totalSectors - entriesSectors - 2 // Reserva espaço para backup

        require(lastUsableLba > firstUsableLba) {
            "Espaço insuficiente no disco para estruturação GPT. Setores totais: $totalSectors"
        }

        val diskGuid = UUID.randomUUID()
        val partitionGuid = UUID.randomUUID()
        val typeGuid = when (fsType) {
            FilesystemType.EXT4 -> GptPartitionEntry.LINUX_DATA_GUID
            else -> GptPartitionEntry.BASIC_DATA_GUID
        }

        val entry = GptPartitionEntry(
            typeGuid = typeGuid,
            uniqueGuid = partitionGuid,
            startingLba = firstUsableLba,
            endingLba = lastUsableLba,
            attributes = 0L,
            name = partitionName
        )

        // 1. Grava Protective MBR no LBA 0
        writeProtectiveMbr(blockDevice, totalSectors)

        // 2. Prepara o array de 128 entradas de partição (16 KB)
        val entriesBuffer = ByteBuffer.allocateDirect(PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE)
        entriesBuffer.order(ByteOrder.LITTLE_ENDIAN)
        entry.writeTo(entriesBuffer)
        // O restante das 127 entradas permanece preenchido com zeros
        while (entriesBuffer.hasRemaining()) {
            entriesBuffer.put(0.toByte())
        }
        entriesBuffer.flip()

        // Calcula CRC32 do array de partições
        val crcCalculator = CRC32()
        val entriesBytes = ByteArray(PARTITION_ENTRY_COUNT * PARTITION_ENTRY_SIZE)
        entriesBuffer.get(entriesBytes)
        crcCalculator.update(entriesBytes)
        val entriesCrc32 = crcCalculator.value.toInt()
        entriesBuffer.flip()

        // 3. Grava GPT Header Primário no LBA 1
        val primaryHeaderBuffer = createGptHeader(
            currentLba = 1L,
            backupLba = totalSectors - 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            entriesLba = 2L,
            entriesCrc32 = entriesCrc32,
            sectorSize = sectorSize
        )
        blockDevice.writeSectors(1L, 1, primaryHeaderBuffer)

        // 4. Grava o array de partições primário (LBAs 2 a 33 para setores de 512B)
        blockDevice.writeSectors(2L, entriesSectors, entriesBuffer)

        // 5. Grava o array de partições de backup no final do disco
        val backupEntriesLba = totalSectors - entriesSectors - 1L
        entriesBuffer.rewind()
        blockDevice.writeSectors(backupEntriesLba, entriesSectors, entriesBuffer)

        // 6. Grava o GPT Header Secundário no último LBA (totalSectors - 1)
        val backupHeaderBuffer = createGptHeader(
            currentLba = totalSectors - 1L,
            backupLba = 1L,
            firstUsableLba = firstUsableLba,
            lastUsableLba = lastUsableLba,
            diskGuid = diskGuid,
            entriesLba = backupEntriesLba,
            entriesCrc32 = entriesCrc32,
            sectorSize = sectorSize
        )
        blockDevice.writeSectors(totalSectors - 1L, 1, backupHeaderBuffer)

        blockDevice.sync()
        return entry
    }

    /**
     * Grava o MBR de Proteção no LBA 0 com uma partição de tipo 0xEE cobrindo todo o disco.
     */
    private suspend fun writeProtectiveMbr(blockDevice: IBlockDevice, totalSectors: Long) {
        val buffer = ByteBuffer.allocateDirect(blockDevice.sectorSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        // Limpa o setor
        while (buffer.hasRemaining()) buffer.put(0.toByte())
        buffer.clear()

        // Entra no offset 446 (Primeira partição MBR)
        buffer.position(MbrEngine.PARTITION_TABLE_OFFSET)
        val protectiveSize = if (totalSectors - 1 > 0xFFFFFFFFL) 0xFFFFFFFFL else (totalSectors - 1)
        val record = MbrPartitionRecord(
            bootable = false,
            typeByte = 0xEE.toByte(), // GPT Protective
            startLba = 1L,
            sectorCount = protectiveSize
        )
        record.writeTo(buffer)

        // Assinatura 0x55AA
        buffer.position(MbrEngine.SIGNATURE_OFFSET)
        buffer.putShort(MbrEngine.MBR_SIGNATURE)

        buffer.flip()
        blockDevice.writeSectors(0L, 1, buffer)
    }

    private fun createGptHeader(
        currentLba: Long,
        backupLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: UUID,
        entriesLba: Long,
        entriesCrc32: Int,
        sectorSize: Int
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(sectorSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.hasRemaining()) buffer.put(0.toByte())
        buffer.clear()

        buffer.putLong(GPT_SIGNATURE)
        buffer.putInt(GPT_REVISION)
        buffer.putInt(HEADER_SIZE)
        buffer.putInt(0) // Header CRC32 (temporariamente 0 para cálculo)
        buffer.putInt(0) // Reserved
        buffer.putLong(currentLba)
        buffer.putLong(backupLba)
        buffer.putLong(firstUsableLba)
        buffer.putLong(lastUsableLba)

        // Grava Disk GUID
        writeUuid(buffer, diskGuid)

        buffer.putLong(entriesLba)
        buffer.putInt(PARTITION_ENTRY_COUNT)
        buffer.putInt(PARTITION_ENTRY_SIZE)
        buffer.putInt(entriesCrc32)

        // Calcula CRC32 do cabeçalho de 92 bytes
        val headerBytes = ByteArray(HEADER_SIZE)
        buffer.position(0)
        buffer.get(headerBytes)
        val crc = CRC32()
        crc.update(headerBytes)
        val headerCrc32 = crc.value.toInt()

        // Escreve o CRC32 calculado no offset 16
        buffer.position(16)
        buffer.putInt(headerCrc32)

        buffer.clear()
        return buffer
    }

    private fun writeUuid(buffer: ByteBuffer, uuid: UUID) {
        val mostSig = uuid.mostSignificantBits
        val timeLow = (mostSig ushr 32).toInt()
        val timeMid = (mostSig ushr 16).toShort()
        val timeHiAndVersion = mostSig.toShort()

        buffer.putInt(timeLow)
        buffer.putShort(timeMid)
        buffer.putShort(timeHiAndVersion)

        val leastSig = uuid.leastSignificantBits
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(leastSig)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
    }
}
