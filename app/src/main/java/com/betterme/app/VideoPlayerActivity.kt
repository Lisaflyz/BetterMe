package com.betterme.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.betterme.app.databinding.ActivityVideoPlayerBinding
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {
    companion object { const val EXTRA_URI = "video_uri" }
    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var position = 0L
    private var shouldPlay = true
    private var started = false
    private var seeking = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            if (!started) return
            updateProgress()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        position = savedInstanceState?.getLong("position") ?: 0L
        shouldPlay = savedInstanceState?.getBoolean("playing") ?: true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.safeControls.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnReturn.setOnClickListener { finish() }
        binding.videoTapArea.setOnClickListener { togglePlayback() }
        binding.btnPaused.setOnClickListener { togglePlayback() }
        binding.btnRetry.setOnClickListener {
            releasePlayer()
            shouldPlay = true
            preparePlayer()
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val target = duration() * progress / seekBar.max
                binding.currentTime.text = time(target)
                // Accessibility can change progress without a touch-drag sequence.
                if (!seeking) player?.seekTo(target)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                player?.seekTo(duration() * seekBar.progress / seekBar.max)
                seeking = false
                updateProgress()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        started = true
        preparePlayer()
        handler.post(progressTicker)
    }

    private fun preparePlayer() {
        if (player != null) return
        binding.errorPanel.isVisible = false
        val uri = intent.getStringExtra(EXTRA_URI)
        if (uri.isNullOrBlank()) {
            renderPlayback()
            binding.errorPanel.isVisible = true
            binding.videoControls.isVisible = false
            return
        }
        val engine = ExoPlayer.Builder(this).build()
        player = engine
        binding.playerView.player = engine
        engine.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        engine.setHandleAudioBecomingNoisy(true)
        engine.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (this@VideoPlayerActivity.player !== engine) return
                renderPlayback()
                updateProgress()
            }
        })
        engine.setMediaItem(MediaItem.fromUri(uri))
        engine.seekTo(position)
        engine.playWhenReady = shouldPlay
        engine.prepare()
        renderPlayback()
        updateProgress()
    }

    private fun togglePlayback() {
        val engine = player ?: return
        if (engine.playerError != null || binding.errorPanel.isVisible) return
        when {
            engine.playbackState == Player.STATE_ENDED -> {
                engine.seekTo(0)
                engine.play()
            }
            // Use playback intent so a tap can pause even while buffering.
            engine.playWhenReady -> engine.pause()
            else -> engine.play()
        }
        shouldPlay = engine.playWhenReady
        renderPlayback()
    }

    private fun renderPlayback() {
        val engine = player
        val failed = engine?.playerError != null
        val ended = engine?.playbackState == Player.STATE_ENDED
        val paused = engine != null && !engine.playWhenReady
        binding.errorPanel.isVisible = failed
        binding.videoControls.isVisible = engine != null && !failed
        binding.videoTapArea.isEnabled = engine != null && !failed
        binding.playerView.keepScreenOn = engine?.isPlaying == true
        binding.loading.isVisible = !failed && engine?.playbackState == Player.STATE_BUFFERING && !paused
        binding.btnPaused.isVisible = !failed && (paused || ended)
        binding.btnPaused.setImageResource(R.drawable.ic_player_play)
        val action = when {
            ended -> R.string.player_replay
            paused -> R.string.player_resume
            else -> R.string.player_pause
        }
        binding.videoTapArea.contentDescription = getString(action)
        binding.btnPaused.contentDescription = getString(action)
    }

    private fun duration(): Long = player?.duration?.takeIf { it > 0 } ?: 0L

    private fun updateProgress() {
        val total = duration()
        binding.totalTime.text = time(total)
        binding.seekBar.isEnabled = total > 0 && player?.isCurrentMediaItemSeekable == true && player?.playerError == null
        if (!seeking) {
            val now = player?.currentPosition ?: position
            binding.currentTime.text = time(now)
            binding.seekBar.progress = if (total > 0) (now * binding.seekBar.max / total).toInt().coerceIn(0, binding.seekBar.max) else 0
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: position)
        outState.putBoolean("playing", player?.let { it.playWhenReady && it.playbackState != Player.STATE_ENDED } ?: shouldPlay)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        started = false
        seeking = false
        handler.removeCallbacks(progressTicker)
        releasePlayer()
        super.onStop()
    }

    private fun releasePlayer() {
        val engine = player ?: return
        position = engine.currentPosition
        shouldPlay = engine.playWhenReady && engine.playbackState != Player.STATE_ENDED
        player = null
        binding.playerView.player = null
        binding.playerView.keepScreenOn = false
        engine.pause()
        engine.release()
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressTicker)
        super.onDestroy()
    }

    private fun time(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
