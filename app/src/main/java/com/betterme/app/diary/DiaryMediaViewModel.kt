package com.betterme.app.diary

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class MediaPhase { READY, RESTORING, IMPORTING, COMPOSING, ERROR }
data class DiaryMediaState(
    val active: DiaryMediaSelection = DiaryMediaSelection(),
    val phase: MediaPhase = MediaPhase.READY,
    val progress: Int? = null,
    val error: String? = null,
    val canRetry: Boolean = false
) {
    val busy get() = phase == MediaPhase.RESTORING || phase == MediaPhase.IMPORTING || phase == MediaPhase.COMPOSING
}

/** Only application context is retained. A rotation does not cancel the media operation. */
class DiaryMediaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DiaryMediaRepository(application)
    private val composer = DiaryVideoComposer(application)
    private val mutableState = MutableStateFlow(DiaryMediaState())
    val state = mutableState.asStateFlow()
    private var recordId: String? = null
    private var operation: Job? = null

    fun bind(entry: DiaryEntry) {
        if (recordId == entry.id) return
        check(operation?.isActive != true)
        recordId = entry.id
        mutableState.value = DiaryMediaState(entry.selection, MediaPhase.RESTORING)
        operation = viewModelScope.launch {
            try {
                val pending = repository.pending(entry.id)
                mutableState.value = if (pending?.result != null) DiaryMediaState(pending.result)
                else if (pending != null) DiaryMediaState(entry.selection, MediaPhase.ERROR,
                    error = "上次媒体处理已中断，可重试或重新选择", canRetry = true)
                else DiaryMediaState(entry.selection)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = DiaryMediaState(entry.selection, MediaPhase.ERROR,
                    error = "无法恢复媒体任务，请重新选择素材")
            }
        }
    }

    fun select(sources: List<String>) {
        if (state.value.busy) return
        val id = recordId ?: return
        val previous = state.value.active
        mutableState.value = state.value.copy(phase = MediaPhase.IMPORTING, progress = 0, error = null, canRetry = false)
        operation = viewModelScope.launch {
            run(id, previous) { repository.begin(id, sources, previous) }
        }
    }

    fun retry() {
        if (state.value.busy || !state.value.canRetry) return
        val id = recordId ?: return
        val previous = state.value.active
        mutableState.value = state.value.copy(phase = MediaPhase.IMPORTING, progress = 0, error = null)
        operation = viewModelScope.launch {
            run(id, previous) { checkNotNull(repository.pending(id)) }
        }
    }

    private suspend fun run(id: String, previous: DiaryMediaSelection, begin: suspend () -> PendingMedia) {
        try {
            var pending = repository.import(id, begin()) { percent ->
                mutableState.value = state.value.copy(progress = percent)
            }
            val video = if (MediaPlan.requiresComposition(pending.imported)) {
                mutableState.value = state.value.copy(phase = MediaPhase.COMPOSING, progress = null)
                val output = repository.allocate("mp4")
                pending = pending.copy(output = Uri.fromFile(output).toString(), owned = pending.owned + Uri.fromFile(output).toString())
                repository.checkpoint(id, pending)
                composer.export(pending.imported, output) { percent -> mutableState.value = state.value.copy(progress = percent) }
                check(output.length() > 0) { "合成未生成有效的视频" }
                Uri.fromFile(output).toString()
            } else null
            val result = DiaryMediaSelection(pending.imported, video)
            repository.checkpoint(id, pending.copy(result = result))
            mutableState.value = DiaryMediaState(result)
        } catch (cancelled: CancellationException) {
            // Leave the journal for a process restart; explicit cancel/exit cleans it below.
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w("DiaryMedia", "Media processing failed", error)
            mutableState.value = DiaryMediaState(previous, MediaPhase.ERROR,
                error = "处理失败，请检查可用空间、媒体格式或相册权限后重试", canRetry = true)
        }
    }

    suspend fun cancelPending() {
        operation?.cancelAndJoin()
        val id = recordId ?: return
        val previous = state.value.active
        withContext(NonCancellable) {
            try { repository.discard(id, previous.files()) }
            catch (error: Exception) { android.util.Log.w("DiaryMedia", "Cleanup deferred", error) }
        }
        mutableState.value = DiaryMediaState(previous)
    }

    /** Called after saving or discarding a diary, never merely because it goes into the background. */
    suspend fun closeSession(saved: Boolean) {
        cancelPending()
        if (!saved) withContext(NonCancellable) {
            try { repository.prune(state.value.active.files(), emptySet()) }
            catch (error: Exception) { android.util.Log.w("DiaryMedia", "Cleanup deferred", error) }
        }
        recordId = null
    }

    override fun onCleared() {
        // viewModelScope cancellation reaches Transformer.cancel. Keep the checkpoint recoverable.
        super.onCleared()
    }
}
