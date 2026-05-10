package com.vedeng.fileserver.network.smb

import android.content.Context
import com.vedeng.fileserver.util.LogHelper
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

    companion object {
        private const val TAG = "SmbClient"
    }

    private var baseContext: CIFSContext? = null
    private var currentHost: String = ""
    private var currentShare: String = ""
    private var currentDomain: String = ""
    private var currentBasePath: String = ""

    data class ServerInfo(
        val host: String,
        val share: String,
        val domain: String?,
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

    fun connect(host: String, share: String, domain: String?, username: String?, password: String?): Result<Unit> {
        return try {
            currentHost = host
            currentShare = share
            currentDomain = domain ?: ""
            currentBasePath = "smb://$host/$share/"

            val props = Properties().apply {
                setProperty("jcifs.smb.client.responseTimeout", "30000")
                setProperty("jcifs.smb.client.soTimeout", "30000")
                setProperty("jcifs.smb.client.connTimeout", "30000")
                setProperty("jcifs.smb.client.dfs.disabled", "true")
                setProperty("jcifs.smb.client.useExtendedSecurity", "true")
                setProperty("jcifs.netbios.cachePolicy", "0")
                setProperty("jcifs.resolveOrder", "DNS,BCAST")
            }

            val auth = if (username.isNullOrEmpty() && password.isNullOrEmpty()) {
                NtlmPasswordAuthenticator("", "", "")
            } else {
                NtlmPasswordAuthenticator(
                    domain ?: "",
                    username ?: "",
                    password ?: ""
                )
            }

            baseContext = BaseContext(PropertyConfiguration(props))
                .withCredentials(auth)

            LogHelper.d(TAG, "Testing connection to: $currentBasePath")
            val testFile = SmbFile(currentBasePath, baseContext!!)
            testFile.connect()
            
            Result.success(Unit)
        } catch (e: Exception) {
            LogHelper.e(TAG, "Connection failed: ${e.message}", e)
            baseContext = null
            Result.failure(e)
        }
    }

    suspend fun listFiles(remotePath: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val baseContextLocal = baseContext ?: return@withContext Result.failure(Exception("Not connected"))

            val smbPath = if (remotePath.startsWith("smb://")) {
                remotePath
            } else if (remotePath == "/" || remotePath.isEmpty()) {
                currentBasePath
            } else {
                buildPath(currentHost, currentShare, remotePath)
            }

            LogHelper.d(TAG, "Listing files at: $smbPath")
            
            val smbFile = SmbFile(smbPath, baseContextLocal)
            
            if (!smbFile.exists()) {
                return@withContext Result.failure(Exception("Path does not exist: $smbPath"))
            }
            
            if (!smbFile.isDirectory) {
                return@withContext Result.failure(Exception("Not a directory: $smbPath"))
            }

            val files = smbFile.listFiles() ?: emptyArray()
            
            LogHelper.d(TAG, "Found ${files.size} files")

            val fileList = files.map { file ->
                FileItem(
                    name = file.name.removeSuffix("/"),
                    path = file.path,
                    isDirectory = file.isDirectory,
                    size = if (file.isDirectory) 0L else file.length(),
                    lastModified = file.lastModified
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

            Result.success(fileList)
        } catch (e: Exception) {
            LogHelper.e(TAG, "List files failed: ${e.message}", e)
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

    fun disconnect() {
        try {
            baseContext = null
            currentHost = ""
            currentShare = ""
            currentDomain = ""
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed: ${e.message}")
        }
    }

    fun isConnected(): Boolean = baseContext != null

    companion object {
        private const val TAG = "SmbClient"

        fun buildPath(host: String, share: String, folder: String = ""): String {
            val cleanFolder = when {
                folder.isEmpty() || folder == "/" -> ""
                folder.startsWith("/") -> folder.substring(1)
                else -> folder
            }

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
                domain = null,
                username = null,
                password = null
            )
        }
    }
}
