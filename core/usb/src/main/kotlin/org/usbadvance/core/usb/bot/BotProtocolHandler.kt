package org.usbadvance.core.usb.bot

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * State machine handler for the USB Mass Storage Class Bulk-Only Transport (BOT) protocol.
 * Manages atomic CBW (Command Block Wrapper) dispatch, payload transfers, and CSW validation.
 */
class BotProtocolHandler(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) {
    private val tagGenerator = AtomicInteger(1)
    private val defaultTimeoutMs = 10000 // 10-second standard timeout

    /**
     * Executes a full SCSI transaction over Bulk-Only Transport:
     * 1. Command Phase: Sends the 31-byte CBW via the Bulk OUT endpoint.
     * 2. Data Phase (optional): Transmits payload data to/from the device.
     * 3. Status Phase: Receives the 13-byte CSW via the Bulk IN endpoint and verifies execution status.
     */
    @Synchronized
    fun executeCommand(
        cdb: ByteArray,
        dataBuffer: ByteBuffer?,
        directionIn: Boolean
    ): CommandStatusWrapper {
        val tag = tagGenerator.getAndIncrement()
        val transferLength = dataBuffer?.remaining() ?: 0

        val cbw = CommandBlockWrapper(
            tag = tag,
            dataTransferLength = transferLength,
            directionIn = directionIn,
            cdb = cdb
        )

        // 1. Send Command Block Wrapper (CBW)
        val cbwBytes = cbw.serialize()
        val cbwSent = connection.bulkTransfer(outEndpoint, cbwBytes, cbwBytes.size, defaultTimeoutMs)
        if (cbwSent != CommandBlockWrapper.CBW_LENGTH) {
            throw IOException("Failed to dispatch Command Block Wrapper (CBW). Bytes sent: $cbwSent")
        }

        // 2. Transfer payload in safe chunks up to 16 KB (usbfs buffer limit on Android kernel)
        if (dataBuffer != null && transferLength > 0) {
            val maxChunkSize = 16384 // Safe chunk threshold for Linux usbfs on Android
            val transferArray = ByteArray(transferLength)

            if (!directionIn) {
                // Host-to-Device (Data OUT)
                val currentPos = dataBuffer.position()
                dataBuffer.get(transferArray)
                dataBuffer.position(currentPos)

                var offset = 0
                while (offset < transferLength) {
                    val chunk = minOf(transferLength - offset, maxChunkSize)
                    val sent = connection.bulkTransfer(outEndpoint, transferArray, offset, chunk, defaultTimeoutMs)
                    if (sent <= 0) {
                        throw IOException("Error transmitting SCSI data payload on Bulk OUT endpoint: $sent")
                    }
                    offset += sent
                }
            } else {
                // Device-to-Host (Data IN)
                var offset = 0
                while (offset < transferLength) {
                    val chunk = minOf(transferLength - offset, maxChunkSize)
                    val received = connection.bulkTransfer(inEndpoint, transferArray, offset, chunk, defaultTimeoutMs)
                    if (received <= 0) {
                        throw IOException("Error receiving SCSI data payload on Bulk IN endpoint: $received")
                    }
                    offset += received
                }
                dataBuffer.put(transferArray, 0, offset)
            }
        }

        // 3. Receive Command Status Wrapper (CSW)
        val cswBuffer = ByteArray(CommandStatusWrapper.CSW_LENGTH)
        val cswReceived = connection.bulkTransfer(inEndpoint, cswBuffer, cswBuffer.size, defaultTimeoutMs)
        if (cswReceived != CommandStatusWrapper.CSW_LENGTH) {
            throw IOException("Failed to receive Command Status Wrapper (CSW). Bytes received: $cswReceived")
        }

        val csw = CommandStatusWrapper.parse(cswBuffer)
        if (csw.tag != tag) {
            throw IOException("Transaction tag mismatch in CSW. Expected: $tag, Received: ${csw.tag}")
        }

        return csw
    }

    /**
     * Executes standard Bulk-Only Mass Storage Reset Recovery sequence.
     */
    fun resetRecovery() {
        // Sends class-specific control request: Bulk-Only Mass Storage Reset (bRequestType=0x21, bRequest=0xFF)
        connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, null, 0, defaultTimeoutMs)
        // Clear HALT / STALL condition on bulk endpoints if needed
        // connection.clearHalt(inEndpoint); connection.clearHalt(outEndpoint);
    }
}
