package org.usbadvance.feature.formatter.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.usbadvance.core.fs.nativebridge.ExFatFilesystemProvider
import org.usbadvance.core.fs.nativebridge.Ext4FilesystemProvider
import org.usbadvance.core.fs.nativebridge.Fat16FilesystemProvider
import org.usbadvance.core.fs.nativebridge.Fat32FilesystemProvider
import org.usbadvance.core.partition.PartitionManager
import org.usbadvance.core.storage.api.IStorageDevice
import org.usbadvance.core.storage.model.ErrorCode
import org.usbadvance.core.storage.model.FilesystemType
import org.usbadvance.core.storage.model.FormatOptions
import org.usbadvance.core.storage.model.FormatProgress
import org.usbadvance.core.storage.model.FormatResult
import org.usbadvance.core.storage.model.FormatStage
import org.usbadvance.core.storage.model.PartitionTableType
import org.usbadvance.core.storage.model.ValidationResult
import org.usbadvance.core.storage.provider.FilesystemRegistry
import org.usbadvance.feature.formatter.service.FormatForegroundService

sealed class FormatterStep {
    object Configuration : FormatterStep()
    object SafetyConfirmation : FormatterStep()
    object Executing : FormatterStep()
    data class Completed(val result: FormatResult) : FormatterStep()
}

data class FormatterUiState(
    val step: FormatterStep = FormatterStep.Configuration,
    val device: IStorageDevice? = null,
    val options: FormatOptions = FormatOptions(FilesystemType.FAT32),
    val validationResult: ValidationResult = ValidationResult.valid(),
    val progress: FormatProgress = FormatProgress(FormatStage.INITIALIZING, "Pronto", 0.0f),
    val errorMessage: String? = null
)

class FormatterViewModel(
    private val partitionManager: PartitionManager = PartitionManager()
) : ViewModel() {

    private val _uiState = MutableStateFlow(FormatterUiState())
    val uiState: StateFlow<FormatterUiState> = _uiState.asStateFlow()

    init {
        // Register filesystem SPI providers if not already registered
        if (!FilesystemRegistry.isSupported(FilesystemType.FAT32)) {
            FilesystemRegistry.register(Fat32FilesystemProvider())
            FilesystemRegistry.register(ExFatFilesystemProvider())
            FilesystemRegistry.register(Ext4FilesystemProvider())
            FilesystemRegistry.register(Fat16FilesystemProvider())
        }
    }

    fun selectDevice(device: IStorageDevice) {
        val defaultOptions = FormatOptions.defaultFor(FilesystemType.FAT32)
        _uiState.value = _uiState.value.copy(
            device = device,
            options = defaultOptions,
            step = FormatterStep.Configuration
        )
    }

    fun updateFilesystem(fs: FilesystemType) {
        val current = _uiState.value.options
        val updated = current.copy(
            filesystemType = fs,
            clusterSizeBytes = 0 // 0 = Automatic cluster size selection
        )
        _uiState.value = _uiState.value.copy(options = updated)
        validate()
    }

    fun updatePartitionTable(table: PartitionTableType) {
        val current = _uiState.value.options
        _uiState.value = _uiState.value.copy(options = current.copy(partitionTableType = table))
        validate()
    }

    fun updateVolumeLabel(label: String) {
        val current = _uiState.value.options
        _uiState.value = _uiState.value.copy(options = current.copy(volumeLabel = label))
        validate()
    }

    fun updateClusterSize(clusterBytes: Int) {
        val current = _uiState.value.options
        _uiState.value = _uiState.value.copy(options = current.copy(clusterSizeBytes = clusterBytes))
        validate()
    }

    fun updateQuickFormat(quick: Boolean) {
        val current = _uiState.value.options
        _uiState.value = _uiState.value.copy(options = current.copy(quickFormat = quick, wipeSectors = !quick))
    }

    private fun validate(): Boolean {
        val device = _uiState.value.device ?: return false
        val provider = FilesystemRegistry.get(_uiState.value.options.filesystemType) ?: return false
        val validation = provider.validateOptions(_uiState.value.options, device.geometry.capacityBytes)
        _uiState.value = _uiState.value.copy(validationResult = validation)
        return validation.isValid
    }

    fun proceedToConfirmation() {
        if (validate()) {
            _uiState.value = _uiState.value.copy(step = FormatterStep.SafetyConfirmation)
        }
    }

    fun cancelConfirmation() {
        _uiState.value = _uiState.value.copy(step = FormatterStep.Configuration)
    }

    fun startFormat(context: Context) {
        val device = _uiState.value.device ?: return
        val options = _uiState.value.options

        _uiState.value = _uiState.value.copy(step = FormatterStep.Executing)
        FormatForegroundService.start(context, device.name)

        viewModelScope.launch {
            val result = executeFormatInternal(device, options)
            FormatForegroundService.stop(context)
            _uiState.value = _uiState.value.copy(
                step = FormatterStep.Completed(result),
                errorMessage = if (result is FormatResult.Failure) result.errorMessage else null
            )
        }
    }

    private suspend fun executeFormatInternal(
        device: IStorageDevice,
        options: FormatOptions
    ): FormatResult = withContext(Dispatchers.IO) {
        try {
            val blockDevice = device.openBlockDevice()

            // 1. Hardware write-protect verification
            if (blockDevice.isWriteProtected()) {
                blockDevice.close()
                return@withContext FormatResult.Failure(
                    errorCode = ErrorCode.WRITE_PROTECTED,
                    errorMessage = "O dispositivo possui chave física ou flag de proteção contra gravação (Write-Protect) ativada.",
                    canRetry = false
                )
            }

            // 2. Partition table writing (MBR or GPT)
            _uiState.value = _uiState.value.copy(
                progress = FormatProgress(
                    stage = FormatStage.CREATING_PARTITION_TABLE,
                    stageDescription = "Gravando tabela ${options.partitionTableType.displayName}...",
                    percentage = 10.0f
                )
            )

            val partition = partitionManager.createSinglePartition(
                blockDevice = blockDevice,
                tableType = options.partitionTableType,
                fsType = options.filesystemType,
                volumeLabel = options.volumeLabel
            )

            // 3. Logical filesystem formatting
            val provider = FilesystemRegistry.get(options.filesystemType)
                ?: return@withContext FormatResult.Failure(
                    errorCode = ErrorCode.INTERNAL_NATIVE_ERROR,
                    errorMessage = "Provedor para ${options.filesystemType.displayName} não encontrado."
                )

            val formatResult = provider.format(
                blockDevice = blockDevice,
                partition = partition,
                options = options,
                progressCallback = { p ->
                    _uiState.value = _uiState.value.copy(progress = p)
                }
            )

            blockDevice.close()
            return@withContext formatResult

        } catch (e: Exception) {
            return@withContext FormatResult.Failure(
                errorCode = ErrorCode.IO_ERROR,
                errorMessage = "Falha durante a operação: ${e.message}",
                cause = e
            )
        }
    }
}
