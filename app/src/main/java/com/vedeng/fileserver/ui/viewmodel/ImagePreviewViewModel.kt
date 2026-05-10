package com.vedeng.fileserver.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vedeng.fileserver.FileServerApp
import com.vedeng.fileserver.data.model.FileItem
import com.vedeng.fileserver.network.ftp.FtpClient
import com.vedeng.fileserver.network.smb.SmbClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

class ImagePreviewViewModel(application: Application) : AndroidViewModel(application) {

    private val _currentBitmap = MutableLiveData<Bitmap?>()
    val currentBitmap: LiveData<Bitmap?> = _currentBitmap

    private val _currentIndex = MutableLiveData<Int>()
    val currentIndex: LiveData<Int> = _currentIndex

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isSlideshowRunning = MutableLiveData<Boolean>()
    val isSlideshowRunning: LiveData<Boolean> = _isSlideshowRunning

    private val _slideshowInterval = MutableLiveData<Int>(3000)
    val slideshowInterval: LiveData<Int> = _slideshowInterval

    private val _imageInfo = MutableLiveData<ImageInfo?>()
    val imageInfo: LiveData<ImageInfo?> = _imageInfo

    private var imageList: List<ImageItem> = emptyList()
    private var slideshowJob: Job? = null
    private val cacheManager = FileServerApp.instance.cacheManager

    data class ImageItem(
        val name: String,
        val path: String,
        val sourceType: SourceType,
        val serverConfig: com.vedeng.fileserver.data.model.ServerConfig? = null
    )

    data class ImageInfo(
        val name: String,
        val size: String,
        val resolution: String,
        val path: String
    )

    enum class SourceType {
        LOCAL, SMB, FTP, CACHE, URL
    }

    fun setImageList(images: List<ImageItem>, startIndex: Int = 0) {
        imageList = images
        _currentIndex.value = startIndex.coerceIn(0, images.size - 1)
        loadCurrentImage()
    }

    fun loadCurrentImage() {
        val index = _currentIndex.value ?: return
        if (index < 0 || index >= imageList.size) return

        loadImage(imageList[index])
    }

    fun loadImage(imageItem: ImageItem) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val cachedFile = cacheManager.getCachedFile(imageItem.path)
                if (cachedFile != null && cachedFile.exists()) {
                    loadBitmapFromFile(cachedFile, imageItem)
                    return@launch
                }

                when (imageItem.sourceType) {
                    SourceType.LOCAL -> loadLocalImage(imageItem)
                    SourceType.SMB -> loadSmbImage(imageItem)
                    SourceType.FTP -> loadFtpImage(imageItem)
                    SourceType.CACHE -> loadCachedImage(imageItem)
                    SourceType.URL -> loadUrlImage(imageItem.path)
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadBitmapFromFile(file: File, imageItem: ImageItem) {
        withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)

                val maxWidth = 1920
                val maxHeight = 1080
                var sampleSize = 1

                while (options.outWidth / sampleSize > maxWidth * 2 ||
                    options.outHeight / sampleSize > maxHeight * 2
                ) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }

