package com.example.core.network

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstraction for a registered local media or subtitle resource so the HTTP server
 * streams directly from SAF ContentResolver descriptors OR local prepared files
 * without ever loading large videos into RAM.
 */
interface StreamableMediaSource {
    val contentLength: Long
    val mimeType: String
    val displayName: String

    fun openStreamAt(offsetBytes: Long): InputStream
}

class FileMediaSource(
    private val file: File,
    override val mimeType: String,
    override val displayName: String = file.name
) : StreamableMediaSource {

    override val contentLength: Long
        get() = file.length()

    override fun openStreamAt(offsetBytes: Long): InputStream {
        val fis = FileInputStream(file)
        if (offsetBytes > 0L) {
            fis.channel.position(offsetBytes)
        }
        return BufferedInputStream(fis, STREAM_BUFFER_BYTES)
    }

    companion object {
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}

class InMemoryTextSource(
    text: String,
    override val mimeType: String,
    override val displayName: String
) : StreamableMediaSource {
    private val bytes = text.toByteArray(Charsets.UTF_8)
    override val contentLength: Long = bytes.size.toLong()

    override fun openStreamAt(offsetBytes: Long): InputStream {
        val start = offsetBytes.toInt().coerceIn(0, bytes.size)
        return ByteArrayInputStream(bytes, start, bytes.size - start)
    }
}

class ContentUriMediaSource(
    private val context: Context,
    private val uri: Uri,
    override val mimeType: String,
    override val displayName: String,
    private val knownSizeBytes: Long
) : StreamableMediaSource {

    override val contentLength: Long by lazy {
        if (knownSizeBytes > 0L) {
            knownSizeBytes
        } else {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length >= 0L) afd.length else 0L
            } ?: 0L
        }
    }

    override fun openStreamAt(offsetBytes: Long): InputStream {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open content URI for streaming: $displayName")
        val fis = FileInputStream(pfd.fileDescriptor)
        if (offsetBytes > 0L) {
            try {
                fis.channel.position(offsetBytes)
            } catch (_: Exception) {
                var remaining = offsetBytes
                while (remaining > 0L) {
                    val skipped = fis.skip(remaining)
                    if (skipped <= 0L) break
                    remaining -= skipped
                }
            }
        }
        return object : BufferedInputStream(fis, FileMediaSource.STREAM_BUFFER_BYTES) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    runCatching { pfd.close() }
                }
            }
        }
    }
}

data class ByteRangeSpec(
    val start: Long,
    val end: Long
) {
    val length: Long get() = (end - start) + 1L
}

data class RegisteredStream(
    val token: String,
    val url: String,
    val mimeType: String,
    val contentLength: Long
)

data class HealthCheckResult(
    val healthy: Boolean,
    val latencyMs: Long,
    val statusLine: String
)

/**
 * Production-grade embedded HTTP/1.1 streaming server for Chromecast local playback.
 *
 * Exposes:
 * - `/stream/<token>`: Original & converted progressive video/audio files
 * - `/subtitle/<token>.<ext>`: External & converted WebVTT / TTML / Timed Bitmap JSON subtitles
 * - `/hls/<token>/<relativeAsset>`: Adaptive HLS master playlists, media playlists, fMP4 init/segments
 *
 * Security & Streaming Guarantees:
 * - Binds explicitly to the provided LAN IPv4 address (never exposes public internet routes).
 * - Uses 192-bit cryptographically random access tokens per registered stream/subtitle/HLS directory.
 * - Rejects directory traversal and unauthorized paths with 403 Forbidden.
 * - Full CORS + OPTIONS preflight headers for Cast Web Receiver XHR/Fetch subtitle & HLS loading.
 * - Supports HTTP GET, HEAD, OPTIONS, and RFC 7233 Range (206 Partial Content) seeking.
 * - Uses 64 KB buffered streaming chunks (zero full-file RAM allocation).
 */
class LocalMediaHttpServer {

    private val secureRandom = SecureRandom()
    private val registeredSources = ConcurrentHashMap<String, StreamableMediaSource>()
    private val registeredSubtitles = ConcurrentHashMap<String, StreamableMediaSource>()
    private val registeredHlsDirs = ConcurrentHashMap<String, File>()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundHost: String? = null

    @Volatile
    private var serverScope: CoroutineScope? = null

    @Volatile
    private var acceptJob: Job? = null

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    val listeningPort: Int?
        get() = serverSocket?.takeIf { !it.isClosed }?.localPort

    val listeningHost: String?
        get() = boundHost

    val activeStreamCount: Int
        get() = registeredSources.size + registeredSubtitles.size + registeredHlsDirs.size

