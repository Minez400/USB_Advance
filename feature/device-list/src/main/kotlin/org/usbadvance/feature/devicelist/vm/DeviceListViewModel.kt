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

    private fun checkRootAndStartListening() {
        viewModelScope.launch {
            val root = RootDetector.isRootAvailable()
            _uiState.value = _uiState.value.copy(isRootAvailable = root)

            usbHostDetector.startListening()
            usbHostDetector.connectedDevices.collect { deviceList ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    devices = deviceList,
                    errorMessage = null
                )
            }
        }
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        usbHostDetector.refreshDevices()
    }

    fun selectDevice(device: IStorageDevice, onDeviceReady: (IStorageDevice) -> Unit) {
        if (device.busType == org.usbadvance.core.storage.api.StorageBusType.USB && device.geometry.capacityBytes <= 0) {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val granted = usbHostDetector.requestPermission(device)
                _uiState.value = _uiState.value.copy(isLoading = false)
                if (granted) {
                    val updated = _uiState.value.devices.firstOrNull { it.id == device.id } ?: device
                    onDeviceReady(updated)
                } else {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Permissão USB necessária para acessar e formatar o dispositivo."
                    )
                }
            }
        } else {
            onDeviceReady(device)
        }
    }

    override fun onCleared() {
        super.onCleared()
        usbHostDetector.stopListening()
    }
}
