package com.vedeng.fileserver.ui.image

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import com.vedeng.fileserver.R
import com.vedeng.fileserver.databinding.ActivityImagePreviewBinding
import com.vedeng.fileserver.ui.viewmodel.ImagePreviewViewModel
import kotlin.math.max
import kotlin.math.min

class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImagePreviewBinding
    private val viewModel: ImagePreviewViewModel by viewModels()

    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetectorCompat
    private var currentScale = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        setupToolbar()
        setupGestureDetectors()
        setupObservers()
        setupListeners()

        loadImages()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra("name") ?: getString(R.string.image_preview)
    }

    private fun setupGestureDetectors() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                currentScale *= scaleFactor
                currentScale = max(0.5f, min(currentScale, 5.0f))
                binding.imageView.scaleX = currentScale
                binding.imageView.scaleY = currentScale
                return true
            }
        })

        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.0f) {
                    currentScale = 1.0f
                    translateX = 0f
                    translateY = 0f
                    binding.imageView.scaleX = currentScale
                    binding.imageView.scaleY = currentScale
                    binding.imageView.translationX = translateX
                    binding.imageView.translationY = translateY
                } else {
                    currentScale = 2.5f
                    binding.imageView.scaleX = currentScale
                    binding.imageView.scaleY = currentScale
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (currentScale > 1.0f) {
                    translateX -= distanceX
                    translateY -= distanceY
                    binding.imageView.translationX = translateX
                    binding.imageView.translationY = translateY
                }
                return true
            }
        })
    }

    private fun setupObservers() {
        viewModel.currentBitmap.observe(this) { bitmap ->
            binding.imageView.setImageBitmap(bitmap)
            resetTransform()
            binding.loadingView.visibility = View.GONE
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.loadingView.visibility = if (isLoading) View.VISIBLE else View.GONE
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

        viewModel.isSlideshowRunning.observe(this) { isRunning ->
            binding.btnSlideshow.text = if (isRunning) getString(R.string.pause) else getString(R.string.slideshow)
        }

        viewModel.imageInfo.observe(this) { info ->
            info?.let {
                supportActionBar?.subtitle = "${it.resolution} | ${it.size}"
            }
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
    }

    private fun loadImages() {
        val path = intent.getStringExtra("path") ?: return
        val name = intent.getStringExtra("name") ?: ""
        val imageList = intent.getStringArrayListExtra("imageList") ?: arrayListOf(path)
        val index = intent.getIntExtra("index", 0)

        val items = imageList.mapIndexed { i, imagePath ->
            ImagePreviewViewModel.ImageItem(
                name = if (i == index) name else imagePath.substringAfterLast('/'),
                path = imagePath,
                sourceType = ImagePreviewViewModel.SourceType.LOCAL
            )
        }

        viewModel.setImageList(items, index)
    }

    private fun toggleControls() {
        val isVisible = binding.toolbar.visibility == View.VISIBLE
        binding.toolbar.visibility = if (isVisible) View.GONE else View.VISIBLE
        binding.bottomControls.visibility = if (isVisible) View.GONE else View.VISIBLE
    }

    private fun resetTransform() {
        currentScale = 1.0f
        translateX = 0f
        translateY = 0f
        binding.imageView.scaleX = currentScale
        binding.imageView.scaleY = currentScale
        binding.imageView.translationX = translateX
        binding.imageView.translationY = translateY
    }

    private fun updateIndexDisplay(index: Int) {
        val total = viewModel.getImageCount()
        binding.tvIndex.text = "${index + 1} / $total"
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

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopSlideshow()
    }
}
