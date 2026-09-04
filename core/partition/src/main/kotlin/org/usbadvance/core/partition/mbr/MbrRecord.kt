package org.usbadvance.core.partition.mbr

import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Registro de uma entrada de partição de 16 bytes na tabela MBR.
 */
data class MbrPartitionRecord(
    val bootable: Boolean,
    val typeByte: Byte,
    val startLba: Long,
    val sectorCount: Long
) {
    fun toFilesystemType(): FilesystemType? {
        val unsignedType = typeByte.toInt() and 0xFF
        return when (unsignedType) {
            0x04, 0x06, 0x0E -> FilesystemType.FAT16
            0x0B, 0x0C -> FilesystemType.FAT32
            0x07 -> FilesystemType.EXFAT // Pode ser exFAT ou NTFS; a leitura do VBR desambigua
            0x83 -> FilesystemType.EXT4
            else -> null
        }
    }

    fun writeTo(buffer: ByteBuffer) {
        val originalOrder = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        try {
            buffer.put(if (bootable) 0x80.toByte() else 0x00.toByte())

            // CHS Start: LBA 2048 mapeia para CHS arbitrário legado (1023, 254, 63)
            buffer.put(0x00.toByte())
            buffer.put(0x20.toByte())
            buffer.put(0x21.toByte())

            buffer.put(typeByte)

            // CHS End
            buffer.put(0xFE.toByte())
            buffer.put(0xFF.toByte())
            buffer.put(0xFF.toByte())

            buffer.putInt(startLba.toInt())
            buffer.putInt(sectorCount.toInt())
        } finally {
            buffer.order(originalOrder)
        }
    }

    companion object {
        fun parseFrom(buffer: ByteBuffer): MbrPartitionRecord? {
            val originalOrder = buffer.order()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            try {
                val status = buffer.get().toInt() and 0xFF
                // Ignora CHS start (3 bytes)
                buffer.get()
                buffer.get()
                buffer.get()

                val type = buffer.get()

                // Ignora CHS end (3 bytes)
                buffer.get()
                buffer.get()
                buffer.get()

                val startLba = buffer.getInt().toLong() and 0xFFFFFFFFL
                val sectorCount = buffer.getInt().toLong() and 0xFFFFFFFFL

                if (type.toInt() == 0x00 || sectorCount == 0L) {
                    return null
                }

                return MbrPartitionRecord(
                    bootable = (status == 0x80),
                    typeByte = type,
                    startLba = startLba,
                    sectorCount = sectorCount
                )
            } finally {
                buffer.order(originalOrder)
            }
        }

        fun getTypeByteFor(fsType: FilesystemType): Byte {
            return when (fsType) {
                FilesystemType.FAT16 -> 0x06.toByte()
                FilesystemType.FAT32 -> 0x0C.toByte() // FAT32 LBA
                FilesystemType.EXFAT -> 0x07.toByte() // exFAT / IFS
                FilesystemType.EXT4 -> 0x83.toByte()  // Linux Native
                FilesystemType.NTFS -> 0x07.toByte()
            }
        }
    }
}
