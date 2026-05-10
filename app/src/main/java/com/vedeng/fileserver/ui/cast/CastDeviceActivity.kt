package com.vedeng.fileserver.ui.cast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.vedeng.fileserver.R
import com.vedeng.fileserver.databinding.ActivityCastDeviceBinding
import com.vedeng.fileserver.network.dlna.CastController
import com.vedeng.fileserver.ui.adapter.CastDeviceAdapter
import com.vedeng.fileserver.ui.viewmodel.CastDeviceViewModel

class CastDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCastDeviceBinding
    private val viewModel: CastDeviceViewModel by viewModels()
    private lateinit var deviceAdapter: CastDeviceAdapter

    private var mediaUrl: String = ""
    private var mediaName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCastDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaUrl = intent.getStringExtra("media_url") ?: ""
        mediaName = intent.getStringExtra("media_name") ?: "Video"

        setupToolbar()
        setupRecyclerView()
        setupObservers()
        setupListeners()

        viewModel.searchDevices()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.dlna_cast)
    }

    private fun setupRecyclerView() {
        deviceAdapter = CastDeviceAdapter { device ->
            viewModel.connectToDevice(device)
        }

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@CastDeviceActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupObservers() {
        viewModel.devices.observe(this) { devices ->
            deviceAdapter.submitList(devices)
            binding.emptyView.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.isSearching.observe(this) { isSearching ->
            binding.progressBar.visibility = if (isSearching) View.VISIBLE else View.GONE
            binding.btnRefresh.isEnabled = !isSearching
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.connectionStatus.observe(this) { status ->
            when (status) {
                CastDeviceViewModel.ConnectionStatus.DISCONNECTED -> {
                    binding.statusText.text = getString(R.string.not_casting)
                }
                CastDeviceViewModel.ConnectionStatus.CONNECTING -> {
                    binding.statusText.text = "正在连接..."
                }
                CastDeviceViewModel.ConnectionStatus.CONNECTED -> {
                    binding.statusText.text = getString(R.string.connection_success)
                }
                CastDeviceViewModel.ConnectionStatus.ERROR -> {
                    binding.statusText.text = getString(R.string.connect_failed)
                }
            }
        }

        viewModel.castState.observe(this) { state ->
            when (state) {
                CastDeviceViewModel.CastState.IDLE -> {
                    binding.btnPlay.isEnabled = true
                    binding.btnPause.isEnabled = false
                    binding.btnStop.isEnabled = false
                }
                CastDeviceViewModel.CastState.PLAYING -> {
                    binding.btnPlay.isEnabled = false
                    binding.btnPause.isEnabled = true
                    binding.btnStop.isEnabled = true
                    binding.statusText.text = getString(R.string.casting)
                }
                CastDeviceViewModel.CastState.PAUSED -> {
                    binding.btnPlay.isEnabled = true
                    binding.btnPause.isEnabled = false
                    binding.btnStop.isEnabled = true
                    binding.statusText.text = "已暂停"
                }
                CastDeviceViewModel.CastState.STOPPED -> {
                    binding.btnPlay.isEnabled = true
                    binding.btnPause.isEnabled = false
                    binding.btnStop.isEnabled = false
                    binding.statusText.text = getString(R.string.stop_cast)
                }
                CastDeviceViewModel.CastState.ERROR -> {
                    binding.btnPlay.isEnabled = true
                    binding.btnPause.isEnabled = false
                    binding.btnStop.isEnabled = false
                }
                null -> {}
            }
        }

        viewModel.positionInfo.observe(this) { info ->
            binding.tvPosition.text = "位置: ${formatTime(info.currentPosition)} / ${formatTime(info.duration)}"
        }
    }

    private fun setupListeners() {
        binding.btnRefresh.setOnClickListener {
            viewModel.searchDevices()
        }

        binding.btnPlay.setOnClickListener {
            viewModel.playMedia(mediaUrl, mediaName)
        }

        binding.btnPause.setOnClickListener {
            viewModel.resumeMedia()
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopMedia()
        }

        binding.btnSeekBack.setOnClickListener {
            val currentInfo = viewModel.positionInfo.value ?: return@setOnClickListener
            viewModel.seekTo(maxOf(0, currentInfo.currentPosition - 10000))
        }

        binding.btnSeekForward.setOnClickListener {
            val currentInfo = viewModel.positionInfo.value ?: return@setOnClickListener
            viewModel.seekTo(minOf(currentInfo.duration, currentInfo.currentPosition + 10000))
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onBackPressed() {
        val selectedDevice = viewModel.selectedDevice.value
        val castState = viewModel.castState.value

        if (selectedDevice != null && castState == CastDeviceViewModel.CastState.PLAYING) {
            val resultIntent = Intent().apply {
                putExtra("device_name", selectedDevice.name)
                putExtra("proxy_url", mediaUrl)
            }
            setResult(Activity.RESULT_OK, resultIntent)
        } else {
            setResult(Activity.RESULT_CANCELED)
        }

        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (viewModel.castState.value != CastDeviceViewModel.CastState.PLAYING &&
            viewModel.castState.value != CastDeviceViewModel.CastState.PAUSED) {
            viewModel.disconnectFromDevice()
        }
    }
}

private fun maxOf(a: Long, b: Long): Long = if (a > b) a else b
private fun minOf(a: Long, b: Long): Long = if (a < b) a else b
