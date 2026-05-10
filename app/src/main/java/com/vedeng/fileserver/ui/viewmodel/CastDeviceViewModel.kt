package com.vedeng.fileserver.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vedeng.fileserver.network.dlna.CastController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CastDeviceViewModel(application: Application) : AndroidViewModel(application) {

    private val castController = CastController()

    private val _devices = MutableLiveData<List<CastController.CastDevice>>()
    val devices: LiveData<List<CastController.CastDevice>> = _devices

    private val _isSearching = MutableLiveData<Boolean>()
    val isSearching: LiveData<Boolean> = _isSearching

    private val _selectedDevice = MutableLiveData<CastController.CastDevice?>()
    val selectedDevice: LiveData<CastController.CastDevice?> = _selectedDevice

    private val _isCasting = MutableLiveData<Boolean>()
    val isCasting: LiveData<Boolean> = _isCasting

    private val _castPosition = MutableLiveData<Long>()
    val castPosition: LiveData<Long> = _castPosition

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun searchDevices() {
        _isSearching.postValue(true)
        castController.searchDevices { deviceList ->
            _devices.postValue(deviceList)
            _isSearching.postValue(false)
        }
    }

    fun selectDevice(device: CastController.CastDevice) {
        _selectedDevice.postValue(device)
        castController.selectDevice(device)
    }

    fun startCasting(mediaUrl: String, mediaType: String = "video/mp4", title: String = "Video") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val device = _selectedDevice.value
                if (device != null) {
                    castController.selectDevice(device)
                    castController.play(mediaUrl, mediaType, title)
                    _isCasting.postValue(true)
                }
            } catch (e: Exception) {
                _error.postValue("Failed to start casting: ${e.message}")
            }
        }
    }

    fun stopCasting() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.stop()
                _isCasting.postValue(false)
            } catch (e: Exception) {
                _error.postValue("Failed to stop casting: ${e.message}")
            }
        }
    }

    fun pauseCasting() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.pause()
            } catch (e: Exception) {
                _error.postValue("Failed to pause: ${e.message}")
            }
        }
    }

    fun resumeCasting() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.resume()
            } catch (e: Exception) {
                _error.postValue("Failed to resume: ${e.message}")
            }
        }
    }

    fun seekCasting(position: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.seek(position)
                _castPosition.postValue(position)
            } catch (e: Exception) {
                _error.postValue("Failed to seek: ${e.message}")
            }
        }
    }

    fun setVolume(volume: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                castController.setVolume(volume)
            } catch (e: Exception) {
                _error.postValue("Failed to set volume: ${e.message}")
            }
        }
    }

    fun getCurrentPosition(): Long {
        return castController.getPosition()
    }

    fun getMediaDuration(): Long {
        return castController.getDuration()
    }

    fun isPlaying(): Boolean {
        return castController.isCurrentlyPlaying()
    }

    fun disconnect() {
        castController.disconnect()
        _selectedDevice.postValue(null)
        _isCasting.postValue(false)
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
