package com.vedeng.fileserver.network.smb

import android.content.Context
import android.util.Log
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Properties

class SmbClient(private val context: Context) {

    private var baseContext: CIFSContext? = null
    private var credentials: NtlmPasswordAuthenticator? = null

    data class ServerInfo(
        val host: String,
        val share: String,
        val username: String?,
        val password: String?
    )

    data class FileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    fun connect(host: String, share: String, username: String?, password: String?): Result<Unit> {
        return try {
            credentials = NtlmPasswordAuthenticator(
                if (username.isNullOrEmpty()) "" else null,
                username ?: "",
                password ?: ""
            )

            val props = Properties().apply {
                setProperty("jcifs.smb.client.responseTimeout", "30000")
                setProperty("jcifs.smb.client.soTimeout", "30000")
                setProperty("jcifs.smb.client.connTimeout", "30000")
            }

            baseContext = BaseContext(PropertyConfiguration(props))
                .withCredentials(credentials!!)

            val testFile = SmbFile("smb://$host/$share/", baseContext!!)
            testFile.listFiles()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun listFiles(remotePath: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val smbFile = SmbFile(remotePath, baseContext!!)
            val files = smbFile.listFiles()
            val fileList = files.map { file ->
                FileItem(
                    name = file.name,
                    path = file.canonicalPath,
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified
                )
            }
            Result.success(fileList)
        } catch (e: Exception) {
            Log.e(TAG, "List files failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun downloadFile(remotePath: String): Result<InputStream> = withContext(Dispatchers.IO) {
        try {
            val smbFile = SmbFile(remotePath, baseContext!!)
            if (smbFile.isDirectory) {
                return@withContext Result.failure(Exception("Cannot download directory"))
            }
            val inputStream = smbFile.openInputStream()
            Result.success(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getFileInfo(remotePath: String): Result<FileItem> = withContext(Dispatchers.IO) {
        try {
            val smbFile = SmbFile(remotePath, baseContext!!)
            val fileItem = FileItem(
                name = smbFile.name,
                path = smbFile.canonicalPath,
                isDirectory = smbFile.isDirectory,
                size = if (smbFile.isDirectory) 0L else smbFile.length(),
                lastModified = smbFile.lastModified
            )
            Result.success(fileItem)
        } catch (e: Exception) {
            Log.e(TAG, "Get file info failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun disconnect() {
        try {
            baseContext = null
            credentials = null
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed: ${e.message}")
        }
    }

    fun isConnected(): Boolean = baseContext != null

    companion object {
        private const val TAG = "SmbClient"

        fun buildPath(host: String, share: String, folder: String = ""): String {
            val cleanFolder = if (folder.isEmpty() || folder == "/") "" else folder.trimStart('/')
            return if (cleanFolder.isEmpty()) {
                "smb://$host/$share/"
            } else {
                "smb://$host/$share/$cleanFolder"
            }
        }

        fun parsePath(path: String): ServerInfo? {
            val regex = Regex("smb://([^/]+)/([^/]+)(.*)")
            val match = regex.find(path) ?: return null
            return ServerInfo(
                host = match.groupValues[1],
                share = match.groupValues[2],
                username = null,
                password = null
            )
        }
    }
}
