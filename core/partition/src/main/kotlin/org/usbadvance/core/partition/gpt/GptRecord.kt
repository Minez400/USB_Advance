package org.usbadvance.core.partition.gpt

import org.usbadvance.core.storage.model.FilesystemType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * GPT partition table entry structure (128 bytes).
 */
data class GptPartitionEntry(
    val typeGuid: UUID,
    val uniqueGuid: UUID,
    val startingLba: Long,
    val endingLba: Long,
    val attributes: Long = 0L,
    val name: String = ""
) {
    fun toFilesystemType(): FilesystemType? {
        return when (typeGuid) {
            BASIC_DATA_GUID -> FilesystemType.EXFAT // Can represent exFAT, FAT32, or NTFS
            LINUX_DATA_GUID -> FilesystemType.EXT4
            else -> null
        }
    }

    fun writeTo(buffer: ByteBuffer) {
        val originalOrder = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        try {
            writeUuid(buffer, typeGuid)
            writeUuid(buffer, uniqueGuid)
            buffer.putLong(startingLba)
            buffer.putLong(endingLba)
            buffer.putLong(attributes)

            // Write partition label in UTF-16LE (maximum 36 characters = 72 bytes)
            val charArray = name.toCharArray()
            for (i in 0 until 36) {
                if (i < charArray.size) {
                    buffer.putChar(charArray[i])
                } else {
                    buffer.putShort(0.toShort())
                }
            }
        } finally {
            buffer.order(originalOrder)
        }
    }

    companion object {
        // GUID for Basic Data Partition (FAT, exFAT, NTFS): EBD0A0A2-B9E5-4433-87C0-68B6B72699C7
        val BASIC_DATA_GUID: UUID = UUID.fromString("ebd0a0a2-b9e5-4433-87c0-68b6b72699c7")

        // GUID for Linux Native Filesystem Partition (ext4): 0FC63DAF-8483-4772-8E79-3D69D8477DE4
        val LINUX_DATA_GUID: UUID = UUID.fromString("0fc63daf-8483-4772-8e79-3d69d8477de4")

        fun parseFrom(buffer: ByteBuffer): GptPartitionEntry? {
            val startPos = buffer.position()
            val originalOrder = buffer.order()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            try {
                val typeGuid = readUuid(buffer)
                if (typeGuid == UUID(0L, 0L)) {
                    buffer.position(startPos + 128)
                    return null // Empty partition entry slot
                }

                val uniqueGuid = readUuid(buffer)
                val startingLba = buffer.long
                val endingLba = buffer.long
                val attributes = buffer.long

                val nameChars = CharArray(36)
                for (i in 0 until 36) {
                    nameChars[i] = buffer.char
                }
                val name = String(nameChars).trimEnd('\u0000')

                return GptPartitionEntry(
                    typeGuid = typeGuid,
                    uniqueGuid = uniqueGuid,
                    startingLba = startingLba,
                    endingLba = endingLba,
                    attributes = attributes,
                    name = name
                )
            } finally {
                buffer.order(originalOrder)
            }
        }

        private fun writeUuid(buffer: ByteBuffer, uuid: UUID) {
            // In UEFI GPT, the first three fields of the GUID are little-endian (mixed-endian UUID)
            val mostSig = uuid.mostSignificantBits
            val timeLow = (mostSig ushr 32).toInt()
            val timeMid = (mostSig ushr 16).toShort()
            val timeHiAndVersion = mostSig.toShort()

            buffer.putInt(timeLow)
            buffer.putShort(timeMid)
            buffer.putShort(timeHiAndVersion)

            val leastSig = uuid.leastSignificantBits
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putLong(leastSig)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
        }

        private fun readUuid(buffer: ByteBuffer): UUID {
            val timeLow = buffer.int.toLong() and 0xFFFFFFFFL
            val timeMid = buffer.short.toLong() and 0xFFFFL
            val timeHiAndVersion = buffer.short.toLong() and 0xFFFFL

            val mostSig = (timeLow shl 32) or (timeMid shl 16) or timeHiAndVersion

            buffer.order(ByteOrder.BIG_ENDIAN)
            val leastSig = buffer.long
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            return UUID(mostSig, leastSig)
        }
    }
}
