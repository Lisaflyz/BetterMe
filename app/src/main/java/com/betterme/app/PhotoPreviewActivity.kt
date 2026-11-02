package com.betterme.app

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.betterme.app.databinding.ActivityPhotoPreviewBinding

/** Read-only preview of the editor's imported image; only a URI is passed through the Intent. */
class PhotoPreviewActivity : AppCompatActivity() {
    companion object { const val EXTRA_URI = "photo_uri" }
    private lateinit var binding: ActivityPhotoPreviewBinding
    private val loader = MediaThumbLoader()

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
        binding.btnRetry.setOnClickListener { loadPhoto() }
        loadPhoto()
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

    private fun loadPhoto() {
        binding.loading.isVisible = true
        binding.errorPanel.isVisible = false
        val uri = intent.getStringExtra(EXTRA_URI)?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        loader.loadPreview(uri, binding.photoView) { loaded ->
            binding.loading.isVisible = false
            binding.errorPanel.isVisible = !loaded
        }
    }

    override fun onDestroy() {
        loader.clear(binding.photoView)
        super.onDestroy()
    }
}
