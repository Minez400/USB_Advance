package org.usbadvance.core.storage.model

/**
 * Resultado da operação de formatação e particionamento.
 */
sealed class FormatResult {
    data class Success(
        val totalTimeMs: Long,
        val bytesWritten: Long,
        val averageSpeedBytesPerSec: Long,
        val filesystem: FilesystemType,
        val partitionTable: PartitionTableType,
        val volumeLabel: String
    ) : FormatResult()

    data class Failure(
        val errorCode: ErrorCode,
        val errorMessage: String,
        val cause: Throwable? = null,
        val canRetry: Boolean = true
    ) : FormatResult()
}

enum class ErrorCode {
    DEVICE_NOT_FOUND,
    PERMISSION_DENIED,
    WRITE_PROTECTED,
    DEVICE_DISCONNECTED,
    SCSI_COMMAND_FAILED,
    SCSI_PHASE_ERROR,
    UNSUPPORTED_SECTOR_SIZE,
    DISK_TOO_SMALL,
    DISK_TOO_LARGE_FOR_FS,
    INVALID_VOLUME_LABEL,
    IO_ERROR,
    INTERNAL_NATIVE_ERROR,
    CANCELLED_BY_USER
}

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errorKeys: List<String> = emptyList(),
    val warningKeys: List<String> = emptyList()
) {
    companion object {
        fun valid(
            warnings: List<String> = emptyList(),
            warningKeys: List<String> = emptyList()
        ) = ValidationResult(true, emptyList(), warnings, emptyList(), warningKeys)

        fun invalid(vararg errors: String) = ValidationResult(false, errors.toList(), emptyList())

        fun invalidWithKeys(
            errors: List<String>,
            errorKeys: List<String>,
            warnings: List<String> = emptyList(),
            warningKeys: List<String> = emptyList()
        ) = ValidationResult(false, errors, warnings, errorKeys, warningKeys)
    }
}
