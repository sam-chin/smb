package com.vedeng.fileserver.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vedeng.fileserver.network.ftp.FtpClient
import com.vedeng.fileserver.network.smb.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileManagerViewModel(application: Application) : AndroidViewModel(application) {

    private var smbClient: SmbClient? = null
    private var ftpClient: FtpClient? = null
    private var localRoot: String = ""

    private val _files = MutableLiveData<List<FileItem>>()
    val files: LiveData<List<FileItem>> = _files

    private val _currentPath = MutableLiveData<String>()
    val currentPath: LiveData<String> = _currentPath

    private val _currentServer = MutableLiveData<ServerInfo?>()
    val currentServer: LiveData<ServerInfo?> = _currentServer

    private val _connectionStatus = MutableLiveData<ConnectionStatus>()
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    data class FileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long
    )

    data class ServerInfo(
        val name: String,
        val type: ServerType,
        val host: String = "",
        val share: String = "",
        val domain: String? = null,
        val port: Int = 0,
        val username: String? = null,
        val password: String? = null
    )

    enum class ServerType {
        SMB, FTP, LOCAL
    }

    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    fun connectToSmbServer(host: String, share: String, domain: String?, username: String?, password: String?) {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        viewModelScope.launch {
            try {
                smbClient = SmbClient(getApplication())
                val result = smbClient?.connect(host, share, domain, username, password)
                if (result?.isSuccess == true) {
                    _currentServer.value = ServerInfo(
                        name = "$host/$share",
                        type = ServerType.SMB,
                        host = host,
                        share = share,
                        domain = domain,
                        port = 445,
                        username = username,
                        password = password
                    )
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    listFiles("/")
                } else {
                    _connectionStatus.value = ConnectionStatus.ERROR
                    _error.value = result?.exceptionOrNull()?.message ?: "Connection failed"
                }
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.ERROR
                _error.value = e.message
            }
        }
    }

    fun connectToFtpServer(host: String, port: Int, username: String?, password: String?) {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        viewModelScope.launch {
            try {
                ftpClient = FtpClient()
                val result = ftpClient?.connect(host, port, username, password)
                if (result?.isSuccess == true) {
                    _currentServer.value = ServerInfo(
                        name = "$host:$port",
                        type = ServerType.FTP,
                        host = host,
                        port = port,
                        username = username,
                        password = password
                    )
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    listFiles("/")
                } else {
                    _connectionStatus.value = ConnectionStatus.ERROR
                    _error.value = result?.exceptionOrNull()?.message ?: "Connection failed"
                }
            } catch (e: Exception) {
                _connectionStatus.value = ConnectionStatus.ERROR
                _error.value = e.message
            }
        }
    }

    fun connectToLocalStorage() {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        localRoot = "/storage/emulated/0"
        _currentServer.value = ServerInfo(
            name = "Local Storage",
            type = ServerType.LOCAL
        )
        _connectionStatus.value = ConnectionStatus.CONNECTED
        listFiles("/")
    }

    fun listFiles(path: String) {
        _isLoading.value = true
        _currentPath.value = path

        viewModelScope.launch {
            when (_currentServer.value?.type) {
                ServerType.SMB -> listSmbFiles(path)
                ServerType.FTP -> listFtpFiles(path)
                ServerType.LOCAL -> listLocalFiles(path)
                null -> _isLoading.value = false
            }
        }
    }

    private suspend fun listSmbFiles(path: String) = withContext(Dispatchers.IO) {
        try {
            val result = smbClient?.listFiles(path)
            result?.onSuccess { smbFiles ->
                val fileList = smbFiles.map { file ->
                    FileItem(
                        name = file.name,
                        path = file.path,
                        isDirectory = file.isDirectory,
                        size = file.size,
                        lastModified = file.lastModified
                    )
                }
                _files.postValue(fileList)
            }?.onFailure { error ->
                _error.postValue(error.message)
            }
        } finally {
            _isLoading.postValue(false)
        }
    }

    private suspend fun listFtpFiles(path: String) = withContext(Dispatchers.IO) {
        try {
            val result = ftpClient?.listFiles(path)
            result?.onSuccess { ftpFiles ->
                val fileList = ftpFiles.map { file ->
                    FileItem(
                        name = file.name,
                        path = file.path,
                        isDirectory = file.isDirectory,
                        size = file.size,
                        lastModified = file.lastModified
                    )
                }
                _files.postValue(fileList)
            }?.onFailure { error ->
                _error.postValue(error.message)
            }
        } finally {
            _isLoading.postValue(false)
        }
    }

    private fun listLocalFiles(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = File(localRoot)
                val targetDir = if (path == "/" || path.isEmpty()) root else File(root, path.removePrefix("/"))

                if (targetDir.exists() && targetDir.isDirectory) {
                    val fileList = targetDir.listFiles()?.map { file ->
                        val relativePath = if (path == "/") {
                            "/${file.name}"
                        } else {
                            "$path/${file.name}"
                        }

                        FileItem(
                            name = file.name,
                            path = relativePath,
                            isDirectory = file.isDirectory,
                            size = if (file.isDirectory) 0L else file.length(),
                            lastModified = file.lastModified()
                        )
                    }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

                    _files.postValue(fileList)
                } else {
                    _files.postValue(emptyList())
                }
            } catch (e: Exception) {
                _error.postValue(e.message)
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    fun navigateUp(): Boolean {
        val path = _currentPath.value ?: return false
        if (path == "/" || path.isEmpty()) return false

        val parentPath = when (_currentServer.value?.type) {
            ServerType.SMB -> {
                val basePath = "smb://${_currentServer.value?.host}/${_currentServer.value?.share}/"
                if (path == basePath) return false
                
                val lastSlash = path.lastIndexOf('/')
                if (lastSlash <= basePath.length - 1) {
                    basePath
                } else {
                    path.substring(0, lastSlash)
                }
            }
            ServerType.FTP -> {
                if (path == "/") return false
                val lastSlash = path.lastIndexOf('/')
                if (lastSlash == 0) {
                    "/"
                } else {
                    path.substring(0, lastSlash)
                }
            }
            ServerType.LOCAL -> {
                val lastSlash = path.lastIndexOf('/')
                if (lastSlash == 0) {
                    "/"
                } else {
                    path.substring(0, lastSlash)
                }
            }
            null -> return false
        }

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
        _currentServer.postValue(null)
        _connectionStatus.postValue(ConnectionStatus.DISCONNECTED)
        _files.postValue(emptyList())
        _currentPath.postValue("/")
    }

    fun clearError() {
        _error.value = null
    }

    fun getInputStream(path: String, callback: (java.io.InputStream?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            when (_currentServer.value?.type) {
                ServerType.SMB -> {
                    val result = smbClient?.downloadFile(path)
                    result?.onSuccess { stream ->
                        callback(stream)
                    } ?: callback(null)
                }
                ServerType.FTP -> {
                    val result = ftpClient?.downloadStream(path)
                    result?.onSuccess { stream ->
                        callback(stream)
                    } ?: callback(null)
                }
                ServerType.LOCAL -> {
                    try {
                        val root = File(localRoot)
                        val file = if (path.startsWith("/")) {
                            File(root, path.removePrefix("/"))
                        } else {
                            File(root, path)
                        }
                        if (file.exists()) {
                            callback(file.inputStream())
                        } else {
                            callback(null)
                        }
                    } catch (e: Exception) {
                        callback(null)
                    }
                }
                null -> callback(null)
            }
        }
    }

    fun getLocalPathForFile(path: String): String? {
        return when (_currentServer.value?.type) {
            ServerType.LOCAL -> {
                val root = File(localRoot)
                val file = if (path.startsWith("/")) {
                    File(root, path.removePrefix("/"))
                } else {
                    File(root, path)
                }
                if (file.exists()) file.absolutePath else null
            }
            else -> null
        }
    }
}
