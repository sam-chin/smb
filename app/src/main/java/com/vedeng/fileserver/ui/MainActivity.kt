package com.vedeng.fileserver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.vedeng.fileserver.R
import com.vedeng.fileserver.data.model.FileItem
import com.vedeng.fileserver.data.model.MediaType
import com.vedeng.fileserver.data.model.ServerConfig
import com.vedeng.fileserver.data.model.ServerType
import com.vedeng.fileserver.databinding.ActivityMainBinding
import com.vedeng.fileserver.ui.adapter.FileAdapter
import com.vedeng.fileserver.ui.image.ImagePreviewActivity
import com.vedeng.fileserver.ui.video.VideoPlayerActivity
import com.vedeng.fileserver.ui.viewmodel.FileManagerViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: FileManagerViewModel by viewModels()
    private lateinit var fileAdapter: FileAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            viewModel.connectToLocalStorage()
        } else {
            Toast.makeText(this, "Storage permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.file_manager)

        setupRecyclerView()
        setupObservers()
        setupListeners()

        checkPermissionsAndConnect()
    }

    private fun setupRecyclerView() {
        fileAdapter = FileAdapter(
            onItemClick = { item -> onFileItemClick(item) },
            onItemLongClick = { item -> onFileItemLongClick(item) }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = fileAdapter
        }
    }

    private fun setupObservers() {
        viewModel.files.observe(this) { files ->
            fileAdapter.submitList(files)
            binding.emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.currentPath.observe(this) { path ->
            supportActionBar?.subtitle = path
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.connectionStatus.observe(this) { status ->
            when (status) {
                FileManagerViewModel.ConnectionStatus.DISCONNECTED -> {
                    binding.statusText.text = getString(R.string.disconnect)
                }
                FileManagerViewModel.ConnectionStatus.CONNECTING -> {
                    binding.statusText.text = getString(R.string.loading)
                }
                FileManagerViewModel.ConnectionStatus.CONNECTED -> {
                    binding.statusText.text = getString(R.string.connection_success)
                }
                FileManagerViewModel.ConnectionStatus.ERROR -> {
                    binding.statusText.text = getString(R.string.connect_failed)
                }
            }
        }
    }

    private fun setupListeners() {
        binding.fabAddServer.setOnClickListener {
            showAddServerDialog()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.files.value?.let {
                viewModel.listFiles(viewModel.currentPath.value ?: "/")
            }
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun checkPermissionsAndConnect() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            viewModel.connectToLocalStorage()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun onFileItemClick(item: FileItem) {
        when (item) {
            is FileItem.LocalFile, is FileItem.SmbFile, is FileItem.FtpFile -> {
                if (item.isDirectory) {
                    viewModel.navigateToFolder(item.path)
                } else {
                    openMediaFile(item)
                }
            }
        }
    }

    private fun openMediaFile(item: FileItem) {
        val mediaType = getMediaType(item.name)

        when (mediaType) {
            MediaType.IMAGE -> {
                val intent = Intent(this, ImagePreviewActivity::class.java)
                intent.putExtra("path", item.path)
                intent.putExtra("name", item.name)
                val images = viewModel.files.value
                    ?.filter { !it.isDirectory && getMediaType(it.name) == MediaType.IMAGE }
                    ?.map { it.path }
                    ?: listOf(item.path)
                intent.putStringArrayListExtra("imageList", ArrayList(images))
                intent.putExtra("index", images.indexOf(item.path))
                startActivity(intent)
            }
            MediaType.VIDEO -> {
                val intent = Intent(this, VideoPlayerActivity::class.java)
                intent.putExtra("path", item.path)
                intent.putExtra("name", item.name)
                startActivity(intent)
            }
            else -> {
                Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getMediaType(fileName: String): MediaType {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp" -> MediaType.IMAGE
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v" -> MediaType.VIDEO
            "mp3", "wav", "aac", "flac", "ogg", "m4a" -> MediaType.AUDIO
            "pdf", "txt", "doc", "docx", "xls", "xlsx" -> MediaType.DOCUMENT
            else -> MediaType.OTHER
        }
    }

    private fun onFileItemLongClick(item: FileItem) {
        if (!item.isDirectory) {
            showFileOptionsDialog(item)
        }
    }

    private fun showFileOptionsDialog(item: FileItem) {
        val options = arrayOf("预览", "DLNA 投屏")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openMediaFile(item)
                    1 -> {
                        val intent = Intent(this, VideoPlayerActivity::class.java)
                        intent.putExtra("path", item.path)
                        intent.putExtra("name", item.name)
                        intent.putExtra("direct_cast", true)
                        startActivity(intent)
                    }
                }
            }
            .show()
    }

    private fun showAddServerDialog() {
        val options = arrayOf("SMB (Windows 共享)", "FTP", "本地存储")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_server))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSmbDialog()
                    1 -> showFtpDialog()
                    2 -> checkPermissionsAndConnect()
                }
            }
            .show()
    }

    private fun showSmbDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_smb_connect, null)
        val etAddress = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAddress)
        val etShare = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etShare)
        val etUsername = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPassword)
        val cbAnonymous = dialogView.findViewById<android.widget.CheckBox>(R.id.cbAnonymous)

        cbAnonymous.setOnCheckedChangeListener { _, isChecked ->
            etUsername.isEnabled = !isChecked
            etPassword.isEnabled = !isChecked
        }

        AlertDialog.Builder(this)
            .setTitle("连接 SMB 服务器")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.connect)) { _, _ ->
                val address = etAddress.text.toString()
                val share = etShare.text.toString()
                val username = etUsername.text.toString()
                val password = etPassword.text.toString()
                val anonymous = cbAnonymous.isChecked

                if (address.isNotEmpty() && share.isNotEmpty()) {
                    val config = ServerConfig(
                        id = "smb_${System.currentTimeMillis()}",
                        name = "SMB: $address",
                        type = ServerType.SMB,
                        host = address,
                        share = share,
                        username = if (anonymous) null else username,
                        password = if (anonymous) null else password,
                        anonymous = anonymous
                    )
                    viewModel.connectToSmbServer(config)
                } else {
                    Toast.makeText(this, "Please fill required fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showFtpDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ftp_connect, null)
        val etHost = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etHost)
        val etPort = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPort)
        val etUsername = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etUsername)
        val etPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPassword)
        val cbAnonymous = dialogView.findViewById<android.widget.CheckBox>(R.id.cbAnonymous)

        etPort.setText("21")

        cbAnonymous.setOnCheckedChangeListener { _, isChecked ->
            etUsername.isEnabled = !isChecked
            etPassword.isEnabled = !isChecked
        }

        AlertDialog.Builder(this)
            .setTitle("连接 FTP 服务器")
            .setView(dialogView)
            .setPositiveButton(getString(R.string.connect)) { _, _ ->
                val host = etHost.text.toString()
                val port = etPort.text.toString().toIntOrNull() ?: 21
                val username = etUsername.text.toString()
                val password = etPassword.text.toString()
                val anonymous = cbAnonymous.isChecked

                if (host.isNotEmpty()) {
                    val config = ServerConfig(
                        id = "ftp_${System.currentTimeMillis()}",
                        name = "FTP: $host",
                        type = ServerType.FTP,
                        host = host,
                        port = port,
                        username = if (anonymous) "anonymous" else username,
                        password = if (anonymous) "" else password,
                        anonymous = anonymous
                    )
                    viewModel.connectToFtpServer(config)
                } else {
                    Toast.makeText(this, "Please fill required fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                if (!viewModel.navigateUp()) {
                    finish()
                }
                true
            }
            R.id.action_refresh -> {
                viewModel.listFiles(viewModel.currentPath.value ?: "/")
                true
            }
            R.id.action_disconnect -> {
                viewModel.disconnect()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!viewModel.navigateUp()) {
            super.onBackPressed()
        }
    }
}
