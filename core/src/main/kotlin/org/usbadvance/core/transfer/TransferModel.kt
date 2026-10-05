package org.usbadvance.core.transfer

import java.util.UUID

enum class TransferStatus {
    QUEUED,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TransferItem(
    val id: String = UUID.randomUUID().toString(),
    val sourceName: String,
    val destinationPath: String,
    val totalBytes: Long,
    val transferredBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: TransferStatus = TransferStatus.QUEUED,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val etaSeconds: Long
        get() = if (speedBytesPerSec > 0 && totalBytes > transferredBytes) {
            (totalBytes - transferredBytes) / speedBytesPerSec
        } else 0L
}
