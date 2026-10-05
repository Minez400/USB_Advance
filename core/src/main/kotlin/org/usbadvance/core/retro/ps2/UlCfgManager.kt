package org.usbadvance.core.retro.ps2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.regex.Pattern

/**
 * Manager for PS2 Open PS2 Loader (OPL) and USBUtil `ul.cfg` files.
 * Handles game cataloging, ISO 9660 SYSTEM.CNF parsing, and chunking
 * ISOs > 4 GB into 1 GiB segments for FAT32 media.
 */
class UlCfgManager {

    companion object {
        const val UL_CFG_NAME = "ul.cfg"
        const val CHUNK_SIZE_BYTES = 1073741824L // 1 GiB per split segment
        private val SYSTEM_CNF_PATTERN = Pattern.compile("BOOT2\\s*=\\s*cdrom0:[\\\\/]([A-Z]{4}_[0-9]{3}\\.[0-9]{2})", Pattern.CASE_INSENSITIVE)
    }

    /**
     * Reads all registered game entries from [ulCfgFile].
     */
    suspend fun readCatalog(ulCfgFile: File): List<UlGameEntry> = withContext(Dispatchers.IO) {
        if (!ulCfgFile.exists() || ulCfgFile.length() < UlGameEntry.RECORD_SIZE) {
            return@withContext emptyList()
        }

        val entries = mutableListOf<UlGameEntry>()
        val bytes = ulCfgFile.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        while (buffer.remaining() >= UlGameEntry.RECORD_SIZE) {
            val entry = UlGameEntry.parseFrom(buffer)
            if (entry != null && entry.title.isNotBlank()) {
                entries.add(entry)
            }
        }
        return@withContext entries
    }

    /**
     * Reads all registered game entries from `ul.cfg` on a mounted virtual filesystem.
     */
    suspend fun readCatalogFromVfs(vfs: IVirtualFileSystem): List<UlGameEntry> = withContext(Dispatchers.IO) {
        val entry = try {
            vfs.getEntry("/$UL_CFG_NAME")
        } catch (_: Exception) {
            null
        } ?: return@withContext emptyList()

        if (entry.sizeBytes < UlGameEntry.RECORD_SIZE) return@withContext emptyList()

        val handle = try {
            vfs.openFile("/$UL_CFG_NAME", writeMode = false)
        } catch (_: Exception) {
            return@withContext emptyList()
        }

        handle.use { h ->
            val totalBytes = entry.sizeBytes.toInt()
            val buffer = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
            val read = h.read(0L, buffer)
            if (read < UlGameEntry.RECORD_SIZE) return@withContext emptyList()

            buffer.position(0)
            buffer.limit(read)

            val games = mutableListOf<UlGameEntry>()
            while (buffer.remaining() >= UlGameEntry.RECORD_SIZE) {
                val game = UlGameEntry.parseFrom(buffer)
                if (game != null && game.title.isNotBlank()) {
                    games.add(game)
                }
            }
            games
        }
    }

    /**
     * Saves or replaces [entries] in [ulCfgFile].
     */
    suspend fun writeCatalog(ulCfgFile: File, entries: List<UlGameEntry>) = withContext(Dispatchers.IO) {
        val totalBytes = entries.size * UlGameEntry.RECORD_SIZE
        val buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)

        for (entry in entries) {
            UlGameEntry.writeTo(buffer, entry)
        }

