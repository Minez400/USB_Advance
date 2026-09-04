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
        val cdb = ScsiCommands.read10(lba, count)
        val csw = botHandler.executeCommand(cdb, destination, directionIn = true)
        if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
            throw IOException("SCSI READ 10 command failed for LBA $lba (CSW status: ${csw.status})")
        }
    }

    override suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer) = withContext(Dispatchers.IO) {
        val cdb = ScsiCommands.write10(lba, count)
        val csw = botHandler.executeCommand(cdb, source, directionIn = false)
        if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
            throw IOException("SCSI WRITE 10 command failed for LBA $lba (CSW status: ${csw.status})")
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

    override suspend fun sync() = withContext(Dispatchers.IO) {
        val cdb = ScsiCommands.synchronizeCache10()
        val csw = botHandler.executeCommand(cdb, null, directionIn = false)
        if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
            throw IOException("SCSI SYNCHRONIZE CACHE 10 command failed")
        }
    }

    override suspend fun isWriteProtected(): Boolean = withContext(Dispatchers.IO) {
        try {
            val cdb = ScsiCommands.modeSense6()
            val buf = ByteBuffer.allocate(192)
            val csw = botHandler.executeCommand(cdb, buf, directionIn = true)
            if (csw.status == CommandStatusWrapper.Status.COMMAND_PASSED && buf.position() >= 3) {
                val modeDataHeader = buf.array()
                val deviceSpecificParam = modeDataHeader[2].toInt() and 0xFF
                // Bit 7 (0x80) of Byte 2 indicates Write Protect (WP) flag
                return@withContext (deviceSpecificParam and 0x80) != 0
            }
        } catch (e: Exception) {
            // Some low-cost USB controllers do not implement MODE SENSE; assume false
        }
        return@withContext false
    }

    override suspend fun eject(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            sync()
            val cdb = ScsiCommands.startStopUnit(start = false, loadEject = true)
            botHandler.executeCommand(cdb, null, directionIn = false)
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
