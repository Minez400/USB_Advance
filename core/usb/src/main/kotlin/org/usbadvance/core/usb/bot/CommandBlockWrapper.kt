package org.usbadvance.core.usb.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Command Block Wrapper (CBW) segundo a especificação USB Mass Storage Class Bulk-Only Transport (BOT).
 * Possui tamanho estrito de 31 bytes.
 */
data class CommandBlockWrapper(
    val tag: Int,
    val dataTransferLength: Int,
    val directionIn: Boolean, // true = Device-to-Host (Data IN), false = Host-to-Device (Data OUT)
    val lun: Byte = 0,
    val cdb: ByteArray // Command Descriptor Block SCSI (até 16 bytes)
) {
    init {
        require(cdb.size in 1..16) { "O bloco de comando SCSI (CDB) deve ter entre 1 e 16 bytes. Tamanho atual: ${cdb.size}" }
    }

    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(CBW_LENGTH)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(CBW_SIGNATURE) // 0x43425355 ("USBC")
        buffer.putInt(tag)
        buffer.putInt(dataTransferLength)
        buffer.put(if (directionIn) 0x80.toByte() else 0x00.toByte())
        buffer.put(lun)
        buffer.put(cdb.size.toByte())

        buffer.put(cdb)
        // Preenche o restante dos 16 bytes do CDB com zeros
        for (i in cdb.size until 16) {
            buffer.put(0.toByte())
        }

        return buffer.array()
    }

    companion object {
        const val CBW_SIGNATURE = 0x43425355 // "USBC" em ASCII Little Endian
        const val CBW_LENGTH = 31
    }
}
