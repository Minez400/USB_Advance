package org.usbadvance.feature.formatter.vm

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.feature.formatter.engine.IsoBurnProgress
import org.usbadvance.feature.formatter.engine.IsoBurnResult
import org.usbadvance.feature.formatter.engine.IsoWriterEngine
import org.usbadvance.feature.formatter.service.FormatForegroundService

data class IsoBurnerState(
    val selectedUri: Uri? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0L,
    val isBurning: Boolean = false,
    val burnProgress: IsoBurnProgress? = null,
    val burnResult: IsoBurnResult? = null,
    val showConfirmDialog: Boolean = false
)

class IsoBurnerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(IsoBurnerState())
    val uiState: StateFlow<IsoBurnerState> = _uiState.asStateFlow()

    private var burnJob: Job? = null
    private val isoEngine = IsoWriterEngine()

    fun onFileSelected(context: Context, uri: Uri?) {
        if (uri == null) return
        val (name, size) = IsoWriterEngine.queryFileInfo(context.contentResolver, uri)
        _uiState.value = _uiState.value.copy(
            selectedUri = uri,
            fileName = name,
            fileSizeBytes = size,
            burnResult = null,
            burnProgress = null
        )
    }

    fun showConfirmation() {
        _uiState.value = _uiState.value.copy(showConfirmDialog = true)
    }

    fun hideConfirmation() {
        _uiState.value = _uiState.value.copy(showConfirmDialog = false)
    }

    fun startBurn(context: Context, device: IStorageDevice) {
        val state = _uiState.value
        val uri = state.selectedUri ?: return
        val size = state.fileSizeBytes

        _uiState.value = state.copy(
            showConfirmDialog = false,
            isBurning = true,
            burnResult = null,
            burnProgress = null
        )

        FormatForegroundService.start(context, device.name)

        burnJob = viewModelScope.launch(Dispatchers.IO) {
            val blockDevice = device.openBlockDevice()
            try {
                val res = isoEngine.burnImage(
                    contentResolver = context.contentResolver,
                    imageUri = uri,
                    imageSizeBytes = size,
                    blockDevice = blockDevice,
                    onProgress = { prog ->
                        _uiState.value = _uiState.value.copy(burnProgress = prog)
                    }
                )
                _uiState.value = _uiState.value.copy(burnResult = res)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    burnResult = IsoBurnResult(
                        success = false,
                        totalBytesWritten = 0,
                        durationSeconds = 0.0,
                        averageSpeedMbPerSec = 0.0,
                        errorMessage = e.message ?: "Unknown error"
                    )
                )
            } finally {
                try {
                    blockDevice.close()
                } catch (ignored: Exception) {}
                
                _uiState.value = _uiState.value.copy(isBurning = false)
                FormatForegroundService.stop(context)
            }
        }
    }

    fun cancelBurn(context: Context, cancelMessage: String) {
        burnJob?.cancel()
        burnJob = null
        _uiState.value = _uiState.value.copy(
            isBurning = false,
            burnResult = IsoBurnResult(false, 0, 0.0, 0.0, cancelMessage)
        )
        FormatForegroundService.stop(context)
    }
}
