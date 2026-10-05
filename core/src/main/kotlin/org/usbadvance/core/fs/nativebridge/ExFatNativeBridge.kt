package org.usbadvance.core.fs.nativebridge

import java.nio.ByteBuffer

/**
 * Low-level interface for block reads performed by C++20 VFS engine.
 */
interface NativeVfsBlockReader {
    fun onReadSectors(lba: Long, count: Int, destination: ByteBuffer): Boolean
}

/**
 * Visitor callback for listing directory entries from native C++.
 */
interface ExFatDirectoryVisitor {
    fun onEntry(
        name: String,
        sizeBytes: Long,
        isDirectory: Boolean,
        modifiedTimestamp: Long,
        isContiguous: Boolean,
        firstCluster: Long,
        isReadOnly: Boolean,
        isHidden: Boolean,
        isSystem: Boolean
    )
}

/**
 * Visitor callback for cluster extents fragmentation analysis.
 */
interface ExFatContiguityVisitor {
    fun onExtent(startCluster: Long, clusterCount: Long, physicalLba: Long)
}

/**
 * JNI Bridge interfacing Kotlin VFS orchestration with the C++20 exFAT engine.
 */
object ExFatNativeBridge {

    init {
        try {
            System.loadLibrary("fsnative")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("Warning: fsnative library not loaded: ${e.message}")
        }
    }

    external fun nativeMount(
        startLba: Long,
        totalSectors: Long,
        sectorSize: Int,
        reader: NativeVfsBlockReader
    ): Long

    external fun nativeUnmount(handle: Long)

    external fun nativeGetVolumeLabel(handle: Long): String

    external fun nativeGetClusterSize(handle: Long): Int

    external fun nativeGetSectorSize(handle: Long): Int

    external fun nativeListDirectory(
        handle: Long,
        path: String,
        visitor: ExFatDirectoryVisitor
    ): Boolean

    external fun nativeReadFile(
        handle: Long,
        firstCluster: Long,
        fileSize: Long,
        noFatChain: Boolean,
        fileOffset: Long,
        destinationBuffer: ByteBuffer,
        bufferOffset: Int,
        readLength: Int
    ): Int

    external fun nativeAnalyzeContiguity(
        handle: Long,
        firstCluster: Long,
        fileSize: Long,
        noFatChain: Boolean,
        visitor: ExFatContiguityVisitor
    ): Boolean
}
