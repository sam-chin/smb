package com.vedeng.fileserver.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vedeng.fileserver.FileServerApp
import com.vedeng.fileserver.data.model.ServerConfig
import com.vedeng.fileserver.network.dlna.CastController
import com.vedeng.fileserver.network.ftp.FtpClient
import com.vedeng.fileserver.network.smb.SmbClient
import com.vedeng.fileserver.proxy.ProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val _videoUrl = MutableLiveData<String?>()
    val videoUrl: LiveData<String?> = _videoUrl

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _playbackPosition = MutableLiveData<Long>()
    val playbackPosition: LiveData<Long> = _playbackPosition

    private val _duration = MutableLiveData<Long>()
    val duration: LiveData<Long> = _duration

    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _castStatus = MutableLiveData<CastStatus>()
    val castStatus: LiveData<CastStatus> = _castStatus

    private val _castDevices = MutableLiveData<List<CastController.CastDevice>>()
    val castDevices: LiveData<List<CastController.CastDevice>> = _castDevices

    private val _selectedDevice = MutableLiveData<CastController.CastDevice?>()
    val selectedDevice: LiveData<CastController.CastDevice?> = _selectedDevice

    private val _castPosition = MutableLiveData<Pair<Long, Long>>()
    val castPosition: LiveData<Pair<Long, Long>> = _castPosition

    private var castController: CastController? = null
    private var cacheManager = FileServerApp.instance.cacheManager
    private var proxyServer = FileServerApp.instance.proxyServer
    private var positionUpdateJob: Job? = null
    private var castPositionJob: Job? = null

    private var currentVideoPath: String = ""
    private var currentServerConfig: ServerConfig? = null
    private var localProxyUrl: String = ""
    private var castProxyUrl: String = ""

    enum class CastStatus {
        IDLE, SEARCHING, CONNECTING, CASTING, ERROR
    }

    data class VideoInfo(
        val name: String,
        val path: String,
        val size: Long,
        val duration: Long = 0
    )

    private val _videoInfo = MutableLiveData<VideoInfo?>()
    val videoInfo: LiveData<VideoInfo?> = _videoInfo

    init {
        _castStatus.value = CastStatus.IDLE
    }

    fun prepareVideo(
        name: String,
        path: String,
        serverConfig: ServerConfig?
    ) {
        currentVideoPath = path
        currentServerConfig = serverConfig
        _videoInfo.value = VideoInfo(name = name, path = path, size = 0)

        viewModelScope.launch {
            _isLoading.value = true

            try {
                val cachedFile = cacheManager.getCachedFile(path)

                if (cachedFile != null && cachedFile.exists()) {
                    localProxyUrl = ProxyServer.getLocalUrl("/file/${cachedFile.name}")
                    _videoUrl.value = localProxyUrl
                    _videoInfo.value = VideoInfo(
                        name = name,
                        path = cachedFile.absolutePath,
                        size = cachedFile.length()
                    )
                    _isLoading.value = false
                    return@launch
                }

                when (serverConfig?.type) {
                    com.vedeng.fileserver.data.model.ServerType.LOCAL -> {
                        val file = File(path)
                        if (file.exists()) {
                            val result = cacheManager.cacheFile(path) { file.inputStream() }
                            if (result.isSuccess) {
                                localProxyUrl = ProxyServer.getLocalUrl("/file/${cacheManager.getCacheKey(path)}")
                                _videoUrl.value = localProxyUrl
                                _videoInfo.value = VideoInfo(
                                    name = name,
                                    path = result.getOrNull()?.absolutePath ?: path,
                                    size = result.getOrNull()?.length() ?: 0
                                )
                            }
                        }
                    }
                    com.vedeng.fileserver.data.model.ServerType.SMB -> {
                        prepareSmbVideo(name, path, serverConfig)
                    }
                    com.vedeng.fileserver.data.model.ServerType.FTP -> {
                        prepareFtpVideo(name, path, serverConfig)
                    }
                    else -> {
                        _error.value = "Unsupported source"
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun prepareSmbVideo(name: String, path: String, config: ServerConfig) {
        withContext(Dispatchers.IO) {
            try {
                val smbClient = SmbClient(getApplication())
                val result = if (config.anonymous) {
                    smbClient.connectAnonymous(config.host, config.share ?: "")
                } else {
                    smbClient.connect(config.host, config.share ?: "", config.username, config.password)
                }

                if (result.isFailure) {
                    withContext(Dispatchers.Main) {
                        _error.value = result.exceptionOrNull()?.message ?: "Connection failed"
                    }
                    return@withContext
                }

                val streamResult = smbClient.openInputStream(path)
                if (streamResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        _error.value = streamResult.exceptionOrNull()?.message ?: "Failed to open stream"
                    }
                    return@withContext
                }

                val sizeResult = smbClient.getFileSize(path)
                val size = sizeResult.getOrNull() ?: 0L

                val cacheResult = cacheManager.cacheFile(path) {
                    val inputStream = smbClient.openInputStream(path).getOrNull()
                        ?: throw Exception("Failed to open SMB stream")
                    inputStream
                }

                if (cacheResult.isSuccess) {
                    val cachedFile = cacheResult.getOrNull()!!
                    withContext(Dispatchers.Main) {
                        localProxyUrl = ProxyServer.getLocalUrl("/file/${cacheManager.getCacheKey(path)}")
                        _videoUrl.value = localProxyUrl
                        _videoInfo.value = VideoInfo(
                            name = name,
                            path = cachedFile.absolutePath,
                            size = cachedFile.length()
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _error.value = cacheResult.exceptionOrNull()?.message ?: "Cache failed"
                    }
                }

                smbClient.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message
                }
            }
        }
    }

    private suspend fun prepareFtpVideo(name: String, path: String, config: ServerConfig) {
        withContext(Dispatchers.IO) {
            try {
                val ftpClient = FtpClient(
                    config.host,
                    config.port,
                    config.username ?: "anonymous",
                    config.password ?: ""
                )

                if (ftpClient.connect().isFailure) {
                    withContext(Dispatchers.Main) {
                        _error.value = "FTP connection failed"
                    }
                    return@withContext
                }

                val sizeResult = ftpClient.getFileSize(path)
                val size = sizeResult.getOrNull() ?: 0L

                val cacheResult = cacheManager.cacheFile(path) {
                    val streamResult = ftpClient.downloadStream(path)
                    streamResult.getOrNull() ?: throw Exception("Failed to download")
                }

                if (cacheResult.isSuccess) {
                    val cachedFile = cacheResult.getOrNull()!!
                    withContext(Dispatchers.Main) {
                        localProxyUrl = ProxyServer.getLocalUrl("/file/${cacheManager.getCacheKey(path)}")
                        _videoUrl.value = localProxyUrl
                        _videoInfo.value = VideoInfo(
                            name = name,
                            path = cachedFile.absolutePath,
                            size = cachedFile.length()
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _error.value = cacheResult.exceptionOrNull()?.message ?: "Cache failed"
                    }
                }

                ftpClient.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message
                }
            }
        }
    }

    fun updatePlaybackState(isPlaying: Boolean, position: Long, duration: Long) {
        _isPlaying.value = isPlaying
        _playbackPosition.value = position
        _duration.value = duration
    }

    fun searchCastDevices() {
        viewModelScope.launch {
            _castStatus.value = CastStatus.SEARCHING
            _castDevices.value = emptyList()

            try {
                castController = CastController()
                val result = castController!!.searchDevices(5000)

                result.fold(
                    onSuccess = { devices ->
                        _castDevices.value = devices
                        _castStatus.value = if (devices.isEmpty()) CastStatus.IDLE else CastStatus.IDLE
                    },
                    onFailure = { e ->
                        _error.value = e.message
                        _castStatus.value = CastStatus.ERROR
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun selectDevice(device: CastController.CastDevice) {
        viewModelScope.launch {
            _selectedDevice.value = device
            _castStatus.value = CastStatus.CONNECTING

            try {
                castController = CastController()
                val connectResult = castController!!.connectDevice(device)

                connectResult.fold(
                    onSuccess = {
                        _castStatus.value = CastStatus.IDLE
                    },
                    onFailure = { e ->
                        _error.value = e.message
                        _castStatus.value = CastStatus.ERROR
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun startCasting() {
        viewModelScope.launch {
            val device = _selectedDevice.value ?: return@launch
            val videoInfo = _videoInfo.value ?: return@launch

            _castStatus.value = CastStatus.CONNECTING

            try {
                if (castController == null) {
                    castController = CastController()
                    castController!!.connectDevice(device)
                }

                val proxyUrl = if (castProxyUrl.isEmpty()) {
                    localProxyUrl.ifEmpty {
                        _videoUrl.value ?: throw Exception("No video URL available")
                    }
                } else {
                    castProxyUrl
                }

                val result = castController!!.play(
                    mediaUrl = proxyUrl,
                    mediaTitle = videoInfo.name,
                    mediaType = "video/*"
                )

                result.fold(
                    onSuccess = {
                        _castStatus.value = CastStatus.CASTING
                        startCastPositionUpdates()
                    },
                    onFailure = { e ->
                        _error.value = e.message
                        _castStatus.value = CastStatus.ERROR
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message
                _castStatus.value = CastStatus.ERROR
            }
        }
    }

    fun stopCasting() {
        viewModelScope.launch {
            try {
                castController?.stop()
                castPositionJob?.cancel()
                _castStatus.value = CastStatus.IDLE
                _selectedDevice.value = null
                _castPosition.value = Pair(0L, 0L)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun pauseCasting() {
        viewModelScope.launch {
            try {
                castController?.pause()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun resumeCasting() {
        viewModelScope.launch {
            try {
                castController?.resume()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun seekCasting(positionMs: Long) {
        viewModelScope.launch {
            try {
                castController?.seek(positionMs)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    private fun startCastPositionUpdates() {
        castPositionJob?.cancel()
        castPositionJob = viewModelScope.launch {
            while (isActive && _castStatus.value == CastStatus.CASTING) {
                try {
                    val result = castController?.getPositionInfo()
                    if (result?.isSuccess == true) {
                        _castPosition.value = result.getOrNull()
                    }
                } catch (e: Exception) {
                }
                delay(1000)
            }
        }
    }

    fun setCastProxyUrl(url: String) {
        castProxyUrl = url
    }

    fun getLocalProxyUrl(): String = localProxyUrl

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopCasting()
        castController?.disconnect()
    }
}
