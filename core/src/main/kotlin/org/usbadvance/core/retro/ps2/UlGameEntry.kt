package org.usbadvance.core.retro.ps2

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single 64-byte game entry in the PS2 Open PS2 Loader / USBUtil `ul.cfg` database file.
 * The 15-byte `imagePrefix` field (offset 0x20..0x2E) specifies the exact prefix of the
 * 1 GiB chunk files stored on disk (e.g. `ul.SLUS_200.62` -> `ul.SLUS_200.62.00`).
 */
data class UlGameEntry(
    val title: String,
    val imagePrefix: String,
    val chunkCount: Int,
    val mediaType: MediaType = MediaType.DVD
) {
    val sizeBytes: Long get() = chunkCount.toLong() * 1073741824L
    val partsCount: Int get() = chunkCount

    enum class MediaType(val byteValue: Byte) {
        CD(0x12.toByte()),
        DVD(0x14.toByte());

        companion object {
            fun fromByte(b: Byte): MediaType = if (b == 0x12.toByte()) CD else DVD
        }
    }

    /**
     * Secondary constructor allowing initialization via gameId and optional CRC32.
     */
    constructor(
        title: String,
        gameId: String,
        chunkCount: Int,
        mediaType: MediaType = MediaType.DVD,
        crc32: String = ""
    ) : this(
        title = title,
        imagePrefix = buildPrefix(gameId, crc32),
        chunkCount = chunkCount,
        mediaType = mediaType
    )

    /**
     * Clean Game ID without "ul." prefix.
     */
    val gameId: String
        get() = imagePrefix.removePrefix("ul.").trim()

    /**
     * Base prefix name used for the split chunks on disk.
     */
    val baseChunkName: String
        get() = imagePrefix

    /**
     * Generates filename for chunk part [index] (0-indexed).
     * Example: `ul.SLUS_200.62.00`
     */
    fun getChunkFileName(index: Int): String {
        return "%s.%02d".format(imagePrefix, index)
    }

    companion object {
        const val RECORD_SIZE = 64
        const val MAX_IMAGE_PREFIX_LENGTH = 14 // Max 14 chars + 1 null terminator in 15 bytes
        const val MAGIC_BYTE: Byte = 0x08.toByte()

        /**
         * Safely builds a prefix that strictly fits within the 15-byte ul.cfg constraint.
         */
        fun buildPrefix(gameId: String, crc32: String = ""): String {
            val cleanId = gameId.replace(";", "").replace(" ", "").trim()
            val cleanCrc = crc32.trim()

            // 1. If "ul.CRC.ID" fits within 14 characters, use it
            if (cleanCrc.isNotEmpty() && cleanId.isNotEmpty()) {
                val full = "ul.$cleanCrc.$cleanId"
                if (full.length <= MAX_IMAGE_PREFIX_LENGTH) return full
            }

            // 2. Standard PS2 serial naming: "ul.SLUS_200.62" (3 + 11 = 14 chars)
            if (cleanId.isNotEmpty()) {
                val serialPref = "ul.$cleanId"
                if (serialPref.length <= MAX_IMAGE_PREFIX_LENGTH) return serialPref
                return serialPref.take(MAX_IMAGE_PREFIX_LENGTH)
            }

            // 3. Fallback to CRC
            if (cleanCrc.isNotEmpty()) {
                return "ul.$cleanCrc".take(MAX_IMAGE_PREFIX_LENGTH)
            }

            return "ul.GAME_000.00"
        }

        /**
         * Parses a 64-byte record from [buffer].
         */
        fun parseFrom(buffer: ByteBuffer): UlGameEntry? {
            if (buffer.remaining() < RECORD_SIZE) return null
            val startPos = buffer.position()

            // 0x00..0x1F (32 bytes): Game Title
            val titleBytes = ByteArray(32)
            buffer.get(titleBytes)
            val title = String(titleBytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
            if (title.isEmpty()) {
                buffer.position(startPos + RECORD_SIZE)
                return null
            }

            // 0x20..0x2E (15 bytes): Base image identifier "ul." + GameId / CRC
            val imageBytes = ByteArray(15)
            buffer.get(imageBytes)
            val imageIdent = String(imageBytes, Charsets.US_ASCII).substringBefore('\u0000').trim()

            // 0x2F (1 byte): Number of chunks
            val chunkCount = buffer.get().toInt() and 0xFF

            // 0x30 (1 byte): Media type (0x12 = CD, 0x14 = DVD)
            val mediaByte = buffer.get()
            val mediaType = MediaType.fromByte(mediaByte)

            // Skip reserved and padding to reach 64 bytes
            buffer.position(startPos + RECORD_SIZE)

            return UlGameEntry(
                title = title,
                imagePrefix = if (imageIdent.isNotEmpty()) imageIdent else "ul.UNKNOWN",
                chunkCount = chunkCount,
                mediaType = mediaType
            )
        }

        /**
         * Serializes this game entry into a 64-byte binary record.
         */
        fun writeTo(buffer: ByteBuffer, entry: UlGameEntry) {
            val startPos = buffer.position()

            // 0x00: Title (32 bytes ASCII null-padded)
            val titleBytes = entry.title.toByteArray(Charsets.US_ASCII)
            val safeTitle = titleBytes.copyOf(32)
            buffer.put(safeTitle)

            // 0x20: Image identifier (15 bytes ASCII null-padded, strictly <= 14 chars)
            val prefix = entry.imagePrefix.take(MAX_IMAGE_PREFIX_LENGTH)
            val identBytes = prefix.toByteArray(Charsets.US_ASCII).copyOf(15)
            buffer.put(identBytes)

            // 0x2F: Chunks count (1 byte)
            buffer.put(entry.chunkCount.toByte())

            // 0x30: Media type (1 byte)
            buffer.put(entry.mediaType.byteValue)

            // 0x31..0x34: Reserved (4 bytes)
            buffer.putInt(0)

            // 0x35: USBExtreme magic byte (0x08)
            buffer.put(MAGIC_BYTE)

            // 0x36..0x3F: Padding (10 bytes)
            for (i in 0 until 10) {
                buffer.put(0.toByte())
            }

            buffer.position(startPos + RECORD_SIZE)
        }
    }
}

typealias UlMediaType = UlGameEntry.MediaType
