package com.vedeng.fileserver.ui.image

import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.View
import android.view.GestureDetector
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.vedeng.fileserver.databinding.ActivityImagePreviewBinding
import com.vedeng.fileserver.ui.viewmodel.ImagePreviewViewModel
import android.content.Intent
import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min

class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImagePreviewBinding
    private val viewModel: ImagePreviewViewModel by viewModels()

    private var scaleFactor = 1.0f

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupGestureDetectors()
        setupObservers()
        setupListeners()

        val imagePath = intent.getStringExtra("image_path")
        val imageList = intent.getStringArrayListExtra("image_list") ?: arrayListOf()

        if (imagePath != null) {
            viewModel.setImageFiles(imageList)
            val index = imageList.indexOf(imagePath)
            if (index >= 0) {
                viewModel.setCurrentIndex(index)
            }
        }
    }

    private fun setupGestureDetectors() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = max(0.5f, min(scaleFactor, 5.0f))
                binding.photoView.scaleX = scaleFactor
                binding.photoView.scaleY = scaleFactor
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                scaleFactor = if (scaleFactor > 1.0f) 1.0f else 2.0f
                binding.photoView.scaleX = scaleFactor
                binding.photoView.scaleY = scaleFactor
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (kotlin.math.abs(diffX) > 100) {
                    if (diffX > 0) {
                        viewModel.previousImage()
                    } else {
                        viewModel.nextImage()
                    }
                    return true
                }
                return false
            }
        })
    }

    private fun setupObservers() {
        viewModel.currentImagePath.observe(this) { path ->
            loadImage(path)
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }

        viewModel.currentIndex.observe(this) { index ->
            updateIndexDisplay(index)
        }

        viewModel.slideshowEnabled.observe(this) { enabled ->
            binding.btnSlideshow.text = if (enabled) "Pause" else "Slideshow"
        }
    }

    private fun setupListeners() {
        binding.root.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }

        binding.btnPrevious.setOnClickListener {
            viewModel.previousImage()
        }

        binding.btnNext.setOnClickListener {
            viewModel.nextImage()
        }

        binding.btnSlideshow.setOnClickListener {
            viewModel.toggleSlideshow()
        }

        binding.btnCast.setOnClickListener {
            viewModel.currentImagePath.value?.let { path ->
                val intent = Intent(this, com.vedeng.fileserver.ui.cast.CastDeviceActivity::class.java).apply {
                    putExtra("media_url", path)
                    putExtra("media_type", "image")
                }
                startActivity(intent)
            }
        }
    }

    private fun loadImage(path: String) {
        binding.loadingProgress.visibility = View.VISIBLE
        val cachedUrl = viewModel.getCachedImageUrl(path)
        if (cachedUrl != null) {
            binding.photoView.setImageURI(android.net.Uri.parse(cachedUrl))
            binding.loadingProgress.visibility = View.GONE
        } else {
            viewModel.cacheImageForLocalPreview(
                sourceStreamProvider = {
                    viewModel.currentImagePath.value?.let { p ->
                        java.io.File(p).inputStream()
                    } ?: throw Exception("No image path")
                },
                remotePath = path
            )
            viewModel.localUrl.observe(this) { url ->
                url?.let {
                    binding.photoView.setImageURI(android.net.Uri.parse(it))
                    binding.loadingProgress.visibility = View.GONE
                }
            }
        }
    }

    private fun updateIndexDisplay(index: Int) {
        supportActionBar?.title = "${index + 1} / ${viewModel.imageFiles.value?.size ?: 0}"
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.releaseStream()
    }
}