        ulCfgFile.writeBytes(buffer.array())
    }

    /**
     * Locates and parses SYSTEM.CNF inside a PS2 ISO file to extract the boot ELF ID (e.g. "SLUS_200.62").
     * First attempts ISO 9660 filesystem parsing; falls back to streaming chunk search.
     */
    suspend fun extractGameIdFromIso(isoFile: File): String? = withContext(Dispatchers.IO) {
        if (!isoFile.exists() || isoFile.length() == 0L) return@withContext null

        return@withContext try {
            RandomAccessFile(isoFile, "r").use { raf ->
                // 1. Try ISO 9660 PVD extraction (Sector 16 at offset 32768)
                val pvdOffset = 16L * 2048L
                if (raf.length() > pvdOffset + 2048) {
                    raf.seek(pvdOffset)
                    val pvd = ByteArray(2048)
                    raf.readFully(pvd)

                    val pvdBuf = ByteBuffer.wrap(pvd).order(ByteOrder.LITTLE_ENDIAN)
                    // Check standard ISO 9660 identifier "CD001" at offset 1
                    val magic = String(pvd, 1, 5, Charsets.US_ASCII)
                    if (magic == "CD001") {
                        // Root Directory Record is at offset 156
                        val rootDirLba = pvdBuf.getInt(156 + 2).toLong() and 0xFFFFFFFFL
                        val rootDirLen = pvdBuf.getInt(156 + 10).toLong() and 0xFFFFFFFFL

                        if (rootDirLba > 0 && rootDirLen in 1..65536) {
                            raf.seek(rootDirLba * 2048L)
                            val rootDirBytes = ByteArray(rootDirLen.toInt())
                            raf.readFully(rootDirBytes)
                            val rootBuf = ByteBuffer.wrap(rootDirBytes).order(ByteOrder.LITTLE_ENDIAN)

                            while (rootBuf.hasRemaining()) {
                                val recordLen = rootBuf.get().toInt() and 0xFF
                                if (recordLen == 0) break // End of records in sector
                                val recordStart = rootBuf.position() - 1

                                if (recordLen >= 33 && recordStart + recordLen <= rootDirBytes.size) {
                                    val fileLba = rootBuf.getInt(recordStart + 2).toLong() and 0xFFFFFFFFL
                                    val fileLen = rootBuf.getInt(recordStart + 10).toLong() and 0xFFFFFFFFL
                                    val nameLen = rootBuf.get(recordStart + 32).toInt() and 0xFF
                                    val nameStr = String(rootDirBytes, recordStart + 33, nameLen, Charsets.US_ASCII)

                                    if (nameStr.startsWith("SYSTEM.CNF", ignoreCase = true) && fileLen in 1..8192) {
                                        raf.seek(fileLba * 2048L)
                                        val cnfBytes = ByteArray(fileLen.toInt())
                                        raf.readFully(cnfBytes)
                                        val cnfContent = String(cnfBytes, Charsets.US_ASCII)
                                        val matcher = SYSTEM_CNF_PATTERN.matcher(cnfContent)
                                        if (matcher.find()) {
                                            matcher.group(1)?.uppercase()?.let { return@use it }
                                        }
                                    }
                                }
                                rootBuf.position(recordStart + recordLen)
                            }
                        }
                    }
                }

                // 2. Fallback: Streaming scan in 64 KB blocks over the first 4 MB
                val maxScan = minOf(raf.length(), 4L * 1024 * 1024)
                val chunk = ByteArray(65536)
                var scanned = 0L
                raf.seek(0)

                while (scanned < maxScan) {
                    val toRead = minOf(chunk.size.toLong(), maxScan - scanned).toInt()
                    val read = raf.read(chunk, 0, toRead)
                    if (read <= 0) break

                    val text = String(chunk, 0, read, Charsets.US_ASCII)
                    val matcher = SYSTEM_CNF_PATTERN.matcher(text)
                    if (matcher.find()) {
                        matcher.group(1)?.uppercase()?.let { return@use it }
                    }
                    scanned += read
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Splits a PS2 ISO image into 1 GiB chunks formatted for USBUtil / OPL on FAT32 media
     * and updates or creates the `ul.cfg` in [targetDirectory].
     */
    suspend fun splitIso(
        sourceIso: File,
        targetDirectory: File,
        customTitle: String? = null,
        progressCallback: suspend (progressPct: Float, stageMessage: String) -> Unit
    ): UlGameEntry = withContext(Dispatchers.IO) {
        require(sourceIso.exists()) { "Source ISO does not exist: ${sourceIso.absolutePath}" }
        if (!targetDirectory.exists()) targetDirectory.mkdirs()

        val totalLength = sourceIso.length()
        val detectedId = extractGameIdFromIso(sourceIso) ?: "SLUS_000.00"
        val gameTitle = (customTitle ?: sourceIso.nameWithoutExtension).take(31)

        val totalChunks = ((totalLength + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt()
        val mediaType = if (totalLength > 700L * 1024 * 1024) UlGameEntry.MediaType.DVD else UlGameEntry.MediaType.CD

        val imagePrefix = UlGameEntry.buildPrefix(detectedId)
        val gameEntry = UlGameEntry(
            title = gameTitle,
            imagePrefix = imagePrefix,
            chunkCount = totalChunks,
            mediaType = mediaType
        )

        val bufferSize = 256 * 1024 // 256 KB streaming buffer
        val buffer = ByteArray(bufferSize)
        var totalBytesRead = 0L

        RandomAccessFile(sourceIso, "r").use { reader ->
            for (chunkIdx in 0 until totalChunks) {
                val chunkFile = File(targetDirectory, gameEntry.getChunkFileName(chunkIdx))
                var chunkBytesWritten = 0L
                val maxChunkBytes = minOf(CHUNK_SIZE_BYTES, totalLength - totalBytesRead)

                chunkFile.outputStream().buffered(bufferSize).use { writer ->
                    while (chunkBytesWritten < maxChunkBytes) {
                        val toRead = minOf(buffer.size.toLong(), maxChunkBytes - chunkBytesWritten).toInt()
                        val read = reader.read(buffer, 0, toRead)
                        if (read <= 0) break

                        writer.write(buffer, 0, read)
                        chunkBytesWritten += read
                        totalBytesRead += read

                        val pct = (totalBytesRead.toFloat() / totalLength.toFloat()) * 100f
                        progressCallback(pct, "Gravando parte ${chunkIdx + 1}/$totalChunks (${totalBytesRead / (1024 * 1024)} MB / ${totalLength / (1024 * 1024)} MB)")
                    }
                }
            }
        }

        // Register game entry into ul.cfg
        val ulCfgFile = File(targetDirectory, UL_CFG_NAME)
        val catalog = readCatalog(ulCfgFile).toMutableList()
        catalog.removeAll { it.imagePrefix.equals(imagePrefix, ignoreCase = true) }
        catalog.add(gameEntry)
        writeCatalog(ulCfgFile, catalog)

        progressCallback(100f, "Concluído com sucesso!")
        return@withContext gameEntry
    }
}
