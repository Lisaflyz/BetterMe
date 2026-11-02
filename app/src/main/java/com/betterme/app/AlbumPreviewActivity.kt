package com.betterme.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.betterme.app.databinding.ActivityAlbumPreviewBinding
import org.json.JSONObject
import java.util.Locale

/** Previewing never selects an item. Explicit select/remove operations are returned to the album. */
@androidx.annotation.OptIn(UnstableApi::class)
class AlbumPreviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlbumPreviewBinding
    private lateinit var current: MediaItem
    private lateinit var selectedAdapter: AlbumSelectedAdapter
    private val selected = LinkedHashMap<Uri, MediaItem>()
    private val photoLoader = MediaThumbLoader()
    private var mediaGeneration = 0
    private var player: ExoPlayer? = null
    private var position = 0L
    private var shouldPlay = false
    private var started = false
    private var seeking = false
    private var lastLimitToastAt = -3_000L
    private var limitToast: Toast? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            updateProgress()
            if (started) handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        try {
            current = AlbumPreviewContract.decode(JSONObject(checkNotNull(
                savedInstanceState?.getString(AlbumPreviewContract.CURRENT) ?: intent.getStringExtra(AlbumPreviewContract.CURRENT))))
            AlbumPreviewContract.decodeList(savedInstanceState?.getString(AlbumPreviewContract.SELECTED)
                ?: intent.getStringExtra(AlbumPreviewContract.SELECTED) ?: "[]").forEach { selected[it.uri] = it }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.preview_error, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        lastLimitToastAt = savedInstanceState?.getLong(AlbumPreviewContract.LAST_LIMIT_TOAST)
            ?: intent.getLongExtra(AlbumPreviewContract.LAST_LIMIT_TOAST, -3_000L)
        position = savedInstanceState?.getLong("position") ?: 0L
        shouldPlay = savedInstanceState?.getBoolean("playing") ?: false
        selectedAdapter = AlbumSelectedAdapter(onRemove = ::removeItem, onPreview = ::showItem, showVideoDuration = true)
        binding.selectedStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.selectedStrip.adapter = selectedAdapter
        binding.btnBack.setOnClickListener { finishSelection(false) }
        onBackPressedDispatcher.addCallback(this) { finishSelection(false) }
        binding.btnSelect.setOnClickListener { toggleCurrent() }
        binding.btnNext.setOnClickListener { if (selected.isNotEmpty()) finishSelection(true) }
        binding.btnPlay.setOnClickListener { togglePlayback() }
        binding.videoTapArea.setOnClickListener { togglePlayback() }
        binding.btnRetry.setOnClickListener {
            releasePlayer()
            renderMedia()
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) { seeking = true }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.currentTime.text = time(duration() * progress / 1000)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val target = duration() * seekBar.progress / 1000
                player?.seekTo(target)
                position = target
                seeking = false
                updateProgress()
            }
        })
        renderSelection()
        renderMedia()
    }

    private fun showItem(item: MediaItem) {
        if (item.uri == current.uri && item.localUri == current.localUri) return
        releasePlayer()
        current = item
        position = 0L
        shouldPlay = false
        renderMedia()
        renderSelection()
    }

    private fun renderMedia() {
        val generation = ++mediaGeneration
        photoLoader.clear(binding.photoView)
        binding.errorPanel.isVisible = false
        val video = current.kind == MediaItem.Kind.VIDEO
        binding.photoView.isVisible = !video
        binding.playerView.isVisible = video
        binding.videoTapArea.isVisible = video
        binding.videoControls.isVisible = video
        binding.btnPlay.isVisible = video
        binding.currentTime.text = time(position)
        binding.totalTime.text = time(current.durationMs)
        binding.seekBar.progress = 0
        binding.loading.isVisible = true
        if (video) {
            if (started) preparePlayer()
        } else {
            val uri = current.localUri ?: current.uri
            photoLoader.loadPreview(uri, binding.photoView) { loaded ->
                if (generation == mediaGeneration) {
                    binding.errorPanel.isVisible = !loaded
                    binding.loading.isVisible = false
                }
            }
        }
    }

    private fun toggleCurrent() {
        if (selected.remove(current.uri) == null) {
            if (selected.size >= AlbumActivity.MAX_SELECT) {
                val now = SystemClock.elapsedRealtime()
                if (now >= lastLimitToastAt && now - lastLimitToastAt < 3_000L) return
                lastLimitToastAt = now
                limitToast?.cancel()
                limitToast = Toast.makeText(this, R.string.album_too_many, Toast.LENGTH_SHORT).also { it.show() }
                return
            }
            selected[current.uri] = current
        }
        renderSelection()
    }

    private fun removeItem(item: MediaItem) {
        if (selected.remove(item.uri) != null) renderSelection()
    }

    private fun renderSelection() {
        val isSelected = selected.containsKey(current.uri)
        binding.btnSelect.isSelected = isSelected
        binding.btnSelect.contentDescription = if (isSelected) "取消选择当前素材" else "选择当前素材"
        binding.selectLabel.setText(if (isSelected) R.string.preview_selected else R.string.preview_select)
        binding.selectIcon.setColorFilter(if (isSelected) ContextCompat.getColor(this, R.color.brand_gold) else Color.GRAY)
        binding.selectionCount.text = getString(R.string.album_order_tip, selected.size)
        binding.btnNext.text = getString(R.string.album_next_n, selected.size)
        binding.btnNext.isEnabled = selected.isNotEmpty()
        binding.btnNext.alpha = if (selected.isEmpty()) .4f else 1f
        binding.selectedStrip.isVisible = selected.isNotEmpty()
        selectedAdapter.submitList(selected.values.mapIndexed { index, item -> AlbumCell(item, index) })
    }

    private fun finishSelection(confirm: Boolean) {
        setResult(RESULT_OK, Intent()
            .putExtra(AlbumPreviewContract.SELECTED, AlbumPreviewContract.encodeList(selected.values))
            .putExtra(AlbumPreviewContract.CONFIRM, confirm)
            .putExtra(AlbumPreviewContract.LAST_LIMIT_TOAST, lastLimitToastAt))
        finish()
    }

    private fun preparePlayer() {
        if (player != null || current.kind != MediaItem.Kind.VIDEO) return
        binding.errorPanel.isVisible = false
        binding.loading.isVisible = true
        val engine = ExoPlayer.Builder(this).build()
        player = engine
        binding.playerView.player = engine
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        engine.setHandleAudioBecomingNoisy(true)
        engine.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== engine) return
                binding.loading.isVisible = playbackState == Player.STATE_BUFFERING
                binding.btnPlay.isVisible = !engine.isPlaying && playbackState != Player.STATE_BUFFERING
                updateProgress()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== engine) return
                binding.btnPlay.isVisible = !isPlaying && engine.playbackState != Player.STATE_BUFFERING
                binding.playerView.keepScreenOn = isPlaying
            }
            override fun onPlayerError(error: PlaybackException) {
                if (player !== engine) return
                binding.errorPanel.isVisible = true
                binding.loading.isVisible = false
                binding.btnPlay.isVisible = false
                binding.playerView.keepScreenOn = false
            }
        })
        engine.setMediaItem(androidx.media3.common.MediaItem.fromUri(current.localUri ?: current.uri))
        engine.seekTo(position)
        engine.playWhenReady = shouldPlay
        engine.prepare()
    }

    private fun togglePlayback() {
        val engine = player ?: return
        if (engine.isPlaying) engine.pause() else {
            if (engine.playbackState == Player.STATE_ENDED) engine.seekTo(0)
            engine.play()
        }
    }

    private fun duration() = player?.duration?.takeIf { it > 0 } ?: current.durationMs.coerceAtLeast(0)
    private fun updateProgress() {
        if (!::current.isInitialized || current.kind != MediaItem.Kind.VIDEO) return
        val total = duration()
        binding.totalTime.text = time(total)
        if (!seeking) {
            val now = player?.currentPosition ?: position
            binding.currentTime.text = time(now)
            binding.seekBar.progress = if (total > 0) (now * 1000 / total).toInt().coerceIn(0, 1000) else 0
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (::current.isInitialized) preparePlayer()
        handler.post(progressTicker)
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(progressTicker)
        releasePlayer()
        super.onStop()
    }

    private fun releasePlayer() {
        val engine = player ?: return
        position = engine.currentPosition
        shouldPlay = engine.playWhenReady
        player = null
        binding.playerView.player = null
        binding.playerView.keepScreenOn = false
        engine.pause()
        engine.release()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::current.isInitialized) {
            outState.putString(AlbumPreviewContract.CURRENT, AlbumPreviewContract.encode(current).toString())
            outState.putString(AlbumPreviewContract.SELECTED, AlbumPreviewContract.encodeList(selected.values))
            outState.putLong(AlbumPreviewContract.LAST_LIMIT_TOAST, lastLimitToastAt)
            outState.putLong("position", player?.currentPosition ?: position)
            outState.putBoolean("playing", player?.playWhenReady ?: shouldPlay)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTicker)
        limitToast?.cancel()
        photoLoader.clear(binding.photoView)
        super.onDestroy()
    }

    private fun time(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
