package com.vedeng.fileserver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.vedeng.fileserver.R
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

    private fun showAddServerDialog() {
        val options = arrayOf("SMB Server", "FTP Server", "Local Storage")
        AlertDialog.Builder(this)
            .setTitle("Add Server")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSmbDialog()
                    1 -> showFtpDialog()
                    2 -> viewModel.connectToLocalStorage()
                }
            }
            .show()
    }

    private fun showSmbDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val hostInput = EditText(this).apply { hint = "Host (e.g., 192.168.1.100)" }
        val shareInput = EditText(this).apply { hint = "Share Name" }
        val domainInput = EditText(this).apply { hint = "Domain/Workgroup (optional)" }
        val usernameInput = EditText(this).apply { hint = "Username (optional)" }
        val passwordInput = EditText(this).apply { hint = "Password (optional)"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }

        layout.addView(hostInput)
        layout.addView(shareInput)
        layout.addView(domainInput)
        layout.addView(usernameInput)
        layout.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Connect to SMB")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val host = hostInput.text.toString().trim()
                val share = shareInput.text.toString().trim()
                val domain = domainInput.text.toString().trim().takeIf { it.isNotEmpty() }
                val username = usernameInput.text.toString().trim().takeIf { it.isNotEmpty() }
                val password = passwordInput.text.toString()

                if (host.isNotEmpty() && share.isNotEmpty()) {
                    viewModel.connectToSmbServer(host, share, domain, username, password)
                } else {
                    Toast.makeText(this, "Host and Share are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFtpDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val hostInput = EditText(this).apply { hint = "Host (e.g., 192.168.1.100)" }
        val portInput = EditText(this).apply { hint = "Port (default: 21)"; setText("21") }
        val usernameInput = EditText(this).apply { hint = "Username (optional)" }
        val passwordInput = EditText(this).apply { hint = "Password (optional)"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }

        layout.addView(hostInput)
        layout.addView(portInput)
        layout.addView(usernameInput)
        layout.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Connect to FTP")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().toIntOrNull() ?: 21
                val username = usernameInput.text.toString().trim().takeIf { it.isNotEmpty() }
                val password = passwordInput.text.toString()

                if (host.isNotEmpty()) {
                    viewModel.connectToFtpServer(host, port, username, password)
                } else {
                    Toast.makeText(this, "Host is required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onFileItemClick(item: FileManagerViewModel.FileItem) {
        if (item.isDirectory) {
            viewModel.navigateToFolder(item.path)
        } else {
            openFile(item)
        }
    }

    private fun onFileItemLongClick(item: FileManagerViewModel.FileItem): Boolean {
        if (!item.isDirectory) {
            showFileOptionsDialog(item)
        }
        return true
    }

    private fun openFile(item: FileManagerViewModel.FileItem) {
        val extension = item.name.substringAfterLast('.', "").lowercase()
        when {
            extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp") -> {
                val intent = Intent(this, ImagePreviewActivity::class.java).apply {
                    putExtra("image_path", item.path)
                    putStringArrayListExtra("image_list", ArrayList(viewModel.files.value?.map { it.path } ?: emptyList()))
                }
                startActivity(intent)
            }
            extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm") -> {
                val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("video_path", item.path)
                }
                startActivity(intent)
            }
            else -> {
                Toast.makeText(this, "Unsupported file type", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFileOptionsDialog(item: FileManagerViewModel.FileItem) {
        val options = arrayOf("Open", "Cast to DLNA", "Share")
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openFile(item)
                    1 -> castFile(item)
                    2 -> shareFile(item)
                }
            }
            .show()
    }

    private fun castFile(item: FileManagerViewModel.FileItem) {
        val intent = Intent(this, com.vedeng.fileserver.ui.cast.CastDeviceActivity::class.java).apply {
            putExtra("media_url", item.path)
            putExtra("media_type", if (item.name.endsWith(".mp4")) "video" else "image")
        }
        startActivity(intent)
    }

    private fun shareFile(item: FileManagerViewModel.FileItem) {
        Toast.makeText(this, "Share: ${item.name}", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_disconnect -> {
                viewModel.disconnect()
                true
            }
            R.id.action_refresh -> {
                viewModel.listFiles(viewModel.currentPath.value ?: "/")
                true
            }
            R.id.action_view_logs -> {
                startActivity(Intent(this, LogViewerActivity::class.java))
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
