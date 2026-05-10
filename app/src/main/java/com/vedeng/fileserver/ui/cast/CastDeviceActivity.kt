package com.vedeng.fileserver.ui.cast

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.vedeng.fileserver.databinding.ActivityCastDeviceBinding
import com.vedeng.fileserver.network.dlna.CastController
import com.vedeng.fileserver.ui.viewmodel.CastDeviceViewModel

class CastDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCastDeviceBinding
    private val viewModel: CastDeviceViewModel by viewModels()
    private lateinit var deviceAdapter: CastDeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCastDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupObservers()
        setupListeners()

        viewModel.searchDevices()
    }

    private fun setupRecyclerView() {
        deviceAdapter = CastDeviceAdapter { device ->
            viewModel.selectDevice(device)
            binding.btnCast.isEnabled = true
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
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.selectedDevice.observe(this) { device ->
            device?.let {
                binding.btnCast.text = "Cast to ${it.name}"
            }
        }
    }

    private fun setupListeners() {
        binding.btnCast.setOnClickListener {
            val mediaUrl = intent.getStringExtra("media_url") ?: return@setOnClickListener
            val mediaType = intent.getStringExtra("media_type") ?: "video"
            val title = mediaUrl.substringAfterLast('/')

            viewModel.startCasting(mediaUrl, if (mediaType == "video") "video/mp4" else "image/*", title)
            Toast.makeText(this, "Casting started", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnSearch.setOnClickListener {
            viewModel.searchDevices()
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopCasting()
            Toast.makeText(this, "Casting stopped", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.disconnect()
    }
}
