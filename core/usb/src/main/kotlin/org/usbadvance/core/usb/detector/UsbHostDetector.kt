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
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.usbadvance.core.storage.api.GenericStorageDevice
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.storage.api.StorageBusType
import org.usbadvance.core.storage.model.DiskGeometry
import org.usbadvance.core.storage.model.PartitionTableType
import org.usbadvance.core.usb.bot.BotProtocolHandler
import org.usbadvance.core.usb.bot.CommandStatusWrapper
import org.usbadvance.core.usb.device.UsbBlockDevice
import org.usbadvance.core.usb.permission.UsbPermissionManager
import org.usbadvance.core.usb.scsi.ScsiCommands
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Detector de hardware responsável por escanear o barramento USB OTG e notificar
 * conexões e desconexões de pendrives e discos externos.
 * Totalmente blindado contra SecurityException no Android 10+ e Android 14+.
 */
class UsbHostDetector(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val permissionManager = UsbPermissionManager(context)
    private val detectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val geometryCache = ConcurrentHashMap<String, DiskGeometry>()

    private val _connectedDevices = MutableStateFlow<List<IStorageDevice>>(emptyList())
    val connectedDevices: StateFlow<List<IStorageDevice>> = _connectedDevices.asStateFlow()

    private var isListening = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    device?.deviceName?.let { geometryCache.remove(it) }
                    refreshDevices()
                }
            }
        }
    }

    fun startListening() {
        if (isListening) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        isListening = true
        refreshDevices()
    }

    fun stopListening() {
        if (!isListening) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (ignored: Exception) {
        } finally {
            isListening = false
        }
    }

    suspend fun requestPermission(device: IStorageDevice): Boolean {
        val usbDevice = usbManager.deviceList.values.firstOrNull { it.deviceName == device.id } ?: return false
        val granted = permissionManager.requestPermission(usbDevice)
        if (granted) {
            geometryCache.remove(device.id) // Força re-leitura com permissão concedida
            refreshDevices()
        }
        return granted
    }

    fun refreshDevices() {
        detectorScope.launch {
            refreshDevicesInternal()
        }
    }

    private suspend fun refreshDevicesInternal() = withContext(Dispatchers.IO) {
        val detectedList = mutableListOf<IStorageDevice>()
        val deviceList = try {
            usbManager.deviceList
        } catch (e: Exception) {
            emptyMap()
        }

        for ((_, usbDevice) in deviceList) {
            val massStorageIntf = findMassStorageInterface(usbDevice) ?: continue

            // Verifica permissão e obtém geometria real se disponível
            val hasPermission = try {
                usbManager.hasPermission(usbDevice)
            } catch (e: Exception) {
                false
            }

            val geometry = if (hasPermission) {
                geometryCache.getOrPut(usbDevice.deviceName) {
                    try {
                        queryCapacitySafely(usbDevice, massStorageIntf)
                    } catch (e: Exception) {
                        DiskGeometry(512, 0L)
                    }
                }
            } else {
                DiskGeometry(512, 0L)
            }

            // Proteção estrita contra SecurityException (Android 10+)
            val vendorName = try {
                usbDevice.manufacturerName?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                null
            } ?: "Dispositivo USB (0x%04X)".format(usbDevice.vendorId)

            val productName = try {
                usbDevice.productName?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                null
            } ?: "Armazenamento OTG (0x%04X)".format(usbDevice.productId)

            val serial = try {
                usbDevice.serialNumber?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                null
            } ?: "USB-${usbDevice.deviceId}"

            // Cria o representador de alto nível
            val storageDevice = GenericStorageDevice(
                id = usbDevice.deviceName,
                name = "$vendorName $productName",
                vendor = vendorName,
                product = productName,
                revision = "1.00",
                serialNumber = serial,
                busType = StorageBusType.USB,
                geometry = geometry,
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

    private fun queryCapacitySafely(device: UsbDevice, usbInterface: UsbInterface): DiskGeometry {
        val connection = usbManager.openDevice(device) ?: return DiskGeometry(512, 0L)
        try {
            if (!connection.claimInterface(usbInterface, true)) {
                return DiskGeometry(512, 0L)
            }
            var inEndpoint: UsbEndpoint? = null
            var outEndpoint: UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val ep = usbInterface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEndpoint = ep else outEndpoint = ep
                }
            }
            if (inEndpoint == null || outEndpoint == null) return DiskGeometry(512, 0L)

            val botHandler = BotProtocolHandler(connection, usbInterface, inEndpoint, outEndpoint)
            val capCdb = ScsiCommands.readCapacity10()
            val capBuf = ByteBuffer.allocate(8)
            val csw = botHandler.executeCommand(capCdb, capBuf, directionIn = true)
            if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
                return DiskGeometry(512, 0L)
            }
            val capInfo = try {
                ScsiCommands.parseReadCapacity10Response(capBuf.array())
            } catch (e: Exception) {
                null
            }
            if (capInfo != null && capInfo.sectorSize > 0 && (capInfo.sectorSize and (capInfo.sectorSize - 1)) == 0 && capInfo.totalSectors > 0) {
                return DiskGeometry(capInfo.sectorSize, capInfo.totalSectors)
            }
            return DiskGeometry(512, 0L)
        } catch (e: Exception) {
            return DiskGeometry(512, 0L)
        } finally {
            try {
                connection.releaseInterface(usbInterface)
                connection.close()
            } catch (ignored: Exception) {}
        }
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
        val csw = botHandler.executeCommand(capCdb, capBuf, directionIn = true)
        if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
            connection.releaseInterface(usbInterface)
            connection.close()
            throw IllegalStateException("SCSI READ CAPACITY 10 falhou com status: ${csw.status}")
        }
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
