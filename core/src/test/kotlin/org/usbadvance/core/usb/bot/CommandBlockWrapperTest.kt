package org.usbadvance.core.usb.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CommandBlockWrapperTest {

    @Test
    fun testCbwSerialization() {
        val cdb = byteArrayOf(0x2A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00) // WRITE 10
        val cbw = CommandBlockWrapper(
            tag = 0x12345678,
            dataTransferLength = 65536,
            directionIn = false,
            cdb = cdb
        )

        val target = ByteArray(CommandBlockWrapper.CBW_LENGTH)
        cbw.serializeTo(target)

        // Verify signature "USBC"
        assertEquals(0x55.toByte(), target[0])
        assertEquals(0x53.toByte(), target[1])
        assertEquals(0x42.toByte(), target[2])
        assertEquals(0x43.toByte(), target[3])

        // Verify CDB size
        assertEquals(10.toByte(), target[14])
    }

    @Test
    fun testCswParsing() {
        val rawCsw = ByteArray(13)
        // Signature "USBS"
        rawCsw[0] = 0x55.toByte()
        rawCsw[1] = 0x53.toByte()
        rawCsw[2] = 0x42.toByte()
        rawCsw[3] = 0x53.toByte()
        // Tag 0x12345678
        rawCsw[4] = 0x78.toByte()
        rawCsw[5] = 0x56.toByte()
        rawCsw[6] = 0x34.toByte()
        rawCsw[7] = 0x12.toByte()
        // Residue 0
        rawCsw[8] = 0
        rawCsw[9] = 0
        rawCsw[10] = 0
        rawCsw[11] = 0
        // Status 0 (PASSED)
        rawCsw[12] = 0

        val csw = CommandStatusWrapper.parse(rawCsw)
        assertNotNull(csw)
        assertEquals(0x12345678, csw.tag)
        assertEquals(0, csw.dataResidue)
        assertEquals(CommandStatusWrapper.Status.COMMAND_PASSED, csw.status)
    }
}
