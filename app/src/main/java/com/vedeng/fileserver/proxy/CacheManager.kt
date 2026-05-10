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

class CacheManager private constructor(private val context: Context) {

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
        val file: File,
        var fileOutputStream: FileOutputStream? = null
    )

    companion object {
        @Volatile
        private var instance: CacheManager? = null

        fun getInstance(context: Context): CacheManager {
            return instance ?: synchronized(this) {
                instance ?: CacheManager(context.applicationContext).also { instance = it }
            }
        }
    }

    init {
        cacheDir = File(context.cacheDir, "file_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        scheduleCleanup()
    }

    private fun scheduleCleanup() {
        cleanupScope.launch {
            while (isActive) {
                delay(maxCacheAge)
                cleanup()
            }
        }
    }

    private fun cleanup() {
        writeLock.lock()
        try {
            val now = System.currentTimeMillis()
            val files = cacheDir.listFiles() ?: return
            var totalSize = files.sumOf { it.length() }

            for (file in files.sortedBy { it.lastModified() }) {
                if (totalSize <= maxCacheSize) break
                val key = file.name
                val refCount = fileReferenceCount[key]?.get() ?: 0
                if (refCount <= 0 && file.lastModified() < now - maxCacheAge) {
                    totalSize -= file.length()
                    file.delete()
                    fileAccessOrder.remove(key)
                    fileReferenceCount.remove(key)
                }
            }
        } finally {
            writeLock.unlock()
        }
    }

    fun cacheFile(url: String, sourceStream: InputStream, size: Long = -1): File? {
        val key = getCacheKey(url)

        readLock.lock()
        try {
            val existingFile = File(cacheDir, key)
            if (existingFile.exists()) {
                incrementReference(key)
                updateAccessOrder(key)
                return existingFile
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

            FileOutputStream(cachedFile).use { fos ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                var bytesRead: Int

                while (sourceStream.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }
            }

            updateAccessOrder(key)
            return cachedFile
        } catch (e: Exception) {
            Log.e(TAG, "Error caching file: ${e.message}")
            return null
        } finally {
            writeLock.unlock()
        }
    }

    fun cacheFile(sourceStreamProvider: () -> InputStream, url: String): File? {
        return try {
            val sourceStream = sourceStreamProvider()
            cacheFile(url, sourceStream)
        } catch (e: Exception) {
            Log.e(TAG, "Error caching file: ${e.message}")
            null
        }
    }

    fun getCachedFile(url: String): File? {
        val key = getCacheKey(url)
        readLock.lock()
        try {
            val file = File(cacheDir, key)
            return if (file.exists()) {
                incrementReference(key)
                updateAccessOrder(key)
                file
            } else {
                null
            }
        } finally {
            readLock.unlock()
        }
    }

    fun getCachedUrl(url: String): String? {
        val file = getCachedFile(url)
        return file?.absolutePath?.let { "file://$it" }
    }

    fun releaseReference(url: String) {
        val key = getCacheKey(url)
        readLock.lock()
        try {
            val count = fileReferenceCount[key]
            if (count != null && count.decrementAndGet() <= 0) {
                fileAccessOrder.remove(key)
            }
        } finally {
            readLock.unlock()
        }
    }

    private fun incrementReference(key: String) {
        fileReferenceCount.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun updateAccessOrder(key: String) {
        fileAccessOrder.remove(key)
        fileAccessOrder.add(key)
    }

    private fun getCacheKey(url: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getCacheSize(): Long {
        readLock.lock()
        try {
            return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        } finally {
            readLock.unlock()
        }
    }

    fun clearCache() {
        writeLock.lock()
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
            fileAccessOrder.clear()
            fileReferenceCount.clear()
        } finally {
            writeLock.unlock()
        }
    }

    fun getCacheDir(): File = cacheDir
}
