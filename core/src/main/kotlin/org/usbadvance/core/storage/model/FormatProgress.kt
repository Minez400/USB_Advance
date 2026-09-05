package org.usbadvance.core.storage.model

/**
 * Snapshot de progresso reportado periodicamente durante a formatação.
 */
data class FormatProgress(
    val stage: FormatStage,
    val stageDescription: String,
    val percentage: Float, // 0.0f a 100.0f
    val bytesProcessed: Long = 0L,
    val totalBytesToProcess: Long = 0L,
    val currentSpeedBytesPerSec: Long = 0L,
    val estimatedRemainingSeconds: Long = -1L
)

enum class FormatStage {
    INITIALIZING,
    DISCONNECTING_KERNEL_DRIVER,
    CHECKING_WRITE_PROTECTION,
    CREATING_PARTITION_TABLE,
    INITIALIZING_METADATA,
    WRITING_BOOT_SECTOR,
    ALLOCATING_FILE_TABLES,
    CREATING_ROOT_DIRECTORY,
    WIPING_DATA_SECTORS,
    SYNCHRONIZING_CACHE,
    VERIFYING,
    COMPLETED,
    FAILED
}

fun interface FormatProgressCallback {
    fun onProgress(progress: FormatProgress)
}
