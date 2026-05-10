package com.vedeng.fileserver.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vedeng.fileserver.data.model.FileItem
import com.vedeng.fileserver.data.model.ServerConfig
import com.vedeng.fileserver.data.model.ServerType
import com.vedeng.fileserver.network.ftp.FtpClient
import com.vedeng.fileserver.network.smb.SmbClient
import kotlinx.coroutines.launch
import java.io.File

class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val _files = MutableLiveData<List<FileItem>>()
    val files: LiveData<List<FileItem>> = _files

    private val _currentPath = MutableLiveData<String>()
    val currentPath: LiveData<String> = _currentPath

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _currentServer = MutableLiveData<ServerConfig?>()
    val currentServer: LiveData<ServerConfig?> = _currentServer

    private val _connectionStatus = MutableLiveData<ConnectionStatus>()
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus

    private var smbClient: SmbClient? = null
    private var ftpClient: FtpClient? = null
    private var localRoot: File? = null

    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    init {
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _currentPath.value = "/"
    }

    fun connectToSmbServer(config: ServerConfig) {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            _isLoading.value = true
            _error.value = null

            try {
                smbClient = SmbClient(getApplication())
                val result = if (config.anonymous) {
                    smbClient!!.connectAnonymous(config.host, config.share ?: "")
                } else {
                    smbClient!!.connect(
                        config.host,
                        config.share ?: "",
                        config.username,
                        config.password
                    )
                }

                result.fold(
                    onSuccess = {
                        _currentServer.value = config
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        listFiles("/")
                    },
                    onFailure = { e ->
                        _error.value = e.message
                        _connectionStatus.value = ConnectionStatus.ERROR
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message
                _connectionStatus.value = ConnectionStatus.ERROR
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun connectToFtpServer(config: ServerConfig) {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            _isLoading.value = true
            _error.value = null

            try {
                ftpClient = FtpClient(
                    config.host,
                    config.port,
                    config.username ?: "anonymous",
                    config.password ?: ""
                )

                val result = ftpClient!!.connect()
                result.fold(
                    onSuccess = {
                        _currentServer.value = config
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        listFiles("/")
                    },
                    onFailure = { e ->
                        _error.value = e.message
                        _connectionStatus.value = ConnectionStatus.ERROR
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message
                _connectionStatus.value = ConnectionStatus.ERROR
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun connectToLocalStorage() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING
            _isLoading.value = true

            try {
                localRoot = File("/storage/emulated/0")
                if (!localRoot!!.exists()) {
                    localRoot = Environment.getExternalStorageDirectory()
                }

                _currentServer.value = ServerConfig(
                    id = "local",
                    name = "本地存储",
                    type = ServerType.LOCAL,
                    host = "local"
                )
                _connectionStatus.value = ConnectionStatus.CONNECTED
                listFiles("/")
            } catch (e: Exception) {
                _error.value = e.message
                _connectionStatus.value = ConnectionStatus.ERROR
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun listFiles(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _currentPath.value = path

            when (_currentServer.value?.type) {
                ServerType.SMB -> listSmbFiles(path)
                ServerType.FTP -> listFtpFiles(path)
                ServerType.LOCAL -> listLocalFiles(path)
                null -> {}
            }
        }
    }

    private suspend fun listSmbFiles(path: String) {
        try {
            smbClient?.listFiles(path)?.fold(
                onSuccess = { smbFiles ->
                    val items = smbFiles.map { smbFile ->
                        FileItem.SmbFile(
                            name = smbFile.name,
                            path = smbFile.path,
                            isDirectory = smbFile.isDirectory,
                            size = smbFile.size,
                            lastModified = smbFile.lastModified,
                            share = _currentServer.value?.share ?: ""
                        )
                    }
                    _files.value = items.sortedWith(
                        compareBy({ !it.isDirectory }, { it.name.lowercase() })
                    )
                },
                onFailure = { e ->
                    _error.value = e.message
                }
            )
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun listFtpFiles(path: String) {
        try {
            ftpClient?.listFiles(path)?.fold(
                onSuccess = { ftpFiles ->
                    val items = ftpFiles.map { ftpFile ->
                        FileItem.FtpFile(
                            name = ftpFile.name,
                            path = ftpFile.path,
                            isDirectory = ftpFile.isDirectory,
                            size = ftpFile.size,
                            lastModified = ftpFile.lastModified
                        )
                    }
                    _files.value = items.sortedWith(
                        compareBy({ !it.isDirectory }, { it.name.lowercase() })
                    )
                },
                onFailure = { e ->
                    _error.value = e.message
                }
            )
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun listLocalFiles(path: String) {
        try {
            val root = localRoot
            if (root == null) {
                _error.value = "Storage not available"
                return
            }

            val dir = if (path == "/" || path.isEmpty()) {
                root
            } else {
                File(root, path)
            }

            if (!dir.exists() || !dir.isDirectory) {
                _error.value = "Directory not found"
                return
            }

            val files = dir.listFiles()?.map { file ->
                FileItem.LocalFile(
                    name = file.name,
                    path = if (path == "/") "/${file.name}" else "$path/${file.name}",
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0,
                    lastModified = file.lastModified(),
                    mimeType = getMimeType(file.name)
                )
            } ?: emptyList()

            _files.value = files.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            )
        } finally {
            _isLoading.value = false
        }
    }

    fun navigateUp(): Boolean {
        val path = _currentPath.value ?: return false
        if (path == "/" || path.isEmpty()) return false

        val parentPath = File(path).parent ?: "/"
        listFiles(parentPath)
        return true
    }

    fun navigateToFolder(path: String) {
        listFiles(path)
    }

    fun disconnect() {
        smbClient?.disconnect()
        ftpClient?.disconnect()
        smbClient = null
        ftpClient = null
        localRoot = null
        _currentServer.value = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _files.value = emptyList()
        _currentPath.value = "/"
    }

    fun getInputStream(path: String, callback: (java.io.InputStream?) -> Unit) {
        viewModelScope.launch {
            when (_currentServer.value?.type) {
                ServerType.SMB -> {
                    smbClient?.openInputStream(path)?.fold(
                        onSuccess = { callback(it) },
                        onFailure = { callback(null) }
                    )
                }
                ServerType.FTP -> {
                    ftpClient?.downloadStream(path)?.fold(
                        onSuccess = { callback(it) },
                        onFailure = { callback(null) }
                    )
                }
                ServerType.LOCAL -> {
                    val root = localRoot
                    if (root != null) {
                        val file = File(root, path)
                        if (file.exists()) {
                            callback(file.inputStream())
                        } else {
                            callback(null)
                        }
                    } else {
                        callback(null)
                    }
                }
                null -> callback(null)
            }
        }
    }

    private fun getMimeType(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v" -> "video/*"
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "image/*"
            "mp3", "wav", "aac", "flac", "ogg", "m4a" -> "audio/*"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> null
        }
    }

    fun clearError() {
        _error.value = null
    }
}

private object Environment {
    fun getExternalStorageDirectory(): File {
        return android.os.Environment.getExternalStorageDirectory()
    }
}
