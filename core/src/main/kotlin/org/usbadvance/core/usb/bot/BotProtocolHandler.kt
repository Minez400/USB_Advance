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
    private val defaultTimeoutMs = 20000 // 20-second timeout for OTG bulk transfers

    // Reusable buffers for zero-allocation I/O on resource-constrained devices
    private val reusableCbwBuffer = ByteArray(CommandBlockWrapper.CBW_LENGTH)
    private val reusableChunkBuffer = ByteArray(16384)
    private val reusableCswBuffer = ByteArray(CommandStatusWrapper.CSW_LENGTH)

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
        directionIn: Boolean,
        timeoutMs: Int = defaultTimeoutMs
    ): CommandStatusWrapper {
        val tag = tagGenerator.getAndIncrement()
        val transferLength = dataBuffer?.remaining() ?: 0

        val cbw = CommandBlockWrapper(
            tag = tag,
            dataTransferLength = transferLength,
            directionIn = directionIn,
            cdb = cdb
        )

        try {
            // 1. Send Command Block Wrapper (CBW) via zero-allocation serialization
            cbw.serializeTo(reusableCbwBuffer)
            val cbwSent = connection.bulkTransfer(outEndpoint, reusableCbwBuffer, CommandBlockWrapper.CBW_LENGTH, timeoutMs)
            if (cbwSent != CommandBlockWrapper.CBW_LENGTH) {
                resetRecovery()
                throw IOException("Failed to dispatch Command Block Wrapper (CBW). Bytes sent: $cbwSent")
            }

            // 2. Transfer payload in safe streaming chunks up to 16 KB (usbfs buffer limit on Android kernel)
            if (dataBuffer != null && transferLength > 0) {
                val maxChunkSize = 16384

                if (!directionIn) {
                    // Host-to-Device (Data OUT)
                    var remaining = transferLength
                    while (remaining > 0) {
                        val chunkSize = minOf(remaining, maxChunkSize)
                        dataBuffer.get(reusableChunkBuffer, 0, chunkSize)
                        var chunkSent = 0
                        while (chunkSent < chunkSize) {
                            val sent = connection.bulkTransfer(
                                outEndpoint,
                                reusableChunkBuffer,
                                chunkSent,
                                chunkSize - chunkSent,
                                timeoutMs
                            )
                            if (sent <= 0) {
                                resetRecovery()
                                throw IOException("Error transmitting SCSI data payload on Bulk OUT endpoint: $sent")
                            }
                            chunkSent += sent
                        }
                        remaining -= chunkSize
                    }
                } else {
                    // Device-to-Host (Data IN)
                    var remaining = transferLength
                    var stallOccurred = false
                    while (remaining > 0) {
                        val chunk = minOf(remaining, maxChunkSize)
                        val received = connection.bulkTransfer(inEndpoint, reusableChunkBuffer, 0, chunk, timeoutMs)
                        if (received <= 0) {
                            // USB BOT 1.0 section 6.7.2: Device may stall Bulk IN if it has fewer bytes
                            stallOccurred = true
                            break
                        }
                        dataBuffer.put(reusableChunkBuffer, 0, received)
                        remaining -= received
                    }
                    if (stallOccurred) {
                        clearEndpointHalt(inEndpoint)
                    }
                }
            }

            // 3. Receive Command Status Wrapper (CSW) into reusable buffer
            // Flash memory write cycles require variable commit times. If bulkTransfer returns -1 (timeout or STALL),
            // clear endpoint halt and retry with progressive backoff before failing.
            var cswReceived = -1
            val maxCswAttempts = 4
            for (attempt in 1..maxCswAttempts) {
                cswReceived = connection.bulkTransfer(inEndpoint, reusableCswBuffer, CommandStatusWrapper.CSW_LENGTH, timeoutMs)
                if (cswReceived == CommandStatusWrapper.CSW_LENGTH) {
                    break
                }
                if (attempt < maxCswAttempts) {
                    clearEndpointHalt(inEndpoint)
                    try {
                        Thread.sleep(50L * attempt)
                    } catch (_: InterruptedException) {}
                }
            }

            if (cswReceived != CommandStatusWrapper.CSW_LENGTH) {
                resetRecovery()
                throw IOException("Failed to receive Command Status Wrapper (CSW). Bytes received: $cswReceived")
            }

            val csw = CommandStatusWrapper.parse(reusableCswBuffer)
            if (csw.tag != tag) {
                resetRecovery()
                throw IOException("Transaction tag mismatch in CSW. Expected: $tag, Received: ${csw.tag}")
            }

            return csw
        } catch (e: Exception) {
            resetRecovery()
            throw e
        }
    }

    private val emptyBuffer = ByteArray(0)

    /**
     * Clears STALL / HALT condition on a USB endpoint via standard USB CLEAR_FEATURE request.
     */
    private fun clearEndpointHalt(endpoint: UsbEndpoint) {
        try {
            // bmRequestType=0x02 (Endpoint), bRequest=0x01 (CLEAR_FEATURE), wValue=0 (ENDPOINT_HALT)
            // Using emptyBuffer instead of null prevents NullPointerException/crashes on vendor Android forks
            connection.controlTransfer(0x02, 0x01, 0, endpoint.address, emptyBuffer, 0, 1000)
        } catch (_: Exception) {}
    }

    /**
     * Executes standard Bulk-Only Mass Storage Reset Recovery sequence (USB BOT 1.0 section 5.3.4).
     */
    fun resetRecovery() {
        try {
            // Sends class-specific control request: Bulk-Only Mass Storage Reset (bRequestType=0x21, bRequest=0xFF)
            connection.controlTransfer(0x21, 0xFF, 0, usbInterface.id, emptyBuffer, 0, 1000)
        } catch (_: Exception) {}
        clearEndpointHalt(inEndpoint)
        clearEndpointHalt(outEndpoint)
    }
}
