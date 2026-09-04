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
 * Hardware detector responsible for scanning the USB OTG bus and notifying
 * connection and disconnection events of flash drives, external SSDs, and card readers.
 * Hardened against SecurityException on Android 10+ (API 29+) and Android 14+ (API 34+).
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
            geometryCache.remove(device.id) // Force re-query with granted permissions
            refreshDevices()
        }
        return granted
    }

    suspend fun ejectDevice(device: IStorageDevice): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val blockDevice = device.openBlockDevice()
            val result = blockDevice.eject()
            geometryCache.remove(device.id)
            refreshDevices()
            result
        } catch (e: Exception) {
            geometryCache.remove(device.id)
            refreshDevices()
            false
        }
    }

    fun refreshDevices() {
        detectorScope.launch {
            refreshDevicesInternal()
        }
    }

    suspend fun refreshDevicesAsync(): List<IStorageDevice> = withContext(Dispatchers.IO) {
        refreshDevicesInternal()
    }

    private suspend fun refreshDevicesInternal(): List<IStorageDevice> = withContext(Dispatchers.IO) {
        val detectedList = mutableListOf<IStorageDevice>()
        val deviceList = try {
            usbManager.deviceList
        } catch (e: Exception) {
            emptyMap()
        }

        for ((_, usbDevice) in deviceList) {
            val massStorageIntf = findMassStorageInterface(usbDevice) ?: continue

            // Verify permission and inspect real geometry if accessible
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

            // Guard against SecurityException on Android 10+ (API 29+) when reading device identifiers
            val vendorName = try {
                usbDevice.manufacturerName?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                null
            } ?: "USB Storage (0x%04X)".format(usbDevice.vendorId)

            val productName = try {
                usbDevice.productName?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                null
            } ?: "OTG Drive (0x%04X)".format(usbDevice.productId)

            val serial = try {
                usbDevice.serialNumber?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                null
            } ?: "USB-${usbDevice.deviceId}"

            // Create high-level storage device abstraction
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
        return@withContext detectedList
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
            ?: throw IllegalStateException("Could not open USB connection. Was permission granted?")

        // CRITICAL: force = true detaches kernel driver (usb-storage) and gives userspace exclusive control
        val claimed = connection.claimInterface(usbInterface, true)
        if (!claimed) {
            connection.close()
            throw IllegalStateException("Failed to claim USB interface via claimInterface(force = true)")
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
            throw IllegalStateException("Bulk IN/OUT endpoints not found on target USB interface.")
        }

        val botHandler = BotProtocolHandler(connection, usbInterface, inEndpoint, outEndpoint)

        // Execute SCSI READ CAPACITY 10 to determine true media sector count and sector size
        val capCdb = ScsiCommands.readCapacity10()
        val capBuf = ByteBuffer.allocate(8)
        val csw = botHandler.executeCommand(capCdb, capBuf, directionIn = true)
        if (csw.status != CommandStatusWrapper.Status.COMMAND_PASSED) {
            connection.releaseInterface(usbInterface)
            connection.close()
            throw IllegalStateException("SCSI READ CAPACITY 10 failed with status: ${csw.status}")
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
            // Class 0x08 (Mass Storage), Subclass 0x06 (SCSI transparent command set), Protocol 0x50 (Bulk-Only Transport)
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
