package com.vedeng.fileserver.proxy

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ProxyServer(
    private val context: Context,
    private val port: Int = 0,
    private val cacheManager: CacheManager
) : NanoHTTPD(port) {

    private val TAG = "ProxyServer"
    private val activeStreams = ConcurrentHashMap<String, ActiveStream>()
    private val sessionCounter = AtomicInteger(0)
    private var serverSocket: ServerSocket? = null

    data class ActiveStream(
        val sessionId: String,
        val sourceType: SourceType,
        val remotePath: String,
        var inputStream: InputStream? = null,
        var outputStream: OutputStream? = null,
        var socket: Socket? = null,
        var job: Job? = null,
        var isActive: Boolean = false
    )

    enum class SourceType {
        SMB, FTP, LOCAL
    }

    data class StreamRequest(
        val sourceType: SourceType,
        val remotePath: String,
        val host: String,
        val port: Int = 0,
        val username: String? = null,
        val password: String? = null,
        val extra: Map<String, String>? = null
    )

    companion object {
        private var instance: ProxyServer? = null

        fun getInstance(context: Context, cacheManager: CacheManager): ProxyServer {
            if (instance == null) {
                instance = ProxyServer(context, 0, cacheManager)
            }
            return instance!!
        }

        fun stopServer() {
            instance?.stop()
            instance = null
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        return try {
            when {
                uri.startsWith("/stream/") -> handleStreamRequest(session, uri)
                uri.startsWith("/file/") -> handleFileRequest(session, uri)
                uri.startsWith("/video/") -> handleVideoRequest(session, uri)
                uri.startsWith("/image/") -> handleImageRequest(session, uri)
                uri == "/status" -> handleStatus(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling request: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun handleStreamRequest(session: IHTTPSession, uri: String): Response {
        val pathParts = uri.removePrefix("/stream/").split("/")
        if (pathParts.size < 2) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        val sessionId = pathParts[0]
        val fileName = pathParts.drop(1).joinToString("/")
        val rangeHeader = session.headers["range"]

        val activeStream = activeStreams[sessionId]
        if (activeStream == null || !activeStream.isActive) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Stream not found")
        }

        val cachedFile = cacheManager.getCachedFile(activeStream.remotePath)
        if (cachedFile != null && cachedFile.exists()) {
            return serveLocalFile(session, cachedFile, fileName, rangeHeader)
        }

        return serveRemoteStream(session, activeStream, fileName, rangeHeader)
    }

    private fun handleFileRequest(session: IHTTPSession, uri: String): Response {
        val filePath = uri.removePrefix("/file/")
        val cachedFile = cacheManager.getCachedFile(filePath)

        if (cachedFile != null && cachedFile.exists()) {
            return serveLocalFile(session, cachedFile, cachedFile.name, null)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not cached")
    }

    private fun handleVideoRequest(session: IHTTPSession, uri: String): Response {
        val videoId = uri.removePrefix("/video/")
        val cachedFile = cacheManager.getCachedFile(videoId)

        if (cachedFile != null && cachedFile.exists()) {
            return serveVideoFile(session, cachedFile, videoId)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Video not cached")
    }

    private fun handleImageRequest(session: IHTTPSession, uri: String): Response {
        val imageId = uri.removePrefix("/image/")
        val cachedFile = cacheManager.getCachedFile(imageId)

        if (cachedFile != null && cachedFile.exists()) {
            return serveImageFile(session, cachedFile, imageId)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Image not cached")
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val status = buildString {
            append("Proxy Server Status\n")
            append("===================\n")
            append("Active streams: ${activeStreams.size}\n")
            append("Local URL: http://$localIp:$localPort\n")
            activeStreams.forEach { (id, stream) ->
                append("\nSession: $id\n")
                append("  Remote: ${stream.remotePath}\n")
                append("  Active: ${stream.isActive}\n")
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", status)
    }

    private fun serveLocalFile(
        session: IHTTPSession,
        file: File,
        fileName: String,
        rangeHeader: String?
    ): Response {
        val mimeType = getMimeType(fileName)
        val fileSize = file.length()

        if (rangeHeader != null) {
            val range = parseRange(rangeHeader, fileSize)
            val fis = FileInputStream(file)
            fis.skip(range.first)

            val limitedStream = object : InputStream() {
                private var remaining = range.second
                private val delegate = fis

                override fun read(): Int {
                    if (remaining <= 0) return -1
                    val b = delegate.read()
                    if (b != -1) remaining--
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) return -1
                    val toRead = minOf(len, remaining.toInt())
                    val read = delegate.read(b, off, toRead)
                    if (read > 0) remaining -= read
                    return read
                }

                override fun close() {
                    delegate.close()
                }
            }

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                limitedStream,
                range.second
            )
            response.addHeader("Content-Range", "bytes ${range.first}-${range.first + range.second - 1}/$fileSize")
            response.addHeader("Accept-Ranges", "bytes")
            return response
        }

        return newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            FileInputStream(file),
            fileSize
        )
    }

    private fun serveVideoFile(session: IHTTPSession, file: File, videoId: String): Response {
        val mimeType = getMimeType(file.name)
        val fileSize = file.length()
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null) {
            val range = parseRange(rangeHeader, fileSize)
            val fis = FileInputStream(file)
            fis.skip(range.first)

            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT,
                mimeType,
                LimitedInputStream(fis, range.second),
                range.second
            )
            response.addHeader("Content-Range", "bytes ${range.first}-${range.first + range.second - 1}/$fileSize")
            response.addHeader("Accept-Ranges", "bytes")
            response.addHeader("Access-Control-Allow-Origin", "*")
            return response
        }

        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            FileInputStream(file),
            fileSize
        )
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun serveImageFile(session: IHTTPSession, file: File, imageId: String): Response {
        val mimeType = getMimeType(file.name)
        return newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            FileInputStream(file),
            file.length()
        )
    }

    private fun serveRemoteStream(
        session: IHTTPSession,
        activeStream: ActiveStream,
        fileName: String,
        rangeHeader: String?
    ): Response {
        val inputStream = activeStream.inputStream ?: return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            "text/plain",
            "Stream unavailable"
        )

        val mimeType = getMimeType(fileName)

        return try {
            val buffer = ByteArray(8192)
            val outputStream = ByteArrayOutputStream()
            var totalRead = 0

            if (rangeHeader != null) {
                val skipBytes = rangeHeader.replace("bytes=", "").split("-").firstOrNull()?.toLongOrNull() ?: 0L
                if (skipBytes > 0) {
                    var skipped = 0L
                    while (skipped < skipBytes) {
                        val skip = inputStream.skip(skipBytes - skipped).toInt()
                        if (skip == 0) break
                        skipped += skip
                    }
                }
            }

            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                if (totalRead >= 1024 * 1024) {
                    break
                }
            }

            val data = outputStream.toByteArray()
            newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(data), data.size.toLong())
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Stream error: ${e.message}")
        }
    }

    private fun parseRange(rangeHeader: String, fileSize: Long): Pair<Long, Long> {
        return try {
            val range = rangeHeader.replace("bytes=", "").split("-")
            val start = range.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = range.getOrNull(1)?.toLongOrNull() ?: (fileSize - 1)
            Pair(start, end - start + 1)
        } catch (e: Exception) {
            Pair(0L, fileSize)
        }
    }

    fun registerStream(request: StreamRequest): String {
        val sessionId = "session_${sessionCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val activeStream = ActiveStream(
            sessionId = sessionId,
            sourceType = request.sourceType,
            remotePath = request.remotePath,
            isActive = true
        )
        activeStreams[sessionId] = activeStream
        return sessionId
    }

    fun getStreamUrl(sessionId: String, fileName: String = "stream"): String {
        return "http://$localIp:$localPort/stream/$sessionId/$fileName"
    }

    fun unregisterStream(sessionId: String) {
        activeStreams.remove(sessionId)?.let { stream ->
            stream.isActive = false
            stream.job?.cancel()
            try {
                stream.inputStream?.close()
                stream.outputStream?.close()
                stream.socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing stream: ${e.message}")
            }
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "m4v", "mkv", "avi", "mov", "wmv", "flv", "webm" -> "video/$extension"
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "image/$extension"
            "mp3", "wav", "aac", "flac", "ogg", "m4a" -> "audio/$extension"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    override fun start() {
        try {
            super.start()
            localIp = getLocalIpAddress()
            localPort = this.listeningPort
            Log.i(TAG, "Proxy server started on $localIp:$localPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server: ${e.message}")
        }
    }

    companion object {
        private var localIp: String = "127.0.0.1"
        private var localPort: Int = 8080

        fun getLocalUrl(path: String = "/"): String {
            return "http://$localIp:$localPort$path"
        }
    }

    override fun stop() {
        activeStreams.values.forEach { stream ->
            unregisterStream(stream.sessionId)
        }
        super.stop()
        Log.i(TAG, "Proxy server stopped")
    }

    private fun getLocalIpAddress(): String {
        return try {
            val runtime = Runtime.getRuntime()
            val process = runtime.exec("ipconfig")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val ip = Regex("IPv4.*: (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(line ?: "")?.groupValues?.get(1)
                if (ip != null && !ip.startsWith("127.")) {
                    return ip
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    inner class LimitedInputStream(
        private val inputStream: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var bytesRead = 0L

        override fun read(): Int {
            if (bytesRead >= maxBytes) return -1
            val b = inputStream.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= maxBytes) return -1
            val remaining = maxBytes - bytesRead
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = inputStream.read(b, off, toRead)
            if (read > 0) bytesRead += read
            return read
        }

        override fun close() {
            inputStream.close()
        }
    }
}
