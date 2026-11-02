package com.betterme.app

import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.TtsSpan
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.betterme.app.databinding.ActivityDiaryDetailBinding
import com.betterme.app.diary.DiaryEntry
import com.betterme.app.diary.DiaryEntryKind
import com.betterme.app.diary.DiaryMedia
import com.betterme.app.diary.DiaryMediaKind
import com.betterme.app.diary.DiaryStore
import com.betterme.app.ui.DiaryDetailMediaAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Displays saved content only. Drafts and media mutations remain in DiaryActivity. */
class DiaryDetailActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ENTRY_ID = "detail_entry_id"
        const val EXTRA_INITIAL_PAGE = "detail_initial_page"
    }

    private lateinit var binding: ActivityDiaryDetailBinding
    private lateinit var store: DiaryStore
    private var entry: DiaryEntry? = null
    private var pages: List<DiaryMedia> = emptyList()
    private var pageIndex = 0
    private var retainedScrollY = 0
    private var childOpen = false
    private var loadJob: Job? = null
    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        childOpen = false
        if (result.resultCode == RESULT_OK) setResult(RESULT_OK, result.data)
        // onResume reloads the saved record after either saving or discarding the edit.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiaryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = DiaryStore(this)
        pageIndex = savedInstanceState?.getInt("page_index") ?: intent.getIntExtra(EXTRA_INITIAL_PAGE, 0)
        retainedScrollY = savedInstanceState?.getInt("scroll_y") ?: 0
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetry.setOnClickListener { loadEntry() }
        binding.btnEdit.setOnClickListener {
            val current = entry ?: return@setOnClickListener
            if (childOpen) return@setOnClickListener
            childOpen = true
            binding.btnEdit.isEnabled = false
            editor.launch(Intent(this, DiaryActivity::class.java).putExtra(DiaryActivity.EXTRA_ENTRY_ID, current.id))
        }
        // Use the explicit page index instead of restoring a RecyclerView's internal state.
        binding.mediaPager.getChildAt(0).isSaveEnabled = false
        binding.mediaPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                pageIndex = position
                updateDots()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        childOpen = false
        loadEntry()
    }

    private fun loadEntry() {
        loadJob?.cancel()
        if (binding.contentScroll.isVisible) retainedScrollY = binding.contentScroll.scrollY
        binding.btnEdit.isEnabled = false
        binding.loading.isVisible = true
        binding.btnRetry.isVisible = false
        binding.loadMessage.setText(R.string.detail_loading)
        binding.loadState.isVisible = !binding.contentScroll.isVisible
        loadJob = lifecycleScope.launch {
            try {
                val id = requireNotNull(intent.getStringExtra(EXTRA_ENTRY_ID))
                val value = store.get(id)
                if (isFinishing) return@launch
                binding.loadState.isVisible = false
                binding.contentScroll.isVisible = true
                binding.btnEdit.isEnabled = !childOpen
                if (value != entry) render(value)
                binding.contentScroll.doOnLayout {
                    if (!isFinishing) binding.contentScroll.scrollTo(0, retainedScrollY)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                binding.btnEdit.isEnabled = false
                binding.contentScroll.isVisible = false
                binding.loadState.isVisible = true
                binding.loading.isVisible = false
                binding.btnRetry.isVisible = true
                binding.loadMessage.setText(R.string.detail_load_error)
            }
        }
    }

    private fun render(value: DiaryEntry) {
        val growth = value.kind == DiaryEntryKind.GROWTH
        binding.heading.text = getString(if (growth) R.string.growth_heading_date else R.string.detail_heading_date, value.date)
        binding.title.text = value.title
        binding.title.isVisible = value.title.isNotBlank()
        binding.body.text = readOnlyBody(value.content)
        binding.body.isVisible = value.content.isNotBlank()
        binding.body.textSize = value.fontSize.toFloat()
        binding.tags.text = if (growth) getString(R.string.detail_tag, getString(R.string.growth_topic), value.topic)
        else listOf(
            R.string.diary_weather to value.weather,
            R.string.diary_mood to value.mood,
            R.string.diary_activity to value.activity
        ).filter { it.second.isNotBlank() }.joinToString("  ") {
            getString(R.string.detail_tag, getString(it.first), it.second)
        }
        binding.tags.isVisible = binding.tags.text.isNotEmpty()

        val video = value.selection.playbackUri
        val nextPages = if (video != null) {
            val source = value.media.firstOrNull { it.kind == DiaryMediaKind.VIDEO }
            listOf(DiaryMedia(video, DiaryMediaKind.VIDEO, durationMs = value.selection.durationMs,
                width = source?.width ?: 0, height = source?.height ?: 0))
        } else value.media.filter { it.kind == DiaryMediaKind.IMAGE }
        binding.mediaSection.isVisible = nextPages.isNotEmpty()
        binding.pageDots.isVisible = nextPages.size > 1
        binding.mediaPager.isUserInputEnabled = nextPages.size > 1
        if (nextPages != pages) {
            val oldUri = pages.getOrNull(pageIndex)?.uri
            val newIndex = nextPages.indexOfFirst { it.uri == oldUri }.takeIf { it >= 0 } ?: pageIndex
            pages = nextPages
            binding.pageDots.removeAllViews()
            pages.forEachIndexed { index, _ ->
                binding.pageDots.addView(View(this).apply {
                    setBackgroundResource(R.drawable.bg_detail_dot)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                    marginStart = if (index == 0) 0 else dp(6)
                })
            }
            binding.mediaPager.adapter = if (pages.isEmpty()) null else {
                val firstUri = pages.first().uri
                DiaryDetailMediaAdapter(pages, ::playVideo, ::previewPhoto) { width, height ->
                    if (pages.firstOrNull()?.uri == firstUri) resizeMedia(width, height)
                }
            }
            pageIndex = newIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            binding.mediaPager.setCurrentItem(pageIndex, false)
            pages.firstOrNull()?.let { resizeMedia(it.width, it.height) }
        }
        updateDots()
        entry = value
    }

    private fun resizeMedia(width: Int, height: Int) {
        binding.root.doOnLayout {
            if (isDestroyed || pages.isEmpty()) return@doOnLayout
            val availableWidth = (binding.root.width - dp(64)).coerceAtLeast(dp(120))
            val aspect = if (width > 0 && height > 0) height.toFloat() / width else 4f / 3f
            val maxHeight = (binding.root.height * .68f).toInt().coerceAtLeast(dp(140))
            val targetHeight = (availableWidth * aspect).toInt().coerceIn(dp(140), maxHeight)
            if (binding.mediaPager.layoutParams.height != targetHeight) {
                binding.mediaPager.updateLayoutParams { this.height = targetHeight }
            }
        }
    }

    private fun updateDots() {
        for (index in 0 until binding.pageDots.childCount) {
            binding.pageDots.getChildAt(index).isSelected = index == pageIndex
        }
        binding.pageDots.contentDescription = if (pages.isEmpty()) null
            else getString(R.string.detail_page, pageIndex + 1, pages.size)
    }

    private fun previewPhoto(uri: String) {
        if (childOpen || isFinishing) return
        if (pages.none { it.kind == DiaryMediaKind.IMAGE && it.uri == uri }) return
        childOpen = true
        pageIndex = binding.mediaPager.currentItem
        retainedScrollY = binding.contentScroll.scrollY
        startActivity(Intent(this, PhotoPreviewActivity::class.java).putExtra(PhotoPreviewActivity.EXTRA_URI, uri))
    }

    private fun playVideo(uri: String) {
        if (childOpen) return
        childOpen = true
        startActivity(Intent(this, VideoPlayerActivity::class.java).putExtra(VideoPlayerActivity.EXTRA_URI, uri))
    }

    private fun readOnlyBody(content: String): CharSequence {
        val result = SpannableString(content)
        var start = 0
        content.split('\n').forEach { line ->
            if (line.startsWith("☐ ") || line.startsWith("☑ ")) {
                val checked = line.startsWith("☑ ")
                val drawable = ContextCompat.getDrawable(this, if (checked) R.drawable.ic_diary_checklist_checked
                    else R.drawable.bg_diary_checklist_unchecked)?.mutate()
                drawable?.let {
                    it.setBounds(0, 0, dp(20), dp(20))
                    result.setSpan(ImageSpan(it, ImageSpan.ALIGN_BASELINE), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                result.setSpan(TtsSpan.TextBuilder(getString(if (checked) R.string.detail_checked else R.string.detail_unchecked)).build(),
                    start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(LeadingMarginSpan.Standard(0, dp(26)), start, start + line.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start += line.length + 1
        }
        return result
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page_index", pageIndex)
        outState.putInt("scroll_y", if (binding.contentScroll.isVisible) binding.contentScroll.scrollY else retainedScrollY)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        loadJob?.cancel()
        binding.mediaPager.adapter = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
