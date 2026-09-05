package org.usbadvance.core.usb.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Command Status Wrapper (CSW) per USB Mass Storage Class Bulk-Only Transport (BOT) specification.
 * Exactly 13 bytes in length.
 */
data class CommandStatusWrapper(
    val tag: Int,
    val dataResidue: Int,
    val status: Status
) {
    enum class Status(val code: Byte) {
        COMMAND_PASSED(0x00),
        COMMAND_FAILED(0x01),
        PHASE_ERROR(0x02);

        companion object {
            fun fromCode(code: Byte): Status = entries.firstOrNull { it.code == code } ?: PHASE_ERROR
        }
    }

    companion object {
        const val CSW_SIGNATURE = 0x53425355 // "USBS" in ASCII Little Endian
        const val CSW_LENGTH = 13

        fun parse(data: ByteArray): CommandStatusWrapper {
            require(data.size >= CSW_LENGTH) { "Insufficient byte length for CSW: ${data.size}" }

            val signature = (data[0].toInt() and 0xFF) or
                    ((data[1].toInt() and 0xFF) shl 8) or
                    ((data[2].toInt() and 0xFF) shl 16) or
                    ((data[3].toInt() and 0xFF) shl 24)

            if (signature != CSW_SIGNATURE) {
                throw IllegalStateException("Invalid CSW signature: 0x${Integer.toHexString(signature)} (expected USBS)")
            }

            val tag = (data[4].toInt() and 0xFF) or
                    ((data[5].toInt() and 0xFF) shl 8) or
                    ((data[6].toInt() and 0xFF) shl 16) or
                    ((data[7].toInt() and 0xFF) shl 24)

            val residue = (data[8].toInt() and 0xFF) or
                    ((data[9].toInt() and 0xFF) shl 8) or
                    ((data[10].toInt() and 0xFF) shl 16) or
                    ((data[11].toInt() and 0xFF) shl 24)

            val statusByte = data[12]

            return CommandStatusWrapper(
                tag = tag,
                dataResidue = residue,
                status = Status.fromCode(statusByte)
            )
        }
    }
}
