package com.betterme.app.diary

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class PendingMedia(
    val sources: List<String>,
    val previous: DiaryMediaSelection,
    val imported: List<DiaryMedia> = emptyList(),
    val owned: List<String> = emptyList(),
    val output: String? = null,
    val result: DiaryMediaSelection? = null
) {
    fun files() = previous.files() + imported.map { it.uri } + owned + listOfNotNull(output) + result?.files().orEmpty()
    fun toJson() = JSONObject().apply {
        put("sources", JSONArray(sources))
        put("previous", previous.toJson())
        put("imported", JSONArray(imported.map { it.toJson() }))
        put("owned", JSONArray(owned))
        put("output", output ?: JSONObject.NULL)
        put("result", result?.toJson() ?: JSONObject.NULL)
    }
    companion object {
        fun fromJson(json: JSONObject) = PendingMedia(
            sources = json.getJSONArray("sources").let { a -> (0 until a.length()).map(a::getString) },
            previous = DiaryMediaSelection.fromJson(json.getJSONObject("previous")),
            imported = DiaryMedia.fromArray(json.optJSONArray("imported")),
            owned = json.optJSONArray("owned")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
            output = json.optString("output").takeUnless { it.isBlank() || it == "null" },
            result = json.optJSONObject("result")?.let(DiaryMediaSelection::fromJson)
        )
    }
}

/** Checkpoints survive process death. All originals remain read-only. */
class DiaryMediaRepository(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "diary_media")
    private val pendingDir = File(app.filesDir, "diary_pending")
    private val store = DiaryStore(app)

    private fun journal(id: String): AtomicFile {
        require(UUID.fromString(id).toString() == id)
        check(pendingDir.isDirectory || pendingDir.mkdirs())
        return AtomicFile(File(pendingDir, "$id.json"))
    }

    suspend fun pending(id: String): PendingMedia? = withContext(Dispatchers.IO) {
        val file = journal(id)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null
        else PendingMedia.fromJson(JSONObject(file.readFully().toString(Charsets.UTF_8)))
    }

    suspend fun checkpoint(id: String, pending: PendingMedia) = withContext(Dispatchers.IO) {
        val file = journal(id)
        val stream = file.startWrite()
        try {
            stream.write(pending.toJson().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    suspend fun begin(id: String, sources: List<String>, previous: DiaryMediaSelection): PendingMedia {
        require(sources.size <= MediaPlan.MAX_ITEMS)
        discard(id, previous.files())
        return PendingMedia(sources.distinct(), previous).also { checkpoint(id, it) }
    }

    suspend fun allocate(extension: String): File = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "${UUID.randomUUID()}.$extension")
    }

    fun isLocal(uri: String): Boolean {
        val parsed = Uri.parse(uri)
        return parsed.scheme == "file" && parsed.path?.let {
            File(it).canonicalPath.startsWith(directory.canonicalPath + File.separator)
        } == true
    }

    suspend fun import(
        id: String, request: PendingMedia, progress: (Int) -> Unit
    ): PendingMedia {
        var pending = request
        // Fully imported items are reused after cancellation of an export or process recreation.
        for (index in pending.imported.size until pending.sources.size) {
            currentCoroutineContext().ensureActive()
            val source = pending.sources[index]
            val reusable = pending.previous.media.find {
                (it.sourceUri == source || it.uri == source) && isLocal(it.uri) && File(Uri.parse(it.uri).path!!).isFile
            }
            val media = if (reusable != null) reusable else {
                val uri = Uri.parse(source)
                val mime = withContext(Dispatchers.IO) { app.contentResolver.getType(uri) }
                    ?: throw IOException("无法识别所选文件，请重新选择")
                val kind = when {
                    mime.startsWith("image/") -> DiaryMediaKind.IMAGE
                    mime.startsWith("video/") -> DiaryMediaKind.VIDEO
                    else -> throw IOException("只能选择图片或视频")
                }
                val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ?.takeIf { it.matches(Regex("[a-zA-Z0-9]+")) } ?: if (kind == DiaryMediaKind.VIDEO) "mp4" else "img"
                val destination = allocate(extension)
                val partial = File(destination.path + ".part")
                pending = pending.copy(owned = pending.owned + listOf(destination, partial).map { Uri.fromFile(it).toString() })
                checkpoint(id, pending)
                withContext(Dispatchers.IO) {
                    try {
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        } ?: throw IOException("无法读取素材，请检查相册权限")
                        check(partial.length() > 0 && partial.renameTo(destination)) { "导入失败，请检查存储空间" }
                        describe(destination, source, kind, mime)
                    } finally { partial.delete() }
                }
            }
            pending = pending.copy(imported = pending.imported + media)
            checkpoint(id, pending)
            progress((index + 1) * 100 / pending.sources.size.coerceAtLeast(1))
        }
        return pending
    }

    private fun describe(file: File, source: String, kind: DiaryMediaKind, mime: String): DiaryMedia {
        if (kind == DiaryMediaKind.IMAGE) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, options)
            check(options.outWidth > 0 && options.outHeight > 0) { "无法解码这张图片" }
            return DiaryMedia(Uri.fromFile(file).toString(), kind, source, mime, width = options.outWidth, height = options.outHeight)
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            fun value(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            var width = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var height = value(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (value(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0) {
                val old = width
                width = height
                height = old
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            check(width > 0 && height > 0 && duration > 0) { "视频已损坏或格式不受支持" }
            return DiaryMedia(Uri.fromFile(file).toString(), kind, source, mime, duration, width, height)
        } finally { retriever.release() }
    }

    suspend fun discard(id: String, keep: Set<String> = emptySet()) {
        val old = pending(id)
        withContext(Dispatchers.IO) { journal(id).delete() }
        if (old != null) store.deleteUnreferenced(old.files(), keep)
    }

    suspend fun prune(candidates: Collection<String>, keep: Set<String>) = store.deleteUnreferenced(candidates, keep)
}
