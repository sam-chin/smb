package com.vedeng.fileserver.network.smb

import android.content.Context
import android.util.Log
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

class SmbClient(
    private val context: Context
) {
    private val TAG = "SmbClient"
    private var baseContext: BaseContext? = null
    private var auth: NtlmPasswordAuthenticator? = null
    private var isConnected = false
    private var currentShare: String = ""
    private var currentPath: String = ""

    data class SmbFileInfo(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    companion object {
        private const val DEFAULT_TIMEOUT = 30000
    }

    fun connect(
        server: String,
        share: String,
        username: String?,
        password: String?
    ): Result<Unit> {
        return try {
            val properties = java.util.Properties()
            properties.setProperty("jcifs.smb.client.responseTimeout", DEFAULT_TIMEOUT.toString())
            properties.setProperty("jcifs.smb.client.soTimeout", DEFAULT_TIMEOUT.toString())
            properties.setProperty("jcifs.smb.client.connTimeout", DEFAULT_TIMEOUT.toString())
            properties.setProperty("jcifs.smb.client.readTimeout", DEFAULT_TIMEOUT.toString())
            properties.setProperty("jcifs.smb.client.writeTimeout", DEFAULT_TIMEOUT.toString())

            baseContext = BaseContext(properties)

            val domain = ""
            auth = NtlmPasswordAuthenticator(domain, username ?: "", password ?: "")

            currentShare = share
            currentPath = ""

            isConnected = true
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun connectAnonymous(server: String, share: String): Result<Unit> {
        return try {
            val properties = java.util.Properties()
            properties.setProperty("jcifs.smb.client.responseTimeout", DEFAULT_TIMEOUT.toString())
            properties.setProperty("jcifs.smb.client.soTimeout", DEFAULT_TIMEOUT.toString())
            properties.setProperty("jcifs.smb.client.connTimeout", DEFAULT_TIMEOUT.toString())
            properties.setProperty("jcifs.smb.client.readTimeout", DEFAULT_TIMEOUT.toString())
            properties.setProperty("jcifs.smb.client.writeTimeout", DEFAULT_TIMEOUT.toString())

            baseContext = BaseContext(properties)

            auth = NtlmPasswordAuthenticator(null, "guest", "")

            currentShare = share
            currentPath = ""

            isConnected = true
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous connection failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun listFiles(path: String = "/"): Result<List<SmbFileInfo>> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || baseContext == null || auth == null) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val fullPath = buildSmbPath(path)
            Log.d(TAG, "Listing files from: $fullPath")

            val smbFile = SmbFile(fullPath, baseContext, auth)
            val files = mutableListOf<SmbFileInfo>()

            if (smbFile.exists() && smbFile.isDirectory) {
                val children = smbFile.list()
                for (childName in children) {
                    try {
                        val childFile = SmbFile(fullPath + childName, baseContext, auth)
                        files.add(
                            SmbFileInfo(
                                name = childName.trimEnd('/'),
                                path = path + if (path.endsWith("/")) childName.trimEnd('/') else "/${childName.trimEnd('/')}",
                                isDirectory = childFile.isDirectory,
                                size = if (childFile.isDirectory) 0 else childFile.length(),
                                lastModified = childFile.lastModified
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading child: $childName - ${e.message}")
                    }
                }
            }

            currentPath = path
            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "List files error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun listShares(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || baseContext == null || auth == null) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val serverUrl = "smb://${getServerAddress()}/"
            val rootFile = SmbFile(serverUrl, baseContext, auth)
            val shares = mutableListOf<String>()

            if (rootFile.exists() && rootFile.isDirectory) {
                val children = rootFile.list()
                for (childName in children) {
                    try {
                        val childFile = SmbFile(serverUrl + childName, baseContext, auth)
                        if (childFile.isDirectory) {
                            shares.add(childName.trimEnd('/'))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading share: $childName - ${e.message}")
                    }
                }
            }

            Result.success(shares)
        } catch (e: Exception) {
            Log.e(TAG, "List shares error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun downloadFile(remotePath: String, localFile: java.io.File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || baseContext == null || auth == null) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val fullPath = buildSmbPath(remotePath)
            val smbFile = SmbFile(fullPath, baseContext, auth)

            if (!smbFile.exists()) {
                return@withContext Result.failure(Exception("File not found"))
            }

            localFile.parentFile?.mkdirs()

            smbFile.inputStream.use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun openInputStream(remotePath: String): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || baseContext == null || auth == null) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val fullPath = buildSmbPath(remotePath)
            val smbFile = SmbFile(fullPath, baseContext, auth)

            if (!smbFile.exists()) {
                return@withContext Result.failure(Exception("File not found"))
            }

            val inputStream = smbFile.inputStream
            Result.success(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Open stream error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getFileSize(remotePath: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || baseContext == null || auth == null) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val fullPath = buildSmbPath(remotePath)
            val smbFile = SmbFile(fullPath, baseContext, auth)

            if (!smbFile.exists()) {
                return@withContext Result.failure(Exception("File not found"))
            }

            Result.success(smbFile.length())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun readBytes(remotePath: String, start: Long, length: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected || baseContext == null || auth == null) {
                return@withContext Result.failure(Exception("Not connected"))
            }

            val fullPath = buildSmbPath(remotePath)
            val smbFile = SmbFile(fullPath, baseContext, auth)

            if (!smbFile.exists()) {
                return@withContext Result.failure(Exception("File not found"))
            }

            val inputStream = smbFile.inputStream
            inputStream.skip(start)

            val buffer = ByteArray(length)
            var totalRead = 0
            var bytesRead: Int

            while (totalRead < length) {
                bytesRead = inputStream.read(buffer, totalRead, length - totalRead)
                if (bytesRead == -1) break
                totalRead += bytesRead
            }

            inputStream.close()
            Result.success(buffer.copyOf(totalRead))
        } catch (e: Exception) {
            Log.e(TAG, "Read bytes error: ${e.message}")
            Result.failure(e)
        }
    }

    fun getCurrentShare(): String = currentShare

    fun getCurrentPath(): String = currentPath

    fun isConnected(): Boolean = isConnected

    fun disconnect() {
        try {
            baseContext?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect error: ${e.message}")
        }
        baseContext = null
        auth = null
        isConnected = false
        currentShare = ""
        currentPath = ""
    }

    private fun buildSmbPath(path: String): String {
        val server = getServerAddress()
        val share = currentShare.trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"

        return "smb://$server/$share$cleanPath"
    }

    private fun getServerAddress(): String {
        return currentShare.substringBefore("/").substringBefore(":")
    }
}
