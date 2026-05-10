package com.vedeng.fileserver.proxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

class CacheManager(private val context: Context) {

    private val TAG = "CacheManager"
    private val cacheDir: File
    private val maxCacheSize: Long = 500 * 1024 * 1024L
    private val maxCacheAge: Long = 7 * 24 * 60 * 60 * 1000L

    private val readWriteLock = ReentrantReadWriteLock(true)
    private val readLock = readWriteLock.readLock()
    private val writeLock = readWriteLock.writeLock()

    private val fileReferenceCount = ConcurrentHashMap<String, AtomicInteger>()
    private val fileAccessOrder = Collections.synchronizedList(LinkedList<String>())
    private val activeStreams = ConcurrentHashMap<String, ActiveFileHandle>()

    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupJob)

    data class ActiveFileHandle(
        val key: String,
        val path: String,
        var inputStream: InputStream? = null,
        var fileOutputStream: FileOutputStream? = null,
        var isDownloading: Boolean = false,
        var totalSize: Long = 0,
        var downloadedSize: Long = 0,
        var referenceCount: AtomicInteger = AtomicInteger(0)
    )

    data class CachedFile(
        val key: String,
        val file: File,
        val size: Long,
        val lastModified: Long,
        val mimeType: String?
    )

    init {
        cacheDir = File(context.cacheDir, "file_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        startCleanupJob()
    }

    fun getCacheKey(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getCachedFile(url: String): File? {
        val key = getCacheKey(url)
        readLock.lock()
        try {
            val cachedFile = File(cacheDir, key)
            if (cachedFile.exists()) {
                incrementReference(key)
                updateAccessOrder(key)
                return cachedFile
            }
            return null
        } finally {
            readLock.unlock()
        }
    }

    fun getCachedFileInfo(url: String): CachedFile? {
        val key = getCacheKey(url)
        readLock.lock()
        try {
            val cachedFile = File(cacheDir, key)
            if (cachedFile.exists()) {
                return CachedFile(
                    key = key,
                    file = cachedFile,
                    size = cachedFile.length(),
                    lastModified = cachedFile.lastModified(),
                    mimeType = getMimeType(cachedFile.name)
                )
            }
            return null
        } finally {
            readLock.unlock()
        }
    }

    fun isCached(url: String): Boolean {
        val key = getCacheKey(url)
        readLock.lock()
        try {
            return File(cacheDir, key).exists()
        } finally {
            readLock.unlock()
        }
    }

    fun getCacheProgress(url: String): Pair<Long, Long>? {
        val key = getCacheKey(url)
        val handle = activeStreams[key] ?: return null
        return Pair(handle.downloadedSize, handle.totalSize)
    }

    fun openInputStream(url: String): InputStream? {
        val key = getCacheKey(url)
        readLock.lock()
        try {
            val cachedFile = File(cacheDir, key)
            if (cachedFile.exists()) {
                incrementReference(key)
                updateAccessOrder(key)
                return BufferedInputStream(FileInputStream(cachedFile))
            }
            return null
        } finally {
            readLock.unlock()
        }
    }

    fun openCachedInputStream(url: String): Result<InputStream> {
        val key = getCacheKey(url)

        readLock.lock()
        try {
            val existingStream = activeStreams[key]
            if (existingStream != null && existingStream.inputStream != null) {
                existingStream.referenceCount.incrementAndGet()
                return Result.success(existingStream.inputStream!!)
            }

            val cachedFile = File(cacheDir, key)
            if (cachedFile.exists()) {
                incrementReference(key)
                updateAccessOrder(key)
                return Result.success(BufferedInputStream(FileInputStream(cachedFile)))
            }
        } finally {
            readLock.unlock()
        }

        return Result.failure(Exception("File not cached"))
    }

    fun createOutputStream(url: String, size: Long = -1): OutputStream? {
        val key = getCacheKey(url)
        writeLock.lock()
        try {
            val cachedFile = File(cacheDir, key)

            if (activeStreams.containsKey(key)) {
                val handle = activeStreams[key]!!
                handle.referenceCount.incrementAndGet()
                return handle.fileOutputStream
            }

            val parent = cachedFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            val fos = FileOutputStream(cachedFile)
            val handle = ActiveFileHandle(
                key = key,
                path = cachedFile.absolutePath,
                fileOutputStream = fos,
                isDownloading = true,
                totalSize = size,
                downloadedSize = 0,
                referenceCount = AtomicInteger(1)
            )
            activeStreams[key] = handle

            return fos
        } finally {
            writeLock.unlock()
        }
    }

    fun appendToCache(url: String, data: ByteArray): Boolean {
        val key = getCacheKey(url)
        val handle = activeStreams[key] ?: return false

        writeLock.lock()
        try {
            handle.fileOutputStream?.write(data)
            handle.downloadedSize += data.size
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error appending to cache: ${e.message}")
            return false
        } finally {
            writeLock.unlock()
        }
    }

    fun completeCache(url: String): File? {
        val key = getCacheKey(url)
        writeLock.lock()
        try {
            val handle = activeStreams.remove(key) ?: return null

            try {
                handle.fileOutputStream?.flush()
                handle.fileOutputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing output stream: ${e.message}")
            }

            val cachedFile = File(handle.path)
            if (cachedFile.exists()) {
                fileAccessOrder.remove(key)
                fileAccessOrder.add(key)
                return cachedFile
            }

            return null
        } finally {
            writeLock.unlock()
        }
    }

    fun cancelCache(url: String) {
        val key = getCacheKey(url)
        writeLock.lock()
        try {
            val handle = activeStreams.remove(key)
            handle?.fileOutputStream?.close()

            val cachedFile = File(cacheDir, key)
            if (cachedFile.exists()) {
                cachedFile.delete()
            }
        } finally {
            writeLock.unlock()
        }
    }

    fun releaseInputStream(url: String) {
        val key = getCacheKey(url)
        decrementReference(key)
    }

    private fun incrementReference(key: String) {
        fileReferenceCount.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun decrementReference(key: String) {
        val count = fileReferenceCount[key] ?: return
        count.decrementAndGet()

        if (count.get() <= 0) {
            cleanupScope.launch {
                delay(5000)
                readLock.lock()
                try {
                    val currentCount = fileReferenceCount[key]?.get() ?: 0
                    if (currentCount <= 0) {
                        fileAccessOrder.remove(key)
                    }
                } finally {
                    readLock.unlock()
                }
            }
        }
    }

    private fun updateAccessOrder(key: String) {
        fileAccessOrder.remove(key)
        fileAccessOrder.add(key)
    }

    fun cacheFile(url: String, sourceStream: InputStream, size: Long = -1): Result<File> = runBlocking {
        withContext(Dispatchers.IO) {
            val key = getCacheKey(url)

            readLock.lock()
            try {
                val existingFile = File(cacheDir, key)
                if (existingFile.exists()) {
                    incrementReference(key)
                    updateAccessOrder(key)
                    return@withContext Result.success(existingFile)
                }
            } finally {
                readLock.unlock()
            }

            writeLock.lock()
            try {
                val cachedFile = File(cacheDir, key)
                val parent = cachedFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                val handle = activeStreams[key]
                val fos = if (handle != null) {
                    handle.fileOutputStream
                } else {
                    FileOutputStream(cachedFile)
                }

                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int

                while (sourceStream.read(buffer).also { bytesRead = it } != -1) {
                    fos?.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }

                fos?.flush()
                fos?.close()

                fileAccessOrder.remove(key)
                fileAccessOrder.add(key)

                Result.success(cachedFile)
            } catch (e: Exception) {
                Log.e(TAG, "Error caching file: ${e.message}")
                Result.failure(e)
            } finally {
                writeLock.unlock()
            }
        }
    }

    fun cacheFileAsync(
        url: String,
        sourceStream: () -> InputStream?,
        size: Long = -1,
        onProgress: ((Long, Long) -> Unit)? = null,
        onComplete: ((Result<File>) -> Unit)? = null
    ) {
        cleanupScope.launch {
            val key = getCacheKey(url)

            writeLock.lock()
            try {
                val cachedFile = File(cacheDir, key)
                if (cachedFile.exists()) {
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(Result.success(cachedFile))
                    }
                    return@launch
                }

                val parent = cachedFile.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }

                val handle = ActiveFileHandle(
                    key = key,
                    path = cachedFile.absolutePath,
                    fileOutputStream = FileOutputStream(cachedFile),
                    isDownloading = true,
                    totalSize = size,
                    downloadedSize = 0,
                    referenceCount = AtomicInteger(1)
                )
                activeStreams[key] = handle
            } finally {
                writeLock.unlock()
            }

            try {
                val inputStream = sourceStream() ?: throw Exception("Failed to open source stream")
                val handle = activeStreams[key]!!

                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int

                while (isActive) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    handle.fileOutputStream?.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    if (onProgress != null && handle.totalSize > 0) {
                        withContext(Dispatchers.Main) {
                            onProgress(totalRead, handle.totalSize)
                        }
                    }
                }

                handle.fileOutputStream?.flush()
                handle.fileOutputStream?.close()
                inputStream.close()

                activeStreams.remove(key)
                fileAccessOrder.remove(key)
                fileAccessOrder.add(key)

                withContext(Dispatchers.Main) {
                    onComplete?.invoke(Result.success(File(handle.path)))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Async cache error: ${e.message}")
                cancelCache(url)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(Result.failure(e))
                }
            }
        }
    }

    fun clearCache(): Boolean {
        writeLock.lock()
        try {
            activeStreams.values.forEach { handle ->
                try {
                    handle.fileOutputStream?.close()
                } catch (e: Exception) {
                }
            }
            activeStreams.clear()

            cacheDir.listFiles()?.forEach { file ->
                file.delete()
            }

            fileAccessOrder.clear()
            fileReferenceCount.clear()

            return true
        } finally {
            writeLock.unlock()
        }
    }

    fun clearExpiredCache() {
        writeLock.lock()
        try {
            val now = System.currentTimeMillis()
            val files = cacheDir.listFiles() ?: return

            for (file in files) {
                val key = file.name
                if (fileReferenceCount[key]?.get() ?: 0 > 0) {
                    continue
                }

                if (now - file.lastModified() > maxCacheAge) {
                    file.delete()
                    fileAccessOrder.remove(key)
                    fileReferenceCount.remove(key)
                }
            }
        } finally {
            writeLock.unlock()
        }
    }

    fun evictIfNeeded() {
        writeLock.lock()
        try {
            var totalSize = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

            while (totalSize > maxCacheSize && fileAccessOrder.isNotEmpty()) {
                val oldestKey = fileAccessOrder.removeAt(0)
                if (fileReferenceCount[oldestKey]?.get() ?: 0 > 0) {
                    fileAccessOrder.add(oldestKey)
                    continue
                }

                val file = File(cacheDir, oldestKey)
                if (file.exists()) {
                    totalSize -= file.length()
                    file.delete()
                    fileReferenceCount.remove(oldestKey)
                }
            }
        } finally {
            writeLock.unlock()
        }
    }

    fun getCacheSize(): Long {
        readLock.lock()
        try {
            return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        } finally {
            readLock.unlock()
        }
    }

    fun getCacheInfo(): Map<String, Any> {
        readLock.lock()
        try {
            val files = cacheDir.listFiles() ?: emptyArray()
            return mapOf(
                "totalSize" to (files.sumOf { it.length() }),
                "fileCount" to files.size,
                "maxSize" to maxCacheSize,
                "activeStreams" to activeStreams.size,
                "referencedFiles" to fileReferenceCount.count { it.value.get() > 0 }
            )
        } finally {
            readLock.unlock()
        }
    }

    private fun startCleanupJob() {
        cleanupScope.launch {
            while (isActive) {
                delay(60 * 1000)
                clearExpiredCache()
                evictIfNeeded()
            }
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "webm" -> "video/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    fun destroy() {
        cleanupJob.cancel()
        clearCache()
    }
}
