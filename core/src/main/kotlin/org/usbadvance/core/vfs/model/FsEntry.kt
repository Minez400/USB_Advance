package org.usbadvance.core.vfs.model

/**
 * Represents a file, directory, or special entry in a mounted virtual file system.
 */
data class FsEntry(
    val name: String,
    val fullPath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val modifiedTimestamp: Long = 0L,
    val isContiguous: Boolean = true,
    val firstCluster: Long = 0L,
    val isReadOnly: Boolean = false,
    val isHidden: Boolean = false,
    val isSystem: Boolean = false
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "")

    val isDiscImage: Boolean
        get() = extension.lowercase() in setOf("iso", "bin", "cue", "img", "cso", "chd", "mdf", "nrg")

    val isMediaFile: Boolean
        get() = extension.lowercase() in setOf("mp4", "mkv", "avi", "mp3", "flac", "wav", "aac", "ogg", "mov")
}
