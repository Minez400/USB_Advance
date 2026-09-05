package org.usbadvance.feature.devicelist.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.usbadvance.core.root.RootDetector
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.usb.detector.UsbHostDetector

data class DeviceListUiState(
    val isLoading: Boolean = false,
    val devices: List<IStorageDevice> = emptyList(),
    val isRootAvailable: Boolean = false,
    val errorMessage: String? = null
)

class DeviceListViewModel(
    private val usbHostDetector: UsbHostDetector
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceListUiState(isLoading = true))
    val uiState: StateFlow<DeviceListUiState> = _uiState.asStateFlow()

    init {
        checkRootAndStartListening()
    }

    fun setEnableFakeUsb(enabled: Boolean) {
        usbHostDetector.setEnableFakeUsb(enabled)
    }

    private val attemptedAutoConnectIds = mutableSetOf<String>()

    private fun checkRootAndStartListening() {
        viewModelScope.launch {
            val root = RootDetector.isRootAvailable()
            _uiState.value = _uiState.value.copy(isRootAvailable = root)

            usbHostDetector.startListening()
            usbHostDetector.connectedDevices.collect { deviceList ->
                // Prune disconnected device IDs
                val currentIds = deviceList.map { it.id }.toSet()
                attemptedAutoConnectIds.retainAll(currentIds)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    devices = deviceList,
                    errorMessage = null
                )

                // Auto-connect uninitialized USB devices automatically (at most once per physical connection)
                val pendingDevice = deviceList.firstOrNull {
                    it.busType == org.usbadvance.core.storage.api.StorageBusType.USB &&
                    it.geometry.capacityBytes <= 0 &&
                    !attemptedAutoConnectIds.contains(it.id)
                }
                if (pendingDevice != null) {
                    attemptedAutoConnectIds.add(pendingDevice.id)
                    autoConnectDevice(pendingDevice)
                }
            }
        }
    }

    private var isAutoConnecting = false

    private fun autoConnectDevice(device: IStorageDevice) {
        if (isAutoConnecting) return
        isAutoConnecting = true
        viewModelScope.launch {
            try {
                val granted = usbHostDetector.requestPermission(device)
                if (granted) {
                    val refreshedDevices = usbHostDetector.refreshDevicesAsync()
                    _uiState.value = _uiState.value.copy(
                        devices = refreshedDevices,
                        errorMessage = null
                    )
                }
            } catch (_: Exception) {
            } finally {
                isAutoConnecting = false
            }
        }
    }

    fun refresh() {
        attemptedAutoConnectIds.clear()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val devices = usbHostDetector.refreshDevicesAsync()
                _uiState.value = _uiState.value.copy(
                    devices = devices,
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.localizedMessage ?: e.message
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun selectDevice(device: IStorageDevice, onDeviceReady: (IStorageDevice) -> Unit) {
        if (device.busType == org.usbadvance.core.storage.api.StorageBusType.USB && device.geometry.capacityBytes <= 0) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val granted = usbHostDetector.requestPermission(device)
                if (granted) {
                    val refreshedDevices = usbHostDetector.refreshDevicesAsync()
                    val updated = refreshedDevices.firstOrNull { it.id == device.id } ?: device
                    _uiState.value = _uiState.value.copy(
                        devices = refreshedDevices,
                        isLoading = false,
                        errorMessage = null
                    )
                    onDeviceReady(updated)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "USB permission required to access and format device."
                    )
                }
            }
        } else {
            onDeviceReady(device)
        }
    }

    suspend fun ejectDevice(device: IStorageDevice): Boolean {
        return usbHostDetector.ejectDevice(device)
    }

    override fun onCleared() {
        super.onCleared()
        usbHostDetector.stopListening()
    }
}
