package com.betterme.app.diary

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(UnstableApi::class)
class DiaryVideoComposer(context: Context) {
    private val app = context.applicationContext

    suspend fun export(items: List<DiaryMedia>, output: File, progress: (Int?) -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            require(MediaPlan.requiresComposition(items))
            val firstVideo = items.first { it.kind == DiaryMediaKind.VIDEO }
            val (width, height) = MediaPlan.outputSize(firstVideo.width, firstVideo.height)
            val edited = items.map { item ->
                val effects = listOf(
                    Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT),
                    FrameDropEffect.createDefaultFrameDropEffect(30f)
                )
                EditedMediaItem.Builder(MediaItem.Builder().setUri(item.uri).setMimeType(item.mimeType).build())
                    .setEffects(Effects(emptyList(), effects)).apply {
                        if (item.kind == DiaryMediaKind.IMAGE) {
                            setDurationUs(MediaPlan.IMAGE_DURATION_MS * 1_000)
                            setFrameRate(30)
                        }
                    }.build()
            }
            val composition = Composition.Builder(EditedMediaItemSequence(edited))
                // A photo at timestamp zero must not suppress audio from later clips.
                .experimentalSetForceAudioTrack(true)
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            val handler = Handler(Looper.getMainLooper())
            var transformer: Transformer? = null
            var ticker: Runnable? = null
            var succeeded = false
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val completed = AtomicBoolean(false)
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (completed.compareAndSet(false, true)) {
                                succeeded = true
                                continuation.resume(Unit)
                            }
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (completed.compareAndSet(false, true)) continuation.resumeWithException(exportException)
                        }
                    }
                    val engine = Transformer.Builder(app)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(DefaultEncoderFactory.Builder(app).setEnableFallback(false).build())
                        .addListener(listener).build()
                    transformer = engine
                    continuation.invokeOnCancellation {
                        completed.set(true)
                        if (Looper.myLooper() == Looper.getMainLooper()) engine.cancel()
                        else handler.post { engine.cancel() }
                    }
                    ticker = object : Runnable {
                        override fun run() {
                            if (completed.get()) return
                            val holder = ProgressHolder()
                            progress(if (engine.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                            handler.postDelayed(this, 300)
                        }
                    }
                    try {
                        engine.start(composition, output.absolutePath)
                        handler.post(ticker!!)
                    } catch (error: Exception) {
                        if (completed.compareAndSet(false, true)) continuation.resumeWithException(error)
                    }
                }
            } finally {
                ticker?.let(handler::removeCallbacks)
                transformer?.cancel()
                if (!succeeded) output.delete()
            }
        }
}
