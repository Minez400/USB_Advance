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
 * Implementação real de IBlockDevice operando sobre a Android USB Host API sem necessidade de Root.
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
            throw IOException("Falha no comando SCSI READ 10 para LBA $lba (Status CSW: ${csw.status})")
        }
    }

    override suspend fun writeSectors(lba: Long, count: Int, source: ByteBuffer) = withContext(Dispatchers.IO) {
        val cdb = ScsiCommands.write10(lba, count)
        val csw = botHandler.executeCommand(cdb, source, directionIn = false)
        if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
            throw IOException("Falha no comando SCSI WRITE 10 para LBA $lba (Status CSW: ${csw.status})")
        }
    }

    override suspend fun eraseSectors(lba: Long, count: Int) = withContext(Dispatchers.IO) {
        val maxChunkSectors = 128 // 64 KB por lote em setores de 512B
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
            throw IOException("Falha no comando SCSI SYNCHRONIZE CACHE 10")
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
                // Bit 7 (0x80) do Byte 2 indica Write Protect (WP)
                return@withContext (deviceSpecificParam and 0x80) != 0
            }
        } catch (e: Exception) {
            // Alguns pendrives baratos não implementam MODE SENSE; assume falso
        }
        return@withContext false
    }

    override fun close() {
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
        } catch (ignored: Exception) {
        }
    }
}
