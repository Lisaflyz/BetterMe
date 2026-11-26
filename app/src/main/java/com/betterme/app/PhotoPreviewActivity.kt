package com.betterme.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.betterme.app.databinding.ActivityPhotoPreviewBinding
import com.betterme.app.ui.PhotoPreviewAdapter

/** Read-only photo paging with URI-only Intent arguments; single-image callers remain supported. */
class PhotoPreviewActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URI = "photo_uri"
        const val EXTRA_URIS = "photo_uris"
        const val EXTRA_INITIAL_INDEX = "photo_initial_index"
        private const val STATE_PAGE = "photo_page"
    }

    private lateinit var binding: ActivityPhotoPreviewBinding
    private lateinit var photoAdapter: PhotoPreviewAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPhotoPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.safeControls.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        val photos = intent.getStringArrayListExtra(EXTRA_URIS)?.takeIf { it.isNotEmpty() }
            ?: listOf(intent.getStringExtra(EXTRA_URI).orEmpty())
        photoAdapter = PhotoPreviewAdapter(photos)
        binding.photoPager.adapter = photoAdapter
        binding.photoPager.isUserInputEnabled = photos.size > 1
        val initialIndex = savedInstanceState?.getInt(STATE_PAGE)
            ?: intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)
        binding.photoPager.setCurrentItem(initialIndex.coerceIn(0, photos.lastIndex), false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, binding.photoPager.currentItem)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        photoAdapter.clear()
        binding.photoPager.adapter = null
        super.onDestroy()
    }
}
