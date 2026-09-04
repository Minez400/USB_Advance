package org.usbadvance.core.usb.detector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.usbadvance.core.storage.api.GenericStorageDevice
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.storage.api.StorageBusType
import org.usbadvance.core.storage.model.DiskGeometry
import org.usbadvance.core.storage.model.PartitionTableType
import org.usbadvance.core.usb.bot.BotProtocolHandler
import org.usbadvance.core.usb.device.UsbBlockDevice
import org.usbadvance.core.usb.scsi.ScsiCommands
import java.nio.ByteBuffer

/**
 * Detector de hardware responsável por escanear o barramento USB OTG e notificar
 * conexões e desconexões de pendrives e discos externos.
 */
class UsbHostDetector(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectedDevices = MutableStateFlow<List<IStorageDevice>>(emptyList())
    val connectedDevices: StateFlow<List<IStorageDevice>> = _connectedDevices.asStateFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDevices()
                }
            }
        }
    }

    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)
        refreshDevices()
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (ignored: Exception) {
        }
    }

    fun refreshDevices() {
        val detectedList = mutableListOf<IStorageDevice>()
        val deviceList = usbManager.deviceList

        for ((_, usbDevice) in deviceList) {
            val massStorageIntf = findMassStorageInterface(usbDevice) ?: continue

            // Verifica permissão
            val hasPermission = usbManager.hasPermission(usbDevice)

            val vendorName = usbDevice.manufacturerName ?: "Dispositivo USB"
            val productName = usbDevice.productName ?: "Armazenamento OTG"
            val serial = usbDevice.serialNumber ?: "USB-${usbDevice.deviceId}"

            // Cria o representador de alto nível
            val storageDevice = GenericStorageDevice(
                id = usbDevice.deviceName,
                name = "$vendorName $productName",
                vendor = vendorName,
                product = productName,
                revision = "1.00",
                serialNumber = serial,
                busType = StorageBusType.USB,
                geometry = DiskGeometry(512, 1024), // Geometria atualizada na abertura
                partitionTableType = PartitionTableType.MBR,
                partitions = emptyList(),
                isRemovable = true,
                isWriteProtected = false,
                blockDeviceProvider = {
                    openUsbBlockDevice(usbDevice, massStorageIntf)
                }
            )
            detectedList.add(storageDevice)
        }

        _connectedDevices.value = detectedList
    }

    private fun openUsbBlockDevice(device: UsbDevice, usbInterface: UsbInterface): UsbBlockDevice {
        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Não foi possível abrir conexão USB. Permissão concedida?")

        // REQUISITO CRÍTICO: force = true desanexa o driver usb-storage do kernel Linux
        val claimed = connection.claimInterface(usbInterface, true)
        if (!claimed) {
            connection.close()
            throw IllegalStateException("Falha ao reivindicar interface USB (claimInterface)")
        }

        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    inEndpoint = ep
                } else {
                    outEndpoint = ep
                }
            }
        }

        if (inEndpoint == null || outEndpoint == null) {
            connection.releaseInterface(usbInterface)
            connection.close()
            throw IllegalStateException("Endpoints Bulk IN/OUT não encontrados na interface USB.")
        }

        val botHandler = BotProtocolHandler(connection, usbInterface, inEndpoint, outEndpoint)

        // Executa comando SCSI READ CAPACITY 10 para descobrir tamanho real
        val capCdb = ScsiCommands.readCapacity10()
        val capBuf = ByteBuffer.allocate(8)
        botHandler.executeCommand(capCdb, capBuf, directionIn = true)
        val capInfo = ScsiCommands.parseReadCapacity10Response(capBuf.array())

        return UsbBlockDevice(
            connection = connection,
            usbInterface = usbInterface,
            botHandler = botHandler,
            sectorSize = capInfo.sectorSize,
            totalSectors = capInfo.totalSectors
        )
    }

    private fun findMassStorageInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            // Classe 0x08 (Mass Storage), Subclasse 0x06 (SCSI), Protocolo 0x50 (Bulk-Only Transport)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                intf.interfaceSubclass == 0x06 &&
                intf.interfaceProtocol == 0x50
            ) {
                return intf
            }
        }
        return null
    }
}
