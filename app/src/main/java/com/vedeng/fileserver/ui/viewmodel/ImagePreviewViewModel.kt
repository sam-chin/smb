package com.vedeng.fileserver.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vedeng.fileserver.proxy.CacheManager
import com.vedeng.fileserver.proxy.ProxyServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

class ImagePreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val cacheManager = CacheManager.getInstance(application)
    private val proxyServer = ProxyServer.getInstance(application, cacheManager)

    private val _currentImagePath = MutableLiveData<String>()
    val currentImagePath: LiveData<String> = _currentImagePath

    private val _imageFiles = MutableLiveData<List<String>>()
    val imageFiles: LiveData<List<String>> = _imageFiles

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _currentIndex = MutableLiveData<Int>()
    val currentIndex: LiveData<Int> = _currentIndex

    private val _slideshowEnabled = MutableLiveData<Boolean>()
    val slideshowEnabled: LiveData<Boolean> = _slideshowEnabled

    private val _localUrl = MutableLiveData<String>()
    val localUrl: LiveData<String> = _localUrl

    private var currentSessionId: String? = null

    init {
        _slideshowEnabled.value = false
        _currentIndex.value = 0
    }

    fun setImageFiles(files: List<String>) {
        _imageFiles.value = files
    }

    fun setCurrentIndex(index: Int) {
        _currentIndex.value = index
        _imageFiles.value?.getOrNull(index)?.let {
            _currentImagePath.value = it
        }
    }

    fun cacheImageForLocalPreview(
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
                val url = "http://127.0.0.1:${ProxyServer.getLocalPortValue()}/stream/$sessionId/${cachedFile.name}"
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

    fun cacheImageForCast(
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

    fun getCachedImageUrl(remotePath: String): String? {
        val cachedFile = cacheManager.getCachedFile(remotePath)
        return if (cachedFile != null && cachedFile.exists()) {
            "file://${cachedFile.absolutePath}"
        } else null
    }

    fun preloadImages(startIndex: Int, count: Int = 5) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = _imageFiles.value ?: return@launch
            for (i in startIndex until minOf(startIndex + count, files.size)) {
                val path = files[i]
                if (cacheManager.getCachedFile(path) == null) {
                    // Preload logic would go here
                }
            }
        }
    }

    fun toggleSlideshow() {
        _slideshowEnabled.value = !(_slideshowEnabled.value ?: false)
    }

    fun nextImage() {
        val files = _imageFiles.value ?: return
        val current = _currentIndex.value ?: 0
        if (current < files.size - 1) {
            setCurrentIndex(current + 1)
        }
    }

    fun previousImage() {
        val current = _currentIndex.value ?: 0
        if (current > 0) {
            setCurrentIndex(current - 1)
        }
    }

    fun clearError() {
        _error.value = null
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
    }
}
