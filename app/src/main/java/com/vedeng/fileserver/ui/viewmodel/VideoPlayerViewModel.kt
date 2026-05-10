package com.vedeng.fileserver.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vedeng.fileserver.network.dlna.CastController
import com.vedeng.fileserver.proxy.CacheManager
import com.vedeng.fileserver.proxy.ProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.InputStream

class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val cacheManager = CacheManager.getInstance(application)
    private val proxyServer = ProxyServer.getInstance(application, cacheManager)
    val castController = CastController()

    private val _videoPath = MutableLiveData<String>()
    val videoPath: LiveData<String> = _videoPath

    private val _localUrl = MutableLiveData<String>()
    val localUrl: LiveData<String> = _localUrl

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isCasting = MutableLiveData<Boolean>()
    val isCasting: LiveData<Boolean> = _isCasting

    private val _castDevice = MutableLiveData<CastController.CastDevice?>()
    val castDevice: LiveData<CastController.CastDevice?> = _castDevice

    private val _playbackPosition = MutableLiveData<Long>()
    val playbackPosition: LiveData<Long> = _playbackPosition

    private val _videoDuration = MutableLiveData<Long>()
    val videoDuration: LiveData<Long> = _videoDuration

    private var currentSessionId: String? = null

    fun setVideoPath(path: String) {
        _videoPath.value = path
    }

    fun cacheVideoForLocalPlayback(
        sourceStreamProvider: () -> InputStream,
        remotePath: String
    ): String? {
        _isLoading.postValue(true)
        return try {
            val cachedFile = cacheManager.cacheFile(sourceStreamProvider, remotePath)
            if (cachedFile != null) {
                val sessionId = proxyServer.registerStream(
                    ProxyServer.StreamRequest(
                        sourceType = ProxyServer.SourceType.LOCAL,
                        remotePath = remotePath,
                        host = "127.0.0.1"
                    ),
                    FileInputStream(cachedFile)
                )
                currentSessionId = sessionId
                val url = "http://127.0.0.1:${proxyServer.getLocalPortValue()}/video/$sessionId"
                _localUrl.postValue(url)
                url
            } else {
                null
            }
        } catch (e: Exception) {
            _error.postValue(e.message)
            null
        } finally {
            _isLoading.postValue(false)
        }
    }

    fun cacheVideoForCast(
        sourceStreamProvider: () -> InputStream,
        remotePath: String
    ): String? {
        return try {
            val cachedFile = cacheManager.cacheFile(sourceStreamProvider, remotePath)
            if (cachedFile != null) {
                "file://${cachedFile.absolutePath}"
            } else {
                null
            }
        } catch (e: Exception) {
            _error.postValue(e.message)
            null
        }
    }

    fun getCachedVideoUrl(remotePath: String): String? {
        val cachedFile = cacheManager.getCachedFile(remotePath)
        return if (cachedFile != null && cachedFile.exists()) {
            "file://${cachedFile.absolutePath}"
        } else null
    }

    fun getProxyUrl(remotePath: String): String {
        val port = proxyServer.getLocalPortValue()
        return "http://127.0.0.1:$port/video/${remotePath.hashCode()}"
    }

    fun startCast(videoUrl: String, title: String = "Video") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = _castDevice.value
                if (device != null) {
                    castController.selectDevice(device)
                    castController.play(videoUrl, "video/mp4", title)
                    _isCasting.postValue(true)
                }
            } catch (e: Exception) {
                _error.postValue("Cast failed: ${e.message}")
            }
        }
    }

    fun stopCast() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.stop()
                _isCasting.postValue(false)
            } catch (e: Exception) {
                _error.postValue("Stop cast failed: ${e.message}")
            }
        }
    }

    fun pauseCast() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.pause()
            } catch (e: Exception) {
                _error.postValue("Pause cast failed: ${e.message}")
            }
        }
    }

    fun resumeCast() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.resume()
            } catch (e: Exception) {
                _error.postValue("Resume cast failed: ${e.message}")
            }
        }
    }

    fun seekCast(position: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.seek(position)
            } catch (e: Exception) {
                _error.postValue("Seek failed: ${e.message}")
            }
        }
    }

    fun selectCastDevice(device: CastController.CastDevice) {
        _castDevice.postValue(device)
    }

    fun searchCastDevices(callback: (List<CastController.CastDevice>) -> Unit) {
        castController.searchDevices(callback)
    }

    fun setPlaybackPosition(position: Long) {
        _playbackPosition.postValue(position)
    }

    fun setVideoDuration(duration: Long) {
        _videoDuration.postValue(duration)
    }

    fun releaseStream() {
        currentSessionId?.let {
            proxyServer.unregisterStream(it)
            currentSessionId = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        releaseStream()
        castController.disconnect()
    }
}