    @Synchronized
    fun start(bindAddress: String, preferredPort: Int = 0): Int {
        require(NetworkModeManager.isPrivateLanOrLoopbackHost(bindAddress)) {
            "Refusing to bind local media server to non-LAN address: $bindAddress"
        }
        if (isRunning && boundHost == bindAddress) {
            return serverSocket!!.localPort
        }
        stop()
        return bindSocketInternal(bindAddress, preferredPort)
    }

    /**
     * Re-binds the listening socket to a new hotspot or Wi-Fi IP address while PRESERVING
     * all registered media, subtitle, and HLS tokens so active casting can recover seamlessly
     * across IP address or network interface transitions without losing stream registrations.
     */
    @Synchronized
    fun rebindIfNeeded(newBindAddress: String, preferredPort: Int = 0): Pair<Boolean, Int> {
        require(NetworkModeManager.isPrivateLanOrLoopbackHost(newBindAddress)) {
            "Refusing to bind local media server to non-LAN address: $newBindAddress"
        }
        if (isRunning && boundHost == newBindAddress) {
            return false to serverSocket!!.localPort
        }
        // Close socket & accept loop ONLY — keep registeredSources, registeredSubtitles, registeredHlsDirs intact!
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundHost = null
        serverScope?.cancel()
        serverScope = null

        val port = bindSocketInternal(newBindAddress, preferredPort)
        return true to port
    }

    fun buildUrlForMediaToken(token: String): String? {
        val host = boundHost ?: return null
        val port = listeningPort ?: return null
        if (!registeredSources.containsKey(token)) return null
        return "http://$host:$port/stream/$token"
    }

    fun buildUrlForSubtitleToken(tokenKey: String): String? {
        val host = boundHost ?: return null
        val port = listeningPort ?: return null
        if (!registeredSubtitles.containsKey(tokenKey)) return null
        return "http://$host:$port/subtitle/$tokenKey"
    }

