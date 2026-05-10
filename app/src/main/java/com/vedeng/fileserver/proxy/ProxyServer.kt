package com.vedeng.fileserver.proxy

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ProxyServer(
    private val context: Context,
    port: Int = 0,
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
        var thread: Thread? = null,
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
        private var localIp: String = "127.0.0.1"
        private var localPort: Int = 8080

        fun getInstance(context: Context, cacheManager: CacheManager): ProxyServer {
            if (instance == null) {
                instance = ProxyServer(context, 0, cacheManager)
            }
            return instance!!
        }

        fun getLocalUrl(path: String = "/"): String {
            return "http://$localIp:$localPort$path"
        }

        fun getLocalPortValue(): Int = localPort

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
            return serveFile(session, cachedFile, fileName)
        }

        val inputStream = activeStream.inputStream ?: return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not available"
        )

        val buffer = ByteArray(8192)
        val outputStream = ByteArrayOutputStream()

        try {
            val skipBytes = if (rangeHeader != null) {
                val range = parseRange(rangeHeader)
                if (range != null) {
                    inputStream.skip(range.first)
                    range.first
                } else 0L
            } else 0L

            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            val data = outputStream.toByteArray()
            val mimeType = getMimeType(fileName)

            return newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(data), data.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error streaming file: ${e.message}")
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Stream error")
        }
    }

    private fun handleFileRequest(session: IHTTPSession, uri: String): Response {
        val pathParts = uri.removePrefix("/file/").split("/")
        if (pathParts.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        val remotePath = URLDecoder.decode(pathParts.joinToString("/"), "UTF-8")
        val cachedFile = cacheManager.getCachedFile(remotePath)

        if (cachedFile != null && cachedFile.exists()) {
            return serveFile(session, cachedFile, cachedFile.name)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
    }

    private fun handleVideoRequest(session: IHTTPSession, uri: String): Response {
        val sessionId = uri.removePrefix("/video/").split("/").firstOrNull() ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "text/plain", "Invalid request"
        )

        val activeStream = activeStreams[sessionId] ?: return newFixedLengthResponse(
            Response.Status.NOT_FOUND, "text/plain", "Stream not found"
        )

        val inputStream = activeStream.inputStream ?: return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Stream not available"
        )

        val rangeHeader = session.headers["range"]
        val mimeType = "video/*"

        return try {
            if (rangeHeader != null) {
                val range = parseRange(rangeHeader)
                if (range != null) {
                    inputStream.skip(range.first)
                    val buffer = ByteArray(range.second.toInt())
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        val response = newFixedLengthResponse(
                            Response.Status.PARTIAL_CONTENT,
                            mimeType,
                            ByteArrayInputStream(buffer, 0, bytesRead),
                            bytesRead.toLong()
                        )
                        response.addHeader("Content-Range", "bytes ${range.first}-${range.first + bytesRead - 1}/*")
                        response.addHeader("Accept-Ranges", "bytes")
                        return response
                    }
                }
            }

            val buffer = ByteArray(1024 * 1024)
            val outputStream = ByteArrayOutputStream()
            var totalBytes = 0L

            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }

            val data = outputStream.toByteArray()
            newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(data), data.size.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Error handling video request: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "Video error")
        }
    }

    private fun handleImageRequest(session: IHTTPSession, uri: String): Response {
        val pathParts = uri.removePrefix("/image/").split("/")
        if (pathParts.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid request")
        }

        val remotePath = URLDecoder.decode(pathParts.joinToString("/"), "UTF-8")
        val cachedFile = cacheManager.getCachedFile(remotePath)

        if (cachedFile != null && cachedFile.exists()) {
            return serveFile(session, cachedFile, cachedFile.name)
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Image not found")
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val status = StringBuilder()
        status.append("ProxyServer Status\n")
        status.append("==================\n")
        status.append("Active streams: ${activeStreams.size}\n")
        status.append("Local IP: $localIp\n")
        status.append("Local Port: $localPort\n")

        return newFixedLengthResponse(Response.Status.OK, "text/plain", status.toString())
    }

    private fun serveFile(session: IHTTPSession, file: File, fileName: String): Response {
        val rangeHeader = session.headers["range"]
        val mimeType = getMimeType(fileName)

        return try {
            if (rangeHeader != null) {
                val range = parseRange(rangeHeader)
                if (range != null) {
                    val fis = FileInputStream(file)
                    fis.skip(range.first)
                    val response = newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT,
                        mimeType,
                        fis,
                        range.second
                    )
                    response.addHeader("Content-Range", "bytes ${range.first}-${range.first + range.second - 1}/${file.length()}")
                    response.addHeader("Accept-Ranges", "bytes")
                    return response
                }
            }

            val fis = FileInputStream(file)
            val response = newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                fis,
                file.length()
            )
            response.addHeader("Accept-Ranges", "bytes")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error serving file: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "File error")
        }
    }

    private fun parseRange(rangeHeader: String): Pair<Long, Long>? {
        val rangeMatch = Regex("bytes=(\\d+)-(\\d*)").find(rangeHeader)
        return rangeMatch?.let {
            val start = it.groupValues[1].toLongOrNull() ?: 0L
            val end = it.groupValues[2].toLongOrNull()
            Pair(start, end ?: (Long.MAX_VALUE))
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm" -> "video/$extension"
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "image/$extension"
            "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "audio/$extension"
            "pdf" -> "application/pdf"
            "html", "htm" -> "text/html"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    fun registerStream(request: StreamRequest, inputStream: InputStream): String {
        val sessionId = "session_${sessionCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        val activeStream = ActiveStream(
            sessionId = sessionId,
            sourceType = request.sourceType,
            remotePath = request.remotePath,
            inputStream = inputStream,
            isActive = true
        )
        activeStreams[sessionId] = activeStream
        Log.d(TAG, "Registered stream: $sessionId for ${request.remotePath}")
        return sessionId
    }

    fun unregisterStream(sessionId: String) {
        val activeStream = activeStreams.remove(sessionId)
        activeStream?.let {
            it.isActive = false
            try {
                it.inputStream?.close()
                it.outputStream?.close()
                it.socket?.close()
                it.thread?.interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing stream: ${e.message}")
            }
        }
    }

    fun getStreamInfo(sessionId: String): Map<String, Any>? {
        val activeStream = activeStreams[sessionId] ?: return null
        return mapOf(
            "sessionId" to activeStream.sessionId,
            "remotePath" to activeStream.remotePath,
            "sourceType" to activeStream.sourceType.name,
            "isActive" to activeStream.isActive
        )
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
}
