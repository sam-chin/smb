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
    private var localRoot: String? = null

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

    fun connectToSmbServer(host: String, share: String, username: String?, password: String?) {
        _connectionStatus.value = ConnectionStatus.CONNECTING
        viewModelScope.launch {
            try {
                smbClient = SmbClient(getApplication())
                val result = smbClient?.connect(host, share, username, password)
                if (result?.isSuccess == true) {
                    _currentServer.value = ServerInfo(
                        name = "$host/$share",
                        type = ServerType.SMB,
                        host = host,
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
        _connectionStatus.value = ConnectionStatus.CONNECTED
        _currentServer.value = ServerInfo(
            name = "Local Storage",
            type = ServerType.LOCAL
        )
        listLocalFiles("/")
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
            val serverInfo = _currentServer.value
            if (serverInfo == null) {
                _isLoading.value = false
                return@withContext
            }
            val smbPath = SmbClient.buildPath(serverInfo.host, "", path.removePrefix("/"))
            val result = smbClient?.listFiles(smbPath)
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
                val root = File("/storage/emulated/0")
                val targetDir = if (path == "/") root else File(root, path.removePrefix("/"))
                if (targetDir.exists() && targetDir.isDirectory) {
                    val fileList = targetDir.listFiles()?.map { file ->
                        FileItem(
                            name = file.name,
                            path = "/${file.name}",
                            isDirectory = file.isDirectory,
                            size = if (file.isDirectory) 0L else file.length(),
                            lastModified = file.lastModified()
                        )
                    } ?: emptyList()
                    _files.postValue(fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })))
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
        if (path == "/") return false

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
                    val root = File("/storage/emulated/0")
                    val file = File(root, path.removePrefix("/"))
                    if (file.exists()) {
                        callback(file.inputStream())
                    } else {
                        callback(null)
                    }
                }
                null -> callback(null)
            }
        }
    }
}
