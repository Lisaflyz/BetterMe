package com.betterme.app

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.betterme.app.databinding.ActivityDiaryBinding
import com.betterme.app.databinding.DialogDiaryUnsavedBinding
import com.betterme.app.diary.DiaryEntry
import com.betterme.app.diary.DiaryEntryKind
import com.betterme.app.diary.GrowthTopics
import com.betterme.app.diary.DiaryDraft
import com.betterme.app.diary.DiaryDraftStore
import com.betterme.app.diary.DiaryMediaState
import com.betterme.app.diary.DiaryStore
import com.betterme.app.diary.DiaryMediaSelection
import com.betterme.app.diary.DiaryMediaKind
import com.betterme.app.diary.DiaryMediaViewModel
import com.betterme.app.diary.MediaPhase
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.widget.FrameLayout
import android.view.Gravity
import org.json.JSONArray
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** “日常.jpg”对应的日记页。照片可选，文字可独立保存。 */
class DiaryActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ENTRY_ID = "diary_entry_id"
        const val EXTRA_ENTRY_KIND = "diary_entry_kind"
        const val EXTRA_SAVED_TOPIC = "saved_topic"
    }
    private val requestedKind: DiaryEntryKind by lazy {
        DiaryEntryKind.fromName(intent.getStringExtra(EXTRA_ENTRY_KIND))
    }
    private var topicLoading = false
    private val requestedEntryId: String? by lazy { intent.getStringExtra(EXTRA_ENTRY_ID) }
    private lateinit var drafts: DiaryDraftStore
    private var entryReady = false
    private var entryLoadJob: Job? = null
    private var pendingPickedMedia: List<String>? = null
    private lateinit var binding: ActivityDiaryBinding
    private lateinit var entry: DiaryEntry
    private lateinit var store: DiaryStore
    private var initialFingerprint = ""
    private var saving = false
    private lateinit var mediaModel: DiaryMediaViewModel
    private var renderedMedia: DiaryMediaSelection? = null
    private val thumbLoader = MediaThumbLoader()
    private data class MediaRemoveTarget(val button: View, val badge: View)
    private val mediaRemoveTargets = mutableListOf<MediaRemoveTarget>()
    private var removeTouchTarget: MediaRemoveTarget? = null
    private var photoPreviewOpen = false
    private var removeTouchX = 0f
    private var removeTouchY = 0f
    private var removeTouchCancelled = false

    private val photoPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getParcelableArrayListExtra<Uri>(AlbumActivity.EXTRA_RESULT)?.let { uris ->
                pendingPickedMedia = uris.map(Uri::toString)
                applyPendingSelection()
            }
        }
    }

    private val voiceInput = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let(::insertText)
        }
    }

    private val photoPreview = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        photoPreviewOpen = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = DiaryStore(this)
        mediaModel = ViewModelProvider(this)[DiaryMediaViewModel::class.java]

        drafts = DiaryDraftStore(this, requestedEntryId, requestedKind)
        pendingPickedMedia = savedInstanceState?.getStringArrayList("pending_picked_media")
        photoPreviewOpen = savedInstanceState?.getBoolean("photo_preview_open") ?: false

        binding.contentEditor.onContentChanged = ::updateWordCount
        binding.contentEditor.onLimitReached = { toast(R.string.diary_format_limit) }
        binding.etTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                binding.contentEditor.focusFirst()
                true
            } else false
        }
        binding.btnBack.setOnClickListener { requestExit() }
        binding.btnDate.setOnClickListener { pickDate() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnAddPhoto.setOnClickListener {
            if (!mediaModel.state.value.busy) {
                val current = mediaModel.state.value.active.media
                photoPicker.launch(Intent(this, AlbumActivity::class.java)
                    .putParcelableArrayListExtra(AlbumActivity.EXTRA_PRESELECT, ArrayList(current.map { Uri.parse(it.sourceUri) }))
                    .putExtra(AlbumActivity.EXTRA_LOCAL_MEDIA, JSONArray(current.map { it.toJson() }).toString()))
            }
        }
        binding.btnCancelMedia.setOnClickListener { lifecycleScope.launch { mediaModel.cancelPending() } }
        binding.btnRetryMedia.setOnClickListener { mediaModel.retry() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaModel.state.collect { state ->
                    if (entryReady) renderMediaState(state)
                }
            }
        }
        binding.btnTopic.setOnClickListener { chooseTopic() }
        binding.btnWeather.setOnClickListener {
            chooseTag(R.string.diary_weather, R.array.diary_weather_options, entry.weather) {
                entry = entry.copy(weather = it)
            }
        }
        binding.btnMood.setOnClickListener {
            chooseTag(R.string.diary_mood, R.array.diary_mood_options, entry.mood) {
                entry = entry.copy(mood = it)
            }
        }
        binding.btnActivity.setOnClickListener {
            chooseTag(R.string.diary_activity, R.array.diary_activity_options, entry.activity) {
                entry = entry.copy(activity = it)
            }
        }
        binding.btnTime.setOnClickListener {
            insertText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) + " ")
        }
        binding.btnPaper.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.diary_paper)
                .setSingleChoiceItems(R.array.diary_paper_options, entry.paper) { dialog, which ->
                    entry = entry.copy(paper = which)
                    renderFormatting()
                    dialog.dismiss()
                }.setNegativeButton(R.string.diary_cancel, null).show()
        }
        binding.btnFont.setOnClickListener {
            val sizes = listOf(14, 16, 18, 20)
            MaterialAlertDialogBuilder(this).setTitle(R.string.diary_font)
                .setSingleChoiceItems(R.array.diary_font_options, sizes.indexOf(entry.fontSize)) { dialog, which ->
                    entry = entry.copy(fontSize = sizes[which])
                    renderFormatting()
                    dialog.dismiss()
                }.setNegativeButton(R.string.diary_cancel, null).show()
        }
        binding.btnChecklist.setOnClickListener {
            binding.contentEditor.insertChecklist()
        }
        onBackPressedDispatcher.addCallback(this) { requestExit() }
        binding.btnRetryEntry.setOnClickListener { loadEntry(savedInstanceState) }
        loadEntry(savedInstanceState)
    }

    private fun loadEntry(savedState: Bundle?) {
        entryLoadJob?.cancel()
        showEntryLoadState(failed = false)
        entryLoadJob = lifecycleScope.launch {
            try {
                // Pass only the ID through Intent; read the full record off the main thread.
                val savedEntry = requestedEntryId?.let { store.get(it) }
                val restored = withContext(Dispatchers.IO) {
                    val candidate = savedState?.getString("entry")?.let {
                        DiaryDraft(DiaryEntry.fromJson(JSONObject(it)), savedState.getString("initialFingerprint"))
                    } ?: drafts.read()
                    require(candidate == null || requestedEntryId == null || candidate.entry.id == requestedEntryId)
                    require(candidate == null || candidate.entry.kind == (savedEntry?.kind ?: requestedKind))
                    // An older draft must not overwrite a newer saved version.
                    candidate?.takeIf { savedEntry == null || it.entry.updatedAt == savedEntry.updatedAt }
                }
                if (isFinishing) return@launch
                showEntry(restored?.entry ?: savedEntry ?: DiaryEntry(date = today(), kind = requestedKind))
                initialFingerprint = restored?.initialFingerprint
                    ?: if (restored != null && savedEntry != null) fingerprint(savedEntry) else fingerprint()
                entryReady = true
                binding.entryLoadState.isVisible = false
                binding.editorScroll.isVisible = true
                binding.editorToolbar.isVisible = true
                enableTree(binding.editorScroll, true)
                enableTree(binding.editorToolbar, true)
                binding.btnDate.isEnabled = true
                savedState?.let {
                    binding.contentEditor.restoreSelection(it)
                    binding.editorScroll.post { binding.editorScroll.scrollTo(0, it.getInt("editor_scroll_y")) }
                    binding.mediaScroll.post { binding.mediaScroll.scrollTo(it.getInt("media_scroll_x"), 0) }
                }
                renderMediaState(mediaModel.state.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isFinishing) showEntryLoadState(failed = true)
            }
        }
    }

    private fun showEntryLoadState(failed: Boolean) {
        entryReady = false
        binding.editorScroll.isVisible = false
        binding.editorToolbar.isVisible = false
        binding.btnDate.isEnabled = false
        binding.btnSave.isEnabled = false
        binding.entryLoadState.isVisible = true
        binding.entryLoading.isVisible = !failed
        binding.entryLoadMessage.setText(if (failed) R.string.diary_read_error else R.string.diary_loading_entry)
        binding.btnRetryEntry.isVisible = failed
    }

    private fun renderMediaState(state: DiaryMediaState) {
        if (renderedMedia != state.active) renderMedia(state.active)
        binding.btnSave.isEnabled = !saving && !state.busy
        binding.btnAddPhoto.isEnabled = !saving && !state.busy
        binding.btnAddPhoto.alpha = if (state.busy) .4f else 1f
        binding.mediaStatus.isVisible = state.busy || state.error != null
        binding.mediaProgress.isVisible = state.busy
        binding.mediaProgress.isIndeterminate = state.progress == null
        binding.mediaProgress.progress = state.progress ?: 0
        binding.mediaMessage.text = when (state.phase) {
            MediaPhase.RESTORING -> "正在恢复媒体…"
            MediaPhase.IMPORTING -> "正在导入素材 ${state.progress ?: 0}% · 可继续编辑文字"
            MediaPhase.COMPOSING -> "正在合成视频${state.progress?.let { " $it%" } ?: "…"} · 可继续编辑文字"
            else -> state.error.orEmpty()
        }
        binding.btnCancelMedia.isVisible = state.busy || state.error != null
        binding.btnRetryMedia.isVisible = state.canRetry && !state.busy
        applyPendingSelection()
    }

    private fun applyPendingSelection() {
        if (!entryReady || saving || mediaModel.state.value.busy) return
        val selected = pendingPickedMedia ?: return
        pendingPickedMedia = null
        mediaModel.select(selected)
    }

    override fun onStop() {
        if (!isFinishing && entryReady && !saving) {
            drafts.save(snapshot(), initialFingerprint)
        }
        super.onStop()
    }

    private fun clearDraft() = drafts.clear()

    override fun onSaveInstanceState(outState: Bundle) {
        // 限制正文 20000 字、照片 9 张；仅保存 URI，不把位图写入 Bundle。
        if (entryReady) {
            outState.putString("entry", snapshot().toJson().toString())
            outState.putString("initialFingerprint", initialFingerprint)
            outState.putInt("editor_scroll_y", binding.editorScroll.scrollY)
            outState.putInt("media_scroll_x", binding.mediaScroll.scrollX)
            binding.contentEditor.saveSelection(outState)
        }
        outState.putBoolean("photo_preview_open", photoPreviewOpen)
        pendingPickedMedia?.let { outState.putStringArrayList("pending_picked_media", ArrayList(it)) }
        super.onSaveInstanceState(outState)
    }

    private fun snapshot() = entry.copy(
        title = binding.etTitle.text.toString(),
        content = binding.contentEditor.getContent(),
        media = mediaModel.state.value.active.media,
        video = mediaModel.state.value.active.video
    )

    private fun fingerprint(value: DiaryEntry = snapshot()): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toJson().toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun showEntry(value: DiaryEntry) {
        entry = value
        binding.etTitle.setHint(
            when (value.kind) {
                DiaryEntryKind.DAILY -> R.string.diary_title_hint
                DiaryEntryKind.GROWTH -> R.string.growth_title_hint
                DiaryEntryKind.TODO -> R.string.add_todo
            }
        )
        binding.btnSave.contentDescription = getString(
            when (value.kind) {
                DiaryEntryKind.DAILY -> R.string.diary_save
                DiaryEntryKind.GROWTH -> R.string.growth_save
                DiaryEntryKind.TODO -> R.string.todo_save
            }
        )
        binding.etTitle.setText(value.title)
        binding.contentEditor.setHintsEnabled(value.kind != DiaryEntryKind.TODO)
        binding.contentEditor.setContent(value.content)
        renderDate()
        renderTags()
        renderFormatting()
        mediaModel.bind(value)
        renderMedia(mediaModel.state.value.active)
        updateWordCount()
        initialFingerprint = fingerprint()
    }

    private fun renderDate() {
        val (year, month, day) = entry.date.split('-').map(String::toInt)
        binding.tvDay.text = day.toString()
        binding.tvMonth.text = getString(R.string.diary_month, year, month)
        binding.btnDate.contentDescription = getString(R.string.diary_date) + "，" + entry.date
    }

    private fun pickDate() {
        val (year, month, day) = entry.date.split('-').map(String::toInt)
        DatePickerDialog(this, { _, y, m, d ->
            entry = entry.copy(date = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d))
            renderDate()
        }, year, month - 1, day).show()
    }

    private fun chooseTopic() {
        if (!entryReady || saving || topicLoading) return
        topicLoading = true
        binding.btnTopic.isEnabled = false
        lifecycleScope.launch {
            try {
                val choices = (GrowthTopics.presets + store.topics(entry.kind).map { it.label } + entry.topic)
                    .map(GrowthTopics::normalize)
                    .filter { it.isNotBlank() }
                    .distinctBy(GrowthTopics::key)
                if (isFinishing || saving) return@launch
                val selected = choices.indexOfFirst { GrowthTopics.key(it) == GrowthTopics.key(entry.topic) }
                val labels = (choices + getString(R.string.diary_custom_tag)).toTypedArray()
                MaterialAlertDialogBuilder(this@DiaryActivity)
                    .setTitle(R.string.growth_topic)
                    .setSingleChoiceItems(labels, selected) { dialog, which ->
                        dialog.dismiss()
                        if (which == choices.size) {
                            editCustomTag(R.string.growth_topic, "") { text ->
                                val normalized = GrowthTopics.normalize(text)
                                val existing = choices.firstOrNull { GrowthTopics.key(it) == GrowthTopics.key(normalized) }
                                entry = entry.copy(topic = existing ?: normalized)
                            }
                        } else {
                            entry = entry.copy(topic = choices[which])
                            renderTags()
                        }
                    }.setNegativeButton(R.string.diary_cancel, null).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(R.string.growth_topics_error)
            } finally {
                topicLoading = false
                binding.btnTopic.isEnabled = !saving
            }
        }
    }

    private fun chooseTag(title: Int, optionsId: Int, current: String, update: (String) -> Unit) {
        val options = resources.getStringArray(optionsId)
        val choices = options + getString(R.string.diary_custom_tag)
        val selected = options.indexOf(current).takeIf { it >= 0 }
            ?: if (current.isNotEmpty()) options.size else -1
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                dialog.dismiss()
                if (which == options.size) {
                    editCustomTag(title, current.takeUnless { it in options }.orEmpty(), update)
                } else {
                    update(options[which])
                    renderTags()
                }
            }.setNeutralButton(R.string.diary_clear) { _, _ ->
                update("")
                renderTags()
            }.setNegativeButton(R.string.diary_cancel, null).show()
    }

    private fun editCustomTag(title: Int, current: String, update: (String) -> Unit) {
        val builder = MaterialAlertDialogBuilder(this)
        val inputLayout = TextInputLayout(builder.context).apply {
            hint = getString(R.string.diary_custom_tag_hint, getString(title))
        }
        val input = TextInputEditText(inputLayout.context).apply {
            setSingleLine(true)
            if (title == R.string.growth_topic) {
                filters = arrayOf(android.text.InputFilter.LengthFilter(GrowthTopics.MAX_LENGTH))
            }
            setText(current)
            setSelection(text?.length ?: 0)
        }
        inputLayout.addView(input, LinearLayout.LayoutParams(-1, -2))
        val container = FrameLayout(builder.context).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(inputLayout, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = builder.setTitle(title).setView(container)
            .setPositiveButton(R.string.diary_confirm, null)
            .setNegativeButton(R.string.diary_cancel, null)
            .create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            confirm.isEnabled = !input.text.isNullOrBlank()
            input.doAfterTextChanged { confirm.isEnabled = !it.isNullOrBlank() }
            confirm.setOnClickListener {
                val value = input.text?.toString().orEmpty().trim()
                if (value.isNotEmpty()) {
                    update(value)
                    renderTags()
                    dialog.dismiss()
                }
            }
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun renderTags() {
        fun render(view: TextView, label: Int, value: String) {
            view.text = value.ifEmpty { getString(label) }
            view.isSelected = value.isNotEmpty()
            view.contentDescription = getString(label) + "，" + view.text
        }
        val requiresTopic = entry.kind.requiresTopic
        binding.btnWeather.isVisible = !requiresTopic
        binding.btnMood.isVisible = !requiresTopic
        binding.btnActivity.isVisible = !requiresTopic
        binding.btnTopic.isVisible = requiresTopic
        render(binding.btnTopic, R.string.growth_topic, entry.topic)
        render(binding.btnWeather, R.string.diary_weather, entry.weather)
        render(binding.btnMood, R.string.diary_mood, entry.mood)
        render(binding.btnActivity, R.string.diary_activity, entry.activity)
    }

    private fun renderFormatting() {
        val color = listOf(R.color.surface, R.color.accent_light, R.color.gold_light)[entry.paper]
        binding.editorScroll.setBackgroundColor(ContextCompat.getColor(this, color))
        binding.contentEditor.setEditorTextSize(entry.fontSize.toFloat())
    }

    private fun updateWordCount() {
        val text = binding.contentEditor.getContent().filterNot(Char::isWhitespace)
        val count = Character.codePointCount(text, 0, text.length)
        binding.tvWordCount.text = getString(R.string.diary_word_count, count)
    }

    private fun insertText(text: String) {
        binding.contentEditor.insertText(text)
    }

    private fun renderMedia(selection: DiaryMediaSelection) {
        renderedMedia = selection
        removeTouchTarget?.button?.isPressed = false
        mediaRemoveTargets.clear()
        thumbLoader.clearViews(binding.photoStrip)
        binding.photoStrip.removeAllViews()
        val playback = selection.playbackUri
        if (playback != null) {
            // Keep the wrapper the size of the video; only the inner tile clips corners.
            val wrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(168), dp(96)).apply { marginEnd = dp(18) }
                clipChildren = false
                clipToPadding = false
            }
            val tile = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.bg_image_placeholder)
                clipToOutline = true
            }
            wrapper.addView(tile, FrameLayout.LayoutParams(-1, -1))
            val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            tile.addView(cover, FrameLayout.LayoutParams(-1, -1))
            thumbLoader.load(MediaItem(Uri.parse(playback), 0, MediaItem.Kind.VIDEO, 0), cover)
            val play = TextView(this).apply {
                text = "▶  ${formatDuration(selection.durationMs)}"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0x55000000)
                contentDescription = "全屏播放视频"
                setOnClickListener { startActivity(Intent(this@DiaryActivity, VideoPlayerActivity::class.java).putExtra(VideoPlayerActivity.EXTRA_URI, playback)) }
            }
            tile.addView(play, FrameLayout.LayoutParams(-1, -1))
            val remove = FrameLayout(this).apply {
                isFocusable = true
                contentDescription = "移除视频及素材"
                setOnClickListener { removeMedia(emptyList()) }
            }
            val badge = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.bg_diary_video_remove)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            badge.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_album_remove)
                setColorFilter(android.graphics.Color.WHITE)
            }, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
            remove.addView(badge, FrameLayout.LayoutParams(-1, -1))
            // The lower-left quarter overlaps the rectangular card bounds.
            wrapper.addView(remove, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply {
                topMargin = -dp(10)
                marginEnd = -dp(10)
            })
            mediaRemoveTargets.add(MediaRemoveTarget(remove, badge))
            binding.photoStrip.addView(wrapper)
        } else selection.media.forEachIndexed { index, item ->
            val wrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(80), dp(80)).apply { marginEnd = dp(8) }
                clipChildren = false
                clipToPadding = false
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_image_placeholder)
                clipToOutline = true
                contentDescription = getString(R.string.diary_preview_photo, index + 1)
                setOnClickListener { previewPhoto(item.uri) }
            }
            wrapper.addView(image, FrameLayout.LayoutParams(-1, -1))
            val remove = FrameLayout(this).apply {
                isFocusable = true
                clipChildren = false
                clipToPadding = false
                contentDescription = getString(R.string.diary_remove_photo_at, index + 1)
                setOnClickListener {
                    val current = mediaModel.state.value.active.media
                    if (current.any { it.uri == item.uri }) {
                        removeMedia(current.filterNot { it.uri == item.uri }.map { it.sourceUri })
                    }
                }
            }
            // Match AlbumSelectedAdapter: 18dp round badge, 6dp glyph, overflowing by 6dp.
            val badge = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.bg_album_remove)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            badge.addView(ImageView(this).apply { setImageResource(R.drawable.ic_album_remove) },
                FrameLayout.LayoutParams(dp(6), dp(6), Gravity.CENTER))
            remove.addView(badge, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
                topMargin = -dp(6)
                marginEnd = -dp(6)
            })
            wrapper.addView(remove, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END))
            mediaRemoveTargets.add(MediaRemoveTarget(remove, badge))
            thumbLoader.load(MediaItem(Uri.parse(item.uri), 0, MediaItem.Kind.PHOTO, 0), image)
            binding.photoStrip.addView(wrapper)
        }
    }

    private fun previewPhoto(uri: String) {
        if (!entryReady || saving || photoPreviewOpen) return
        if (mediaModel.state.value.active.media.none { it.uri == uri && it.kind == DiaryMediaKind.IMAGE }) return
        photoPreviewOpen = true
        photoPreview.launch(Intent(this, PhotoPreviewActivity::class.java).putExtra(PhotoPreviewActivity.EXTRA_URI, uri))
    }

    /** Include the badge overflow, but exclude items scrolled outside the visible media strip. */
    private fun removeHitBounds(target: MediaRemoveTarget): Rect? {
        if (target !in mediaRemoveTargets || !target.button.isShown || !target.button.isEnabled) return null
        val location = IntArray(2)
        fun screenBounds(view: View): Rect {
            view.getLocationOnScreen(location)
            return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        val bounds = screenBounds(target.button)
        bounds.union(screenBounds(target.badge))
        val viewport = screenBounds(binding.editorScroll)
        val mediaViewport = screenBounds(binding.mediaScroll)
        // Keep the vertical overflow tappable; only the horizontal viewport clips scrolling items.
        viewport.left = maxOf(viewport.left, mediaViewport.left)
        viewport.right = minOf(viewport.right, mediaViewport.right)
        return bounds.takeIf { it.intersect(viewport) }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // clipChildren changes drawing, not touch dispatch. Route only gestures starting at a delete target.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            removeTouchTarget?.button?.isPressed = false
            removeTouchTarget = null
            val target = mediaRemoveTargets.asReversed().firstOrNull {
                removeHitBounds(it)?.contains(event.rawX.toInt(), event.rawY.toInt()) == true
            }
            if (target != null) {
                removeTouchTarget = target
                removeTouchX = event.rawX
                removeTouchY = event.rawY
                removeTouchCancelled = false
                target.button.isPressed = true
                return true
            }
        }
        val target = removeTouchTarget ?: return super.dispatchTouchEvent(event)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        if (kotlin.math.abs(event.rawX - removeTouchX) > slop ||
            kotlin.math.abs(event.rawY - removeTouchY) > slop ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
            removeHitBounds(target)?.contains(event.rawX.toInt(), event.rawY.toInt()) != true) {
            removeTouchCancelled = true
            target.button.isPressed = false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                target.button.isPressed = false
                removeTouchTarget = null
                if (!removeTouchCancelled) target.button.performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                target.button.isPressed = false
                removeTouchTarget = null
            }
        }
        return true
    }

    private fun removeMedia(remaining: List<String>) {
        if (!entryReady || saving || mediaModel.state.value.busy) return
        MaterialAlertDialogBuilder(this).setTitle("移除此媒体？")
            .setPositiveButton("移除") { _, _ -> mediaModel.select(remaining) }
            .setNegativeButton(R.string.diary_cancel, null).show()
    }

    private fun formatDuration(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60)

    private fun save() {
        if (!entryReady || saving || mediaModel.state.value.busy) return
        val value = snapshot().copy(
            updatedAt = System.currentTimeMillis(),
            topic = GrowthTopics.forSave(entry.topic, entry.kind)
        )
        if (value.title.isBlank() && value.content.isBlank() && value.media.isEmpty()) {
            toast(R.string.diary_empty)
            return
        }
        saving = true
        enableTree(binding.root, false)
        lifecycleScope.launch {
            try {
                store.save(value)
                clearDraft()
                mediaModel.closeSession(saved = true)
                toast(
                    when (value.kind) {
                        DiaryEntryKind.DAILY -> R.string.diary_saved
                        DiaryEntryKind.GROWTH -> R.string.growth_saved
                        DiaryEntryKind.TODO -> R.string.todo_saved
                    }
                )
                setResult(RESULT_OK, Intent().putExtra("saved_diary_id", value.id)
                    .putExtra(EXTRA_ENTRY_KIND, value.kind.name)
                    .putExtra(EXTRA_SAVED_TOPIC, value.topic))
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(R.string.diary_save_error)
            } finally {
                saving = false
                enableTree(binding.root, true)
            }
        }
    }

    private fun discardAndFinish() {
        saving = true
        enableTree(binding.root, false)
        lifecycleScope.launch {
            clearDraft()
            mediaModel.closeSession(saved = false)
            finish()
        }
    }

    private fun requestExit() {
        if (!entryReady) {
            entryLoadJob?.cancel()
            finish()
            return
        }
        if (saving) return
        if (mediaModel.state.value.busy) {
            MaterialAlertDialogBuilder(this).setTitle("停止媒体处理？")
                .setMessage("停止后保留文字和原媒体，可继续编辑或退出。")
                .setPositiveButton("停止处理") { _, _ ->
                    lifecycleScope.launch {
                        mediaModel.cancelPending()
                        requestExit()
                    }
                }
                .setNegativeButton("继续处理", null).show()
            return
        }
        if (fingerprint() == initialFingerprint) {
            discardAndFinish()
            return
        }
        val builder = MaterialAlertDialogBuilder(this)
        val content = DialogDiaryUnsavedBinding.inflate(android.view.LayoutInflater.from(builder.context))
        val dialog = builder.setView(content.root).create()
        content.btnExit.setOnClickListener {
            dialog.dismiss()
            discardAndFinish()
        }
        content.btnContinue.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun enableTree(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (i in 0 until view.childCount) enableTree(view.getChildAt(i), enabled)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun today(): String = Calendar.getInstance().run {
        String.format(Locale.ROOT, "%04d-%02d-%02d", get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH))
    }
}
