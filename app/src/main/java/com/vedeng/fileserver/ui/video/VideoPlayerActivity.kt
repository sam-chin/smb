package com.vedeng.fileserver.ui.video

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.vedeng.fileserver.databinding.ActivityVideoPlayerBinding
import com.vedeng.fileserver.ui.viewmodel.VideoPlayerViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import android.content.Intent

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private val viewModel: VideoPlayerViewModel by viewModels()
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var playbackPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupObservers()
        setupListeners()

        val videoPath = intent.getStringExtra("video_path")
        if (videoPath != null) {
            viewModel.setVideoPath(videoPath)
            prepareVideo(videoPath)
        }
    }

    private fun setupObservers() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.videoDuration.observe(this) { duration ->
            binding.playerView.visibility = if (duration > 0) View.VISIBLE else View.GONE
        }

        viewModel.isCasting.observe(this) { isCasting ->
            binding.castStatusText.visibility = if (isCasting) View.VISIBLE else View.GONE
        }
    }

    private fun setupListeners() {
        binding.btnCast.setOnClickListener {
            val videoUrl = viewModel.videoPath.value
            if (videoUrl != null) {
                val intent = Intent(this, com.vedeng.fileserver.ui.cast.CastDeviceActivity::class.java).apply {
                    putExtra("media_url", videoUrl)
                    putExtra("media_type", "video")
                }
                startActivity(intent)
            }
        }

        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun prepareVideo(path: String) {
        viewModel.isLoading.value = true

        val cachedUrl = viewModel.getCachedVideoUrl(path)
        if (cachedUrl != null) {
            initializePlayer(cachedUrl)
            return
        }

        viewModel.cacheVideoForLocalPlayback(
            sourceStreamProvider = {
                viewModel.videoPath.value?.let { p ->
                    java.io.File(p).inputStream()
                } ?: throw Exception("No video path")
            },
            remotePath = path
        )

        viewModel.localUrl.observe(this) { url ->
            url?.let {
                initializePlayer(it)
            }
        }
    }

    private fun initializePlayer(videoUrl: String) {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer
            val mediaItem = MediaItem.fromUri(videoUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.playWhenReady = playWhenReady
            exoPlayer.seekTo(playbackPosition)
            exoPlayer.prepare()

            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        androidx.media3.common.Player.STATE_READY -> {
                            viewModel.isLoading.value = false
                            viewModel.setVideoDuration(exoPlayer.duration)
                        }
                        androidx.media3.common.Player.STATE_ENDED -> {
                            viewModel.setPlaybackPosition(0)
                        }
                    }
                }
            })
        }
        viewModel.isLoading.value = false
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (player == null) {
            viewModel.videoPath.value?.let { prepareVideo(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            playbackPosition = it.currentPosition
            playWhenReady = it.playWhenReady
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let {
            playbackPosition = it.currentPosition
            playWhenReady = it.playWhenReady
            it.release()
        }
        player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.releaseStream()
        releasePlayer()
    }
}
