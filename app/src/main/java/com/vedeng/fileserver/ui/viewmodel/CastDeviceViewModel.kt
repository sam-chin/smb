package com.vedeng.fileserver.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.vedeng.fileserver.network.dlna.CastController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CastDeviceViewModel(application: Application) : AndroidViewModel(application) {

    private val _devices = MutableLiveData<List<CastController.CastDevice>>()
    val devices: LiveData<List<CastController.CastDevice>> = _devices

    private val _isSearching = MutableLiveData<Boolean>()
    val isSearching: LiveData<Boolean> = _isSearching

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _selectedDevice = MutableLiveData<CastController.CastDevice?>()
    val selectedDevice: LiveData<CastController.CastDevice?> = _selectedDevice

    private val _connectionStatus = MutableLiveData<ConnectionStatus>()
    val connectionStatus: LiveData<ConnectionStatus> = _connectionStatus

    private val _castState = MutableLiveData<CastState>()
    val castState: LiveData<CastState> = _castState

    private val _positionInfo = MutableLiveData<PositionInfo>()
    val positionInfo: LiveData<PositionInfo> = _positionInfo

    private var castController: CastController? = null
    private var positionUpdateJob: Job? = null

    enum class ConnectionStatus {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    enum class CastState {
        IDLE, PLAYING, PAUSED, STOPPED, ERROR
    }

    data class PositionInfo(
        val currentPosition: Long,
        val duration: Long
    )

    init {
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _castState.value = CastState.IDLE
        _isSearching.value = false
    }

    fun searchDevices() {
        viewModelScope.launch {
            _isSearching.value = true
            _devices.value = emptyList()
            _error.value = null

            try {
                val controller = CastController()
                val result = controller.searchDevices(5000)

                result.fold(
                    onSuccess = { foundDevices ->
                        _devices.value = foundDevices
                    },
                    onFailure = { e ->
                        _error.value = e.message
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun connectToDevice(device: CastController.CastDevice) {
        viewModelScope.launch {
            _selectedDevice.value = device
            _connectionStatus.value = ConnectionStatus.CONNECTING
            _error.value = null

            try {
                castController = CastController()
                val result = castController!!.connectDevice(device)

                result.fold(
                    onSuccess = {
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                    },
                    onFailure = { e ->
                        _error.value = e.message
                        _connectionStatus.value = ConnectionStatus.ERROR
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message
                _connectionStatus.value = ConnectionStatus.ERROR
            }
        }
    }

    fun disconnectFromDevice() {
        viewModelScope.launch {
            castController?.disconnect()
            castController = null
            _selectedDevice.value = null
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            _castState.value = CastState.IDLE
            positionUpdateJob?.cancel()
        }
    }

    fun playMedia(mediaUrl: String, mediaTitle: String, mimeType: String = "video/*") {
        viewModelScope.launch {
            val controller = castController
            if (controller == null) {
                _error.value = "No device connected"
                return@launch
            }

            try {
                val result = controller.play(mediaUrl, mediaTitle, mimeType)

                result.fold(
                    onSuccess = {
                        _castState.value = CastState.PLAYING
                        startPositionUpdates()
                    },
                    onFailure = { e ->
                        _error.value = e.message
                        _castState.value = CastState.ERROR
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message
                _castState.value = CastState.ERROR
            }
        }
    }

    fun pauseMedia() {
        viewModelScope.launch {
            try {
                castController?.pause()
                _castState.value = CastState.PAUSED
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun resumeMedia() {
        viewModelScope.launch {
            try {
                castController?.resume()
                _castState.value = CastState.PLAYING
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun stopMedia() {
        viewModelScope.launch {
            try {
                castController?.stop()
                _castState.value = CastState.STOPPED
                positionUpdateJob?.cancel()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun seekTo(positionMs: Long) {
        viewModelScope.launch {
            try {
                castController?.seek(positionMs)
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val result = castController?.getPositionInfo()
                    if (result?.isSuccess == true) {
                        val (position, duration) = result.getOrNull()!!
                        _positionInfo.value = PositionInfo(position, duration)
                    }

                    val stateResult = castController?.getTransportInfo()
                    if (stateResult?.isSuccess == true) {
                        val state = stateResult.getOrNull()
                        _castState.value = when {
                            state?.contains("PLAYING", ignoreCase = true) == true -> CastState.PLAYING
                            state?.contains("PAUSED", ignoreCase = true) == true -> CastState.PAUSED
                            else -> _castState.value ?: CastState.IDLE
                        }
                    }
                } catch (e: Exception) {
                }
                delay(1000)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        positionUpdateJob?.cancel()
        castController?.disconnect()
    }
}