                val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)

                withContext(Dispatchers.Main) {
                    _currentBitmap.value = bitmap
                    _imageInfo.value = ImageInfo(
                        name = imageItem.name,
                        size = formatFileSize(file.length()),
                        resolution = if (bitmap != null) "${bitmap.width} x ${bitmap.height}" else "Unknown",
                        path = file.absolutePath
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = "Failed to load image: ${e.message}"
                }
            }
        }
    }

    private suspend fun loadLocalImage(imageItem: ImageItem) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(imageItem.path)
                if (file.exists()) {
                    loadBitmapFromFile(file, imageItem)
                } else {
                    cacheAndLoadImage(imageItem)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message
                }
            }
        }
    }

    private suspend fun loadSmbImage(imageItem: ImageItem) {
        withContext(Dispatchers.IO) {
            try {
                val config = imageItem.serverConfig ?: return@withContext
                val smbClient = SmbClient(getApplication())
                val connectResult = if (config.anonymous) {
                    smbClient.connectAnonymous(config.host, config.share ?: "")
                } else {
                    smbClient.connect(config.host, config.share ?: "", config.username, config.password)
                }

                if (connectResult.isFailure) {
                    throw connectResult.exceptionOrNull() ?: Exception("Connection failed")
                }

                val streamResult = smbClient.openInputStream(imageItem.path)
                if (streamResult.isFailure) {
                    throw streamResult.exceptionOrNull() ?: Exception("Failed to open stream")
                }

                val tempFile = File(getApplication<Application>().cacheDir, "temp_${System.currentTimeMillis()}.jpg")
                tempFile.outputStream().use { output ->
                    streamResult.getOrNull()?.copyTo(output)
                }

                loadBitmapFromFile(tempFile, imageItem)
                smbClient.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message
                }
            }
        }
    }

    private suspend fun loadFtpImage(imageItem: ImageItem) {
        withContext(Dispatchers.IO) {
            try {
                val config = imageItem.serverConfig ?: return@withContext
                val ftpClient = FtpClient(
                    config.host,
                    config.port,
                    config.username ?: "anonymous",
                    config.password ?: ""
                )

                val connectResult = ftpClient.connect()
                if (connectResult.isFailure) {
                    throw connectResult.exceptionOrNull() ?: Exception("Connection failed")
                }

                val path = imageItem.path
                val sizeResult = ftpClient.getFileSize(path)
                val size = sizeResult.getOrNull() ?: 0L

                val cacheResult = cacheManager.cacheFile(path) {
                    val streamResult = ftpClient.downloadStream(path)
                    streamResult.getOrNull() ?: throw Exception("Failed to download")
                }

                if (cacheResult.isSuccess) {
                    loadBitmapFromFile(cacheResult.getOrNull()!!, imageItem)
                } else {
                    throw cacheResult.exceptionOrNull() ?: Exception("Cache failed")
                }

                ftpClient.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message
                }
            }
        }
    }

    private suspend fun loadCachedImage(imageItem: ImageItem) {
        val cachedFile = cacheManager.getCachedFile(imageItem.path)
        if (cachedFile != null && cachedFile.exists()) {
            loadBitmapFromFile(cachedFile, imageItem)
        } else {
            cacheAndLoadImage(imageItem)
        }
    }

    private suspend fun loadUrlImage(url: String) {
        withContext(Dispatchers.IO) {
            try {
                val cacheResult = cacheManager.cacheFile(url) {
                    URL(url).openStream()
                }

                if (cacheResult.isSuccess) {
                    loadBitmapFromFile(cacheResult.getOrNull()!!, ImageItem("", url, SourceType.URL))
                } else {
                    val bitmap = BitmapFactory.decodeStream(URL(url).openStream())
                    withContext(Dispatchers.Main) {
                        _currentBitmap.value = bitmap
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message
                }
            }
        }
    }

    private suspend fun cacheAndLoadImage(imageItem: ImageItem) {
        withContext(Dispatchers.IO) {
            try {
                when (imageItem.sourceType) {
                    SourceType.SMB -> {
                        val config = imageItem.serverConfig ?: return@withContext
                        val smbClient = SmbClient(getApplication())
                        val result = if (config.anonymous) {
                            smbClient.connectAnonymous(config.host, config.share ?: "")
                        } else {
                            smbClient.connect(config.host, config.share ?: "", config.username, config.password)
                        }

                        if (result.isSuccess) {
                            val streamResult = smbClient.openInputStream(imageItem.path)
                            if (streamResult.isSuccess) {
                                cacheManager.cacheFile(imageItem.path) {
                                    streamResult.getOrNull()!!
                                }
                                val cached = cacheManager.getCachedFile(imageItem.path)
                                if (cached != null) {
                                    loadBitmapFromFile(cached, imageItem)
                                }
                            }
                        }
                        smbClient.disconnect()
                    }
                    SourceType.FTP -> {
                        val config = imageItem.serverConfig ?: return@withContext
                        val ftpClient = FtpClient(config.host, config.port, config.username ?: "anonymous", config.password ?: "")
                        if (ftpClient.connect().isSuccess) {
                            val streamResult = ftpClient.downloadStream(imageItem.path)
                            if (streamResult.isSuccess) {
                                cacheManager.cacheFile(imageItem.path) {
                                    streamResult.getOrNull()!!
                                }
                                val cached = cacheManager.getCachedFile(imageItem.path)
                                if (cached != null) {
                                    loadBitmapFromFile(cached, imageItem)
                                }
                            }
                        }
                        ftpClient.disconnect()
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _error.value = e.message
                }
            }
        }
    }

    fun nextImage() {
        val current = _currentIndex.value ?: 0
        if (current < imageList.size - 1) {
            _currentIndex.value = current + 1
            loadCurrentImage()
        }
    }

    fun previousImage() {
        val current = _currentIndex.value ?: 0
        if (current > 0) {
            _currentIndex.value = current - 1
            loadCurrentImage()
        }
    }

    fun goToImage(index: Int) {
        if (index in 0 until imageList.size) {
            _currentIndex.value = index
            loadCurrentImage()
        }
    }

    fun startSlideshow() {
        if (imageList.size <= 1) return

        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            _isSlideshowRunning.value = true
            while (isActive && _isSlideshowRunning.value == true) {
                delay(_slideshowInterval.value?.toLong() ?: 3000L)
                if (_isSlideshowRunning.value == true) {
                    nextImage()
                }
            }
        }
    }

    fun stopSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = null
        _isSlideshowRunning.value = false
    }

    fun toggleSlideshow() {
        if (_isSlideshowRunning.value == true) {
            stopSlideshow()
        } else {
            startSlideshow()
        }
    }

    fun setSlideshowInterval(intervalMs: Int) {
        _slideshowInterval.value = intervalMs
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }

    fun getImageCount(): Int = imageList.size

    override fun onCleared() {
        super.onCleared()
        stopSlideshow()
    }
}