    private fun bindSocketInternal(bindAddress: String, preferredPort: Int): Int {
        val inetAddress = InetAddress.getByName(bindAddress)
        val socket = ServerSocket().apply {
            reuseAddress = true
            try {
                bind(InetSocketAddress(inetAddress, preferredPort), 32)
            } catch (_: Exception) {
                bind(InetSocketAddress(inetAddress, 0), 32)
            }
        }

        serverSocket = socket
        boundHost = bindAddress

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope
        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    // Keep accepting remaining connections unless socket closed
                }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        registeredSources.clear()
        registeredSubtitles.clear()
        registeredHlsDirs.clear()
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundHost = null
        serverScope?.cancel()
        serverScope = null
    }

    fun registerMedia(source: StreamableMediaSource): RegisteredStream {
        val host = boundHost ?: throw IllegalStateException("HTTP server is not started")
        val port = listeningPort ?: throw IllegalStateException("HTTP server has no active port")
        val token = generateSecureToken()
        registeredSources[token] = source
        val url = "http://$host:$port/stream/$token"
        return RegisteredStream(
            token = token,
            url = url,
            mimeType = source.mimeType,
            contentLength = source.contentLength
        )
    }

    fun registerSubtitle(source: StreamableMediaSource, extension: String = "vtt"): RegisteredStream {
        val host = boundHost ?: throw IllegalStateException("HTTP server is not started")
        val port = listeningPort ?: throw IllegalStateException("HTTP server has no active port")
        val safeExt = extension.lowercase(Locale.US).filter { it in 'a'..'z' || it in '0'..'9' }.ifBlank { "vtt" }
        val token = generateSecureToken()
        val key = "$token.$safeExt"
        registeredSubtitles[key] = source
        val url = "http://$host:$port/subtitle/$key"
        return RegisteredStream(
            token = key,
            url = url,
            mimeType = source.mimeType,
            contentLength = source.contentLength
        )
    }

    /**
     * Registers a scoped directory containing an adaptive HLS master playlist (`master.m3u8`),
     * audio/video media playlists, and fMP4/TS segments under a random 192-bit session token.
     */
    fun registerHlsDirectory(directory: File, masterPlaylistName: String = "master.m3u8"): RegisteredStream {
        val host = boundHost ?: throw IllegalStateException("HTTP server is not started")
        val port = listeningPort ?: throw IllegalStateException("HTTP server has no active port")
        require(directory.isDirectory) { "HLS root must be a valid directory." }
        val token = generateSecureToken()
        registeredHlsDirs[token] = directory.canonicalFile
        val url = "http://$host:$port/hls/$token/$masterPlaylistName"
        val masterFile = File(directory, masterPlaylistName)
        return RegisteredStream(
            token = token,
            url = url,
            mimeType = "application/vnd.apple.mpegurl",
            contentLength = if (masterFile.exists()) masterFile.length() else 0L
        )
    }

    fun unregisterMedia(token: String) {
        registeredSources.remove(token)
    }

    fun unregisterSubtitle(token: String) {
        registeredSubtitles.remove(token)
    }

    fun unregisterHlsDirectory(token: String) {
        registeredHlsDirs.remove(token)
    }

    fun clearStreams() {
        registeredSources.clear()
        registeredSubtitles.clear()
        registeredHlsDirs.clear()
    }

    suspend fun runSelfHealthCheck(): HealthCheckResult = withContext(Dispatchers.IO) {
        val host = boundHost ?: return@withContext HealthCheckResult(false, 0L, "Server stopped")
        val port = listeningPort ?: return@withContext HealthCheckResult(false, 0L, "No port bound")
        val startNs = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1500)
                socket.soTimeout = 1500
                val out = socket.getOutputStream()
                out.write("HEAD /health HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val statusLine = reader.readLine() ?: "Empty response"
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                HealthCheckResult(
                    healthy = statusLine.contains("200"),
                    latencyMs = elapsedMs,
                    statusLine = statusLine
                )
            }
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            HealthCheckResult(false, elapsedMs, e.message ?: "Connection failed")
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            client.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
            val output = BufferedOutputStream(client.getOutputStream(), STREAM_CHUNK_BYTES)

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) {
                writeSimpleResponse(output, "400 Bad Request", "Invalid HTTP request")
                return
            }

            val method = parts[0].uppercase(Locale.US)
            val rawPath = parts[1].substringBefore("?")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase(Locale.US)
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            // CORS preflight support required by Google Cast Web Receiver XHR/Fetch
            if (method == "OPTIONS") {
                writeHeaders(
                    output = output,
                    status = "204 No Content",
                    contentType = "text/plain",
                    contentLength = 0L,
                    extraHeaders = listOf("Access-Control-Max-Age: 86400")
                )
                output.flush()
                return
            }

            if (method != "GET" && method != "HEAD") {
                writeSimpleResponse(output, "405 Method Not Allowed", "Only GET, HEAD, and OPTIONS are supported")
                return
            }

            // Security: strict path validation & directory traversal rejection
            if (!isSafeRequestPath(rawPath)) {
                writeSimpleResponse(output, "403 Forbidden", "Invalid or unauthorized path")
                return
            }

            if (rawPath == "/health") {
                val body = "OK".toByteArray()
                writeHeaders(
                    output = output,
                    status = "200 OK",
                    contentType = "text/plain; charset=utf-8",
                    contentLength = body.size.toLong(),
                    extraHeaders = emptyList()
                )
                if (method == "GET") {
                    output.write(body)
                }
                output.flush()
                return
            }

            val source: StreamableMediaSource? = when {
                rawPath.startsWith("/stream/") -> {
                    val token = rawPath.removePrefix("/stream/")
                    if (!TOKEN_REGEX.matches(token)) {
                        writeSimpleResponse(output, "403 Forbidden", "Malformed stream token")
                        return
                    }
                    registeredSources[token]
                }

                rawPath.startsWith("/subtitle/") -> {
                    val key = rawPath.removePrefix("/subtitle/")
                    if (!SUBTITLE_KEY_REGEX.matches(key)) {
                        writeSimpleResponse(output, "403 Forbidden", "Malformed subtitle token")
                        return
                    }
                    registeredSubtitles[key]
                }

                rawPath.startsWith("/hls/") -> {
                    val remainder = rawPath.removePrefix("/hls/")
                    val token = remainder.substringBefore("/")
                    val relPath = remainder.substringAfter("/", "")
                    if (!TOKEN_REGEX.matches(token) || relPath.isBlank() || !HLS_ASSET_REGEX.matches(relPath)) {
                        writeSimpleResponse(output, "403 Forbidden", "Invalid HLS asset path")
                        return
                    }
                    val rootDir = registeredHlsDirs[token]
                    if (rootDir == null) {
                        writeSimpleResponse(output, "403 Forbidden", "Expired or unknown HLS session")
                        return
                    }
                    val targetFile = File(rootDir, relPath).canonicalFile
                    if (!targetFile.path.startsWith(rootDir.path + File.separator) || !targetFile.isFile) {
                        writeSimpleResponse(output, "404 Not Found", "HLS segment not found")
                        return
                    }
                    FileMediaSource(targetFile, inferHlsMimeType(targetFile.name), targetFile.name)
                }

                else -> {
                    writeSimpleResponse(output, "403 Forbidden", "Unauthorized endpoint")
                    return
                }
            }

            if (source == null) {
                writeSimpleResponse(output, "403 Forbidden", "Expired or unknown stream token")
                return
            }

            val totalLength = source.contentLength
            if (totalLength <= 0L) {
                writeSimpleResponse(output, "404 Not Found", "Media source unavailable")
                return
            }

            val rangeHeader = headers["range"]
            if (rangeHeader != null) {
                val range = parseRangeHeader(rangeHeader, totalLength)
                if (range == null) {
                    writeHeaders(
                        output = output,
                        status = "416 Range Not Satisfiable",
                        contentType = "text/plain",
                        contentLength = 0L,
                        extraHeaders = listOf("Content-Range: bytes */$totalLength")
                    )
                    output.flush()
                    return
                }

                writeHeaders(
                    output = output,
                    status = "206 Partial Content",
                    contentType = source.mimeType,
                    contentLength = range.length,
                    extraHeaders = listOf(
                        "Accept-Ranges: bytes",
                        "Content-Range: bytes ${range.start}-${range.end}/$totalLength"
                    )
                )
                if (method == "GET") {
                    streamBytes(source, range.start, range.length, output)
                }
                output.flush()
            } else {
                writeHeaders(
                    output = output,
                    status = "200 OK",
                    contentType = source.mimeType,
                    contentLength = totalLength,
                    extraHeaders = listOf("Accept-Ranges: bytes")
                )
                if (method == "GET") {
                    streamBytes(source, 0L, totalLength, output)
                }
                output.flush()
            }
        }
    }

    private fun streamBytes(
        source: StreamableMediaSource,
        offset: Long,
        length: Long,
        output: OutputStream
    ) {
        source.openStreamAt(offset).use { input ->
            val buffer = ByteArray(STREAM_CHUNK_BYTES)
            var remaining = length
            while (remaining > 0L) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val bytesRead = input.read(buffer, 0, toRead)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                remaining -= bytesRead
            }
            output.flush()
        }
    }

    private fun writeHeaders(
        output: OutputStream,
        status: String,
        contentType: String,
        contentLength: Long,
        extraHeaders: List<String>
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Range, Content-Type, Accept, Origin\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
        sb.append("Connection: close\r\n")
        extraHeaders.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeSimpleResponse(output: OutputStream, status: String, bodyText: String) {
        val body = bodyText.toByteArray(Charsets.UTF_8)
        writeHeaders(
            output = output,
            status = status,
            contentType = "text/plain; charset=utf-8",
            contentLength = body.size.toLong(),
            extraHeaders = emptyList()
        )
        output.write(body)
        output.flush()
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(24) // 192 bits of entropy
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val STREAM_CHUNK_BYTES = 64 * 1024
        private val TOKEN_REGEX = Regex("^[a-f0-9]{48}$")
        private val SUBTITLE_KEY_REGEX = Regex("^[a-f0-9]{48}\\.[a-z0-9]{2,6}$")
        private val HLS_ASSET_REGEX = Regex("^[a-zA-Z0-9._/-]+$")

        fun inferHlsMimeType(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase(Locale.US)) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "mpd" -> "application/dash+xml"
            "m4s", "mp4" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "ts" -> "video/mp2t"
            "vtt" -> "text/vtt; charset=utf-8"
            "ttml", "xml" -> "application/ttml+xml; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            else -> "application/octet-stream"
        }

        fun isSafeRequestPath(path: String): Boolean {
            if (!path.startsWith("/")) return false
            val lower = path.lowercase(Locale.US)
            if (lower.contains("..") || lower.contains("%2e") || lower.contains("\\") ||
                lower.contains("%2f") || lower.contains("\u0000")
            ) {
                return false
            }
            return path == "/health" ||
                path.startsWith("/stream/") ||
                path.startsWith("/subtitle/") ||
                path.startsWith("/hls/")
        }

        fun parseRangeHeader(rangeHeader: String, totalLength: Long): ByteRangeSpec? {
            if (totalLength <= 0L) return null
            val trimmed = rangeHeader.trim()
            if (!trimmed.startsWith("bytes=", ignoreCase = true)) return null
            val rangePart = trimmed.substring(6).substringBefore(",").trim()
            val dashIndex = rangePart.indexOf('-')
            if (dashIndex < 0) return null

            val startStr = rangePart.substring(0, dashIndex).trim()
            val endStr = rangePart.substring(dashIndex + 1).trim()

            return if (startStr.isEmpty()) {
                val suffix = endStr.toLongOrNull() ?: return null
                if (suffix <= 0L) return null
                val start = (totalLength - suffix).coerceAtLeast(0L)
                ByteRangeSpec(start = start, end = totalLength - 1L)
            } else {
                val start = startStr.toLongOrNull() ?: return null
                if (start < 0L || start >= totalLength) return null
                val end = if (endStr.isEmpty()) {
                    totalLength - 1L
                } else {
                    (endStr.toLongOrNull() ?: return null).coerceAtMost(totalLength - 1L)
                }
                if (end < start) return null
                ByteRangeSpec(start = start, end = end)
            }
        }
    }
}
