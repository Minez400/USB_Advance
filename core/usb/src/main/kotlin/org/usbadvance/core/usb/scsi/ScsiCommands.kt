package org.usbadvance.core.usb.scsi

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Construtor e decodificador dos blocos de comando SCSI (SBC-3 e SPC-4).
 */
object ScsiCommands {

    fun testUnitReady(): ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    fun requestSense(allocationLength: Int = 18): ByteArray {
        return byteArrayOf(
            0x03, 0x00, 0x00, 0x00,
            allocationLength.toByte(),
            0x00
        )
    }

    fun inquiry(allocationLength: Int = 36): ByteArray {
        return byteArrayOf(
            0x12, 0x00, 0x00, 0x00,
            allocationLength.toByte(),
            0x00
        )
    }

    fun modeSense6(pageCode: Byte = 0x3F, allocationLength: Int = 192): ByteArray {
        return byteArrayOf(
            0x1A, 0x00, pageCode, 0x00,
            allocationLength.toByte(),
            0x00
        )
    }

    fun readCapacity10(): ByteArray {
        return byteArrayOf(
            0x25, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    fun readCapacity16(allocationLength: Int = 32): ByteArray {
        val cdb = ByteArray(16)
        cdb[0] = 0x9E.toByte()
        cdb[1] = 0x10.toByte() // Service Action: READ CAPACITY 16
        cdb[13] = allocationLength.toByte()
        return cdb
    }

    fun read10(lba: Long, sectorCount: Int): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = 0x28.toByte()
        cdb[2] = ((lba shr 24) and 0xFF).toByte()
        cdb[3] = ((lba shr 16) and 0xFF).toByte()
        cdb[4] = ((lba shr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[7] = ((sectorCount shr 8) and 0xFF).toByte()
        cdb[8] = (sectorCount and 0xFF).toByte()
        return cdb
    }

    fun write10(lba: Long, sectorCount: Int): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = 0x2A.toByte()
        cdb[2] = ((lba shr 24) and 0xFF).toByte()
        cdb[3] = ((lba shr 16) and 0xFF).toByte()
        cdb[4] = ((lba shr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[7] = ((sectorCount shr 8) and 0xFF).toByte()
        cdb[8] = (sectorCount and 0xFF).toByte()
        return cdb
    }

    fun synchronizeCache10(): ByteArray {
        return byteArrayOf(
            0x35, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    // Decodificadores de Resposta SCSI

    data class InquiryInfo(
        val vendor: String,
        val product: String,
        val revision: String
    )

    fun parseInquiryResponse(data: ByteArray): InquiryInfo {
        if (data.size < 36) return InquiryInfo("Desconhecido", "Dispositivo USB", "")
        val vendor = String(data, 8, 8, Charsets.US_ASCII).trim()
        val product = String(data, 16, 16, Charsets.US_ASCII).trim()
        val revision = String(data, 32, 4, Charsets.US_ASCII).trim()
        return InquiryInfo(vendor, product, revision)
    }

    data class CapacityInfo(
        val totalSectors: Long,
        val sectorSize: Int
    )

    fun parseReadCapacity10Response(data: ByteArray): CapacityInfo {
        require(data.size >= 8) { "Dados insuficientes para ReadCapacity10" }
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN) // SCSI sempre usa Big Endian nos dados!

        val lastLba = buffer.int.toLong() and 0xFFFFFFFFL
        val sectorSize = buffer.int
        return CapacityInfo(lastLba + 1, sectorSize)
    }
}
