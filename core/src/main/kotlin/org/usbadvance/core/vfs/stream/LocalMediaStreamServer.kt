package org.usbadvance.core.vfs.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.usbadvance.core.vfs.api.IVirtualFileSystem
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.min

/**
 * High-performance embedded HTTP/1.1 server running locally on 127.0.0.1.
 * Provides HTTP Range-Requests (HTTP 206 Partial Content) to allow external media
 * players (VLC, MX Player, Just Player, ExoPlayer) to stream and seek large media files
 * and disc images directly from USB OTG filesystems without loading them into memory.
 */
class LocalMediaStreamServer(
    private val vfs: IVirtualFileSystem,
    requestedPort: Int = 0 // 0 = bind to any available local ephemeral port
) : Closeable {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    val isRunning: Boolean
        get() = serverSocket != null && !(serverSocket?.isClosed ?: true)

    /**
     * Starts the HTTP streaming daemon.
     */
    fun start() {
        if (isRunning) return

        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = server

        serverJob = scope.launch {
            while (isActive && !server.isClosed) {
                try {
                    val clientSocket = server.accept()
                    scope.launch {
                        handleClient(clientSocket)
                    }
                } catch (e: Exception) {
                    if (server.isClosed) break
                }
            }
        }
    }

    /**
     * Generates a streaming URL that can be passed to external players or ExoPlayer.
     */
    fun getStreamUrl(vfsPath: String): String {
        val encodedPath = java.net.URLEncoder.encode(vfsPath, "UTF-8")
        return "http://127.0.0.1:$port/stream?path=$encodedPath"
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { s ->
            s.soTimeout = 15000 // 15s timeout
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = BufferedOutputStream(s.getOutputStream())

            val requestLine = reader.readLine() ?: return@withContext
            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext

            val method = parts[0].uppercase(Locale.ROOT)
            val uri = parts[1]

            var rangeHeader: String? = null
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
                line = reader.readLine()
            }

            if (method != "GET" && method != "HEAD") {
                sendResponse(out, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n\r\n")
                return@withContext
            }

            if (!uri.startsWith("/stream?path=")) {
                sendResponse(out, 404, "Not Found", "Content-Length: 0\r\n\r\n")
                return@withContext
            }

            val encodedPath = uri.substringAfter("/stream?path=")
            val path = URLDecoder.decode(encodedPath, "UTF-8")

            val entry = try {
                vfs.getEntry(path)
            } catch (e: Exception) {
                null
            }

            if (entry == null || entry.isDirectory) {
                sendResponse(out, 404, "Not Found", "Content-Length: 0\r\n\r\n")
                return@withContext
            }

            val fileSize = entry.sizeBytes
            val mimeType = getMimeType(entry.extension)

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                // Handle Partial Content Range Request (HTTP 206)
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                val rangeParts = rangeSpec.split("-")
                val start = rangeParts[0].toLongOrNull() ?: 0L
                val end = if (rangeParts.size > 1 && rangeParts[1].isNotEmpty()) {
                    rangeParts[1].toLongOrNull() ?: (fileSize - 1)
                } else {
                    fileSize - 1
                }

                if (start >= fileSize || start > end) {
                    sendResponse(out, 416, "Range Not Satisfiable", "Content-Range: bytes */$fileSize\r\n\r\n")
                    return@withContext
                }

                val contentLength = end - start + 1
                val headers = buildString {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Type: $mimeType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Range: bytes $start-$end/$fileSize\r\n")
                    append("Content-Length: $contentLength\r\n")
                    append("Connection: keep-alive\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.flush()

                if (method == "GET") {
                    streamFileData(path, start, contentLength, out)
                }
            } else {
                // Full File Stream (HTTP 200)
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $mimeType\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: $fileSize\r\n")
                    append("Connection: keep-alive\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.US_ASCII))
                out.flush()

                if (method == "GET") {
                    streamFileData(path, 0L, fileSize, out)
                }
            }
        }
    }

    private suspend fun streamFileData(
        filePath: String,
        startOffset: Long,
        totalBytesToSend: Long,
        out: BufferedOutputStream
    ) {
        val handle = try {
            vfs.openFile(filePath, writeMode = false)
        } catch (e: Exception) {
            return
        }

        handle.use { h ->
            // Use 128 KB buffer for optimal network pipe throughput
            val chunkSize = 128 * 1024
            val buffer = ByteBuffer.allocateDirect(chunkSize)
            var currentOffset = startOffset
            var remainingBytes = totalBytesToSend
            val transferArray = ByteArray(chunkSize)

            while (remainingBytes > 0) {
                val toRead = min(remainingBytes, chunkSize.toLong()).toInt()
                buffer.clear()
                buffer.limit(toRead)

                val read = h.read(currentOffset, buffer)
                if (read <= 0) break

                buffer.position(0)
                buffer.get(transferArray, 0, read)
                out.write(transferArray, 0, read)

                currentOffset += read
                remainingBytes -= read
            }
            out.flush()
        }
    }

    private fun sendResponse(out: BufferedOutputStream, statusCode: Int, statusText: String, headersAndBody: String) {
        val response = "HTTP/1.1 $statusCode $statusText\r\n$headersAndBody"
        out.write(response.toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun getMimeType(extension: String): String {
        return when (extension.lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "aac" -> "audio/aac"
            "iso" -> "application/x-iso9660-image"
            "bin", "img" -> "application/octet-stream"
            "pdf" -> "application/pdf"
            "txt", "log", "cnf", "cfg" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    override fun close() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        serverSocket = null
    }
}
