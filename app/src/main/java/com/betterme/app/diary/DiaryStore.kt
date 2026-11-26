package com.betterme.app.diary

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class DiaryEntryKind {
    DAILY,
    GROWTH,
    TODO;

    val requiresTopic: Boolean get() = this == GROWTH || this == TODO

    companion object {
        fun fromName(name: String?): DiaryEntryKind = values().firstOrNull { it.name == name } ?: DAILY
    }
}

data class DiaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val date: String,
    val title: String = "",
    val content: String = "",
    val weather: String = "",
    val mood: String = "",
    val activity: String = "",
    val media: List<DiaryMedia> = emptyList(),
    val video: String? = null,
    val fontSize: Int = 16,
    val paper: Int = 0,
    val updatedAt: Long = 0L,
    val kind: DiaryEntryKind = DiaryEntryKind.DAILY,
    val topic: String = ""
) {
    val photos: List<String> get() = media.filter { it.kind == DiaryMediaKind.IMAGE }.map { it.uri }
    val selection get() = DiaryMediaSelection(media, video)
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("date", date)
        put("title", title)
        put("content", content)
        put("weather", weather)
        put("mood", mood)
        put("activity", activity)
        put("version", 3)
        put("kind", kind.name)
        put("topic", topic)
        put("media", JSONArray(media.map { it.toJson() }))
        put("video", video ?: JSONObject.NULL)
        put("fontSize", fontSize)
        put("paper", paper)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = DiaryEntry(
            id = json.getString("id"),
            date = json.getString("date"),
            title = json.optString("title"),
            content = json.optString("content"),
            weather = json.optString("weather"),
            mood = json.optString("mood"),
            activity = json.optString("activity"),
            media = if (json.has("media")) DiaryMedia.fromArray(json.optJSONArray("media"))
            else json.optJSONArray("photos")?.let { array ->
                (0 until array.length()).map { DiaryMedia(array.getString(it), DiaryMediaKind.IMAGE) }
            }.orEmpty().take(9),
            video = json.optString("video").takeUnless { it.isBlank() || it == "null" },
            fontSize = json.optInt("fontSize", 16).coerceIn(14, 20),
            paper = json.optInt("paper", 0).coerceIn(0, 2),
            updatedAt = json.optLong("updatedAt"),
            kind = DiaryEntryKind.fromName(json.optString("kind")),
            topic = GrowthTopics.normalize(json.optString("topic"))
        )
    }
}

/** Saved entries live in Room; media and drafts retain their existing storage. */
class DiaryStore(context: Context) {
    private val app = context.applicationContext
    private val filesDir = app.filesDir
    private val records = DiaryIndexStore(app)

    companion object {
        const val PAGE_SIZE = 10
        private val lock = Mutex()
    }

    suspend fun save(entry: DiaryEntry) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(UUID.fromString(entry.id).toString() == entry.id)
            records.ensureReady()
            records.save(entry.copy(topic = GrowthTopics.forSave(entry.topic, entry.kind)))
        }
    }

    /** Explicit full export for callers that need it; feeds and topic pickers use page/topics. */
    suspend fun list(kind: DiaryEntryKind? = null): List<DiaryEntry> = withContext(Dispatchers.IO) {
        lock.withLock {
            records.ensureReady()
            records.list(kind)
        }
    }

    suspend fun page(
        kind: DiaryEntryKind,
        topic: String? = null,
        after: DiaryCursor? = null,
        limit: Int = PAGE_SIZE
    ): DiaryPage = withContext(Dispatchers.IO) {
        lock.withLock {
            require(limit in 1..PAGE_SIZE)
            records.ensureReady()
            records.page(kind, topic, after, limit)
        }
    }

    suspend fun topics(kind: DiaryEntryKind = DiaryEntryKind.GROWTH): List<DiaryTopic> = withContext(Dispatchers.IO) {
        lock.withLock {
            records.ensureReady()
            records.topics(kind)
        }
    }

    suspend fun get(id: String): DiaryEntry = withContext(Dispatchers.IO) {
        lock.withLock {
            require(UUID.fromString(id).toString() == id)
            records.ensureReady()
            records.get(id)
        }
    }

    /** Returns false only when the record was deleted but ancillary cleanup was incomplete. */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            require(UUID.fromString(id).toString() == id)
            records.ensureReady()
            val entry = records.get(id)
            val drafts = DiaryDraftStore(app, id)
            val candidates = entry.selection.files().toMutableSet()
            var cleanupComplete = true
            try {
                candidates.addAll(drafts.read()?.entry?.selection?.files().orEmpty())
            } catch (_: Exception) {
                cleanupComplete = false
            }
            records.delete(id)

            // The record is now deleted. Cleanup failure must not be reported as a failed deletion.
            try {
                if (drafts.clearCommitted()) {
                    if (!deleteUnreferencedLocked(candidates, emptySet())) {
                        cleanupComplete = false
                    }
                } else {
                    cleanupComplete = false
                }
            } catch (_: Exception) {
                cleanupComplete = false
            }
            cleanupComplete
        }
    }

    /** Only delete explicitly owned files, and fail closed if any reference cannot be read. */
    suspend fun deleteUnreferenced(candidates: Collection<String>, keep: Set<String> = emptySet()) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                records.ensureReady()
                deleteUnreferencedLocked(candidates, keep)
                Unit
            }
        }

    /** Caller holds lock; sharing this helper avoids re-entering the non-reentrant mutex. */
    private fun deleteUnreferencedLocked(candidates: Collection<String>, keep: Set<String>): Boolean {
        val referenced = keep.toMutableSet()
        referenced.addAll(DiaryDraftStore.referencedFiles(app))
        File(filesDir, "diary_pending").listFiles().orEmpty()
            .filter { it.extension == "json" }.forEach {
                val json = JSONObject(AtomicFile(it).readFully().toString(Charsets.UTF_8))
                referenced.addAll(PendingMedia.fromJson(json).files())
            }
        val root = File(filesDir, "diary_media").canonicalPath + File.separator
        var complete = true
        for (uri in candidates.distinct()) {
            if (uri in referenced || records.isMediaReferenced(uri)) continue
            val parsed = android.net.Uri.parse(uri)
            if (parsed.scheme != "file") continue
            val file = File(parsed.path ?: continue)
            if (file.canonicalPath.startsWith(root)) {
                if (file.exists() && !file.delete()) complete = false
            }
        }
        return complete
    }
}
