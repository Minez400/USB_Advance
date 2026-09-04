package org.usbadvance.core.usb.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Command Block Wrapper (CBW) per USB Mass Storage Class Bulk-Only Transport (BOT) specification.
 * Exactly 31 bytes in length.
 */
data class CommandBlockWrapper(
    val tag: Int,
    val dataTransferLength: Int,
    val directionIn: Boolean, // true = Device-to-Host (Data IN), false = Host-to-Device (Data OUT)
    val lun: Byte = 0,
    val cdb: ByteArray // SCSI Command Descriptor Block (up to 16 bytes)
) {
    init {
        require(cdb.size in 1..16) { "SCSI Command Descriptor Block (CDB) must be between 1 and 16 bytes. Current size: ${cdb.size}" }
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
        // Zero-pad remaining CDB bytes up to 16 bytes
        for (i in cdb.size until 16) {
            buffer.put(0.toByte())
        }

        return buffer.array()
    }

    companion object {
        const val CBW_SIGNATURE = 0x43425355 // "USBC" in ASCII Little Endian
        const val CBW_LENGTH = 31
    }
}
