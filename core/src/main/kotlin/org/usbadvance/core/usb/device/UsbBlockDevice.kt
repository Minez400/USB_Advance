package org.usbadvance.core.usb.device

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.IBlockDevice
import org.usbadvance.core.usb.bot.BotProtocolHandler
import org.usbadvance.core.usb.bot.CommandStatusWrapper
import org.usbadvance.core.usb.scsi.ScsiCommands
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Concrete implementation of IBlockDevice operating on top of Android USB Host API
 * without requiring Root privileges (userspace USB Mass Storage / SCSI over BOT).
 */
class UsbBlockDevice(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val botHandler: BotProtocolHandler,
    override val sectorSize: Int,
    override val totalSectors: Long
) : IBlockDevice {

    override suspend fun readSectors(lba: Long, count: Int, destination: ByteBuffer) = withContext(Dispatchers.IO) {
        val maxSectorsPerCmd = maxOf(1, (64 * 1024) / sectorSize) // 64 KB max per SCSI command for OTG reliability
        var remaining = count
        var currentLba = lba
        while (remaining > 0) {
            val chunk = minOf(remaining, maxSectorsPerCmd)
            val chunkBytes = chunk * sectorSize
            val slice = destination.duplicate().apply {
                limit(position() + chunkBytes)
            }
            val cdb = ScsiCommands.read10(currentLba, chunk)
            val csw = botHandler.executeCommand(cdb, slice, directionIn = true)
            if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
                throw IOException("SCSI READ 10 command failed for LBA $currentLba (CSW status: ${csw.status})")
            }
            destination.position(destination.position() + chunkBytes)
            remaining -= chunk
            currentLba += chunk
        }
    }

    override suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer) = withContext(Dispatchers.IO) {
        val maxSectorsPerCmd = maxOf(1, (64 * 1024) / sectorSize) // 64 KB max per SCSI command for OTG reliability
        var remaining = count
        var currentLba = lba
        while (remaining > 0) {
            val chunk = minOf(remaining, maxSectorsPerCmd)
            val chunkBytes = chunk * sectorSize
            val slice = source.duplicate().apply {
                limit(position() + chunkBytes)
            }
            val cdb = ScsiCommands.write10(currentLba, chunk)
            val csw = botHandler.executeCommand(cdb, slice, directionIn = false)
            if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
                throw IOException("SCSI WRITE 10 command failed for LBA $currentLba (CSW status: ${csw.status})")
            }
            source.position(source.position() + chunkBytes)
            remaining -= chunk
            currentLba += chunk
        }
    }

    override suspend fun eraseSectors(lba: Long, count: Int) = withContext(Dispatchers.IO) {
        val maxChunkSectors = 128 // 64 KB per batch for 512B sectors
        val zeroBuffer = ByteBuffer.allocateDirect(maxChunkSectors * sectorSize)
        while (zeroBuffer.hasRemaining()) zeroBuffer.put(0.toByte())

        var sectorsRemaining = count
        var currentLba = lba

        while (sectorsRemaining > 0) {
            val chunk = minOf(sectorsRemaining, maxChunkSectors)
            zeroBuffer.position(0)
            zeroBuffer.limit(chunk * sectorSize)

            writeSectors(currentLba, chunk, zeroBuffer)
            sectorsRemaining -= chunk
            currentLba += chunk
        }
    }

    override suspend fun sync(): Unit = withContext(Dispatchers.IO) {
        try {
            val cdb = ScsiCommands.synchronizeCache10()
            botHandler.executeCommand(cdb, null, directionIn = false, timeoutMs = 3000)
        } catch (_: Exception) {
            // Non-fatal: most USB flash drives do not implement SYNCHRONIZE CACHE 10 (opcode 0x35)
            // Matching Linux kernel drivers/scsi/sd.c, cache sync failures on flash media are suppressed.
        }
        Unit
    }

    override suspend fun isWriteProtected(): Boolean = withContext(Dispatchers.IO) {
        // USB thumb drives / flash pendrives do NOT have mechanical write-protect switches.
        // Sending MODE SENSE 6 (opcode 0x1A) causes low-cost USB flash controllers (Chipsbank,
        // Alcor, Silicon Motion, Appotech, Phison) to STALL the Bulk-IN endpoint, which disrupts
        // the BOT transport right before partition creation. We safely report false.
        false
    }

    override suspend fun eject(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            sync()
            val cdb = ScsiCommands.startStopUnit(start = false, loadEject = true)
            botHandler.executeCommand(cdb, null, directionIn = false, timeoutMs = 3000)
            close()
            true
        } catch (e: Exception) {
            close()
            false
        }
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
        } catch (ignored: Exception) {
        }
    }
}
