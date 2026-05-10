package com.vedeng.fileserver.ui.video

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.vedeng.fileserver.R
import com.vedeng.fileserver.databinding.ActivityVideoPlayerBinding
import com.vedeng.fileserver.ui.cast.CastDeviceActivity
import com.vedeng.fileserver.ui.viewmodel.VideoPlayerViewModel

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private val viewModel: VideoPlayerViewModel by viewModels()
    private var exoPlayer: ExoPlayer? = null

    private val castDeviceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val deviceName = result.data?.getStringExtra("device_name")
            val proxyUrl = result.data?.getStringExtra("proxy_url")
            deviceName?.let {
                binding.tvCastStatus.text = "投屏到: $it"
                binding.tvCastStatus.visibility = View.VISIBLE
                proxyUrl?.let { url ->
                    viewModel.setCastProxyUrl(url)
                    viewModel.startCasting()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupPlayer()
        setupObservers()
        setupListeners()

        val path = intent.getStringExtra("path") ?: return
        val name = intent.getStringExtra("name") ?: ""

        viewModel.prepareVideo(name, path, null)

        if (intent.getBooleanExtra("direct_cast", false)) {
            openCastDeviceSelection()
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra("name") ?: getString(R.string.video_player)
    }

    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            binding.progressBar.visibility = View.VISIBLE
                        }
                        Player.STATE_READY -> {
                            binding.progressBar.visibility = View.GONE
                            viewModel.updatePlaybackState(
                                isPlaying = isPlaying,
                                position = currentPosition,
                                duration = duration
                            )
                        }
                        Player.STATE_ENDED -> {
                            viewModel.updatePlaybackState(isPlaying = false, position = duration, duration = duration)
                        }
                        Player.STATE_IDLE -> {}
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.updatePlaybackState(isPlaying, currentPosition, duration)
                }
            })
        }
    }

    private fun setupObservers() {
        viewModel.videoUrl.observe(this) { url ->
            url?.let {
                val mediaItem = MediaItem.fromUri(it)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
            }
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

        viewModel.playbackPosition.observe(this) { position ->
            binding.seekBar.progress = position.toInt()
            binding.tvCurrentTime.text = formatTime(position)
        }

        viewModel.duration.observe(this) { duration ->
            binding.seekBar.max = duration.toInt()
            binding.tvTotalTime.text = formatTime(duration)
        }

        viewModel.castStatus.observe(this) { status ->
            when (status) {
                VideoPlayerViewModel.CastStatus.IDLE -> {
                    binding.btnCast.text = "投屏"
                }
                VideoPlayerViewModel.CastStatus.SEARCHING -> {
                    binding.btnCast.text = "搜索中..."
                    binding.btnCast.isEnabled = false
                }
                VideoPlayerViewModel.CastStatus.CONNECTING -> {
                    binding.btnCast.text = "连接中..."
                    binding.btnCast.isEnabled = false
                }
                VideoPlayerViewModel.CastStatus.CASTING -> {
                    binding.btnCast.text = "停止投屏"
                    binding.btnCast.isEnabled = true
                    binding.tvCastStatus.visibility = View.VISIBLE
                }
                VideoPlayerViewModel.CastStatus.ERROR -> {
                    binding.btnCast.text = "投屏"
                    binding.btnCast.isEnabled = true
                }
                null -> {}
            }
        }

        viewModel.castPosition.observe(this) { (position, duration) ->
            binding.tvCastPosition.text = "投屏进度: ${formatTime(position)} / ${formatTime(duration)}"
        }
    }

    private fun setupListeners() {
        binding.btnPlayPause.setOnClickListener {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                } else {
                    player.play()
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                }
            }
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    exoPlayer?.seekTo(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnCast.setOnClickListener {
            when (viewModel.castStatus.value) {
                VideoPlayerViewModel.CastStatus.CASTING -> {
                    viewModel.stopCasting()
                }
                else -> {
                    openCastDeviceSelection()
                }
            }
        }

        binding.btnPrevious.setOnClickListener {
            exoPlayer?.seekTo(maxOf(0, (exoPlayer?.currentPosition ?: 0) - 10000))
        }

        binding.btnNext.setOnClickListener {
            exoPlayer?.seekTo(minOf(exoPlayer?.duration ?: 0, (exoPlayer?.currentPosition ?: 0) + 10000))
        }
    }

    private fun openCastDeviceSelection() {
        val intent = Intent(this, CastDeviceActivity::class.java)
        intent.putExtra("media_url", viewModel.getLocalProxyUrl())
        intent.putExtra("media_name", viewModel.videoInfo.value?.name)
        castDeviceLauncher.launch(intent)
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

    override fun onStart() {
        super.onStart()
        exoPlayer?.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

private fun maxOf(a: Long, b: Long): Long = if (a > b) a else b
private fun minOf(a: Long, b: Long): Long = if (a < b) a else b
