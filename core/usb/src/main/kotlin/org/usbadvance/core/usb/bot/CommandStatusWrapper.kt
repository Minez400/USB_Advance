package org.usbadvance.core.usb.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Command Status Wrapper (CSW) segundo a especificação USB Mass Storage Class Bulk-Only Transport (BOT).
 * Possui tamanho estrito de 13 bytes.
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
        const val CSW_SIGNATURE = 0x53425355 // "USBS" em ASCII Little Endian
        const val CSW_LENGTH = 13

        fun parse(data: ByteArray): CommandStatusWrapper {
            require(data.size >= CSW_LENGTH) { "Tamanho insuficiente para CSW: ${data.size}" }
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            val signature = buffer.int
            if (signature != CSW_SIGNATURE) {
                throw IllegalStateException("Assinatura CSW inválida: 0x${Integer.toHexString(signature)} (esperado USBS)")
            }

            val tag = buffer.int
            val residue = buffer.int
            val statusByte = buffer.get()

            return CommandStatusWrapper(
                tag = tag,
                dataResidue = residue,
                status = Status.fromCode(statusByte)
            )
        }
    }
}
