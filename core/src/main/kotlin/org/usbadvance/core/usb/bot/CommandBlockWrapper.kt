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

    fun serializeTo(target: ByteArray) {
        require(target.size >= CBW_LENGTH) { "Target array too small for CBW: ${target.size}" }
        // CBW_SIGNATURE = 0x43425355 ("USBC") in Little Endian
        target[0] = 0x55.toByte() // 'U'
        target[1] = 0x53.toByte() // 'S'
        target[2] = 0x42.toByte() // 'B'
        target[3] = 0x43.toByte() // 'C'
        // Tag (32-bit Little Endian)
        target[4] = (tag and 0xFF).toByte()
        target[5] = ((tag ushr 8) and 0xFF).toByte()
        target[6] = ((tag ushr 16) and 0xFF).toByte()
        target[7] = ((tag ushr 24) and 0xFF).toByte()
        // Data transfer length (32-bit Little Endian)
        target[8] = (dataTransferLength and 0xFF).toByte()
        target[9] = ((dataTransferLength ushr 8) and 0xFF).toByte()
        target[10] = ((dataTransferLength ushr 16) and 0xFF).toByte()
        target[11] = ((dataTransferLength ushr 24) and 0xFF).toByte()
        // Flags
        target[12] = if (directionIn) 0x80.toByte() else 0x00.toByte()
        // LUN
        target[13] = lun
        // CDB Length
        target[14] = cdb.size.toByte()
        // CDB bytes
        System.arraycopy(cdb, 0, target, 15, cdb.size)
        // Zero-pad remaining CDB bytes up to 16 bytes
        for (i in (15 + cdb.size) until 31) {
            target[i] = 0.toByte()
        }
    }

    fun serialize(): ByteArray {
        val buffer = ByteArray(CBW_LENGTH)
        serializeTo(buffer)
        return buffer
    }

    companion object {
        const val CBW_SIGNATURE = 0x43425355 // "USBC" in ASCII Little Endian
        const val CBW_LENGTH = 31
    }
}
