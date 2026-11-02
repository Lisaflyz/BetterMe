package com.betterme.app.diary

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

enum class DiaryMediaKind { IMAGE, VIDEO }

data class DiaryMedia(
    val uri: String,
    val kind: DiaryMediaKind,
    val sourceUri: String = uri,
    val mimeType: String = if (kind == DiaryMediaKind.IMAGE) "image/jpeg" else "video/mp4",
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0
) {
    fun toJson() = JSONObject().apply {
        put("uri", uri)
        put("kind", kind.name)
        put("sourceUri", sourceUri)
        put("mimeType", mimeType)
        put("durationMs", durationMs)
        put("width", width)
        put("height", height)
    }

    companion object {
        fun fromJson(json: JSONObject) = DiaryMedia(
            uri = json.getString("uri"),
            kind = DiaryMediaKind.valueOf(json.getString("kind")),
            sourceUri = json.optString("sourceUri", json.getString("uri")),
            mimeType = json.optString("mimeType", ""),
            durationMs = json.optLong("durationMs"),
            width = json.optInt("width"), height = json.optInt("height")
        )
        fun fromArray(array: JSONArray?): List<DiaryMedia> = array?.let {
            (0 until it.length()).map { i -> fromJson(it.getJSONObject(i)) }
        }.orEmpty()
    }
}

data class DiaryMediaSelection(val media: List<DiaryMedia> = emptyList(), val video: String? = null) {
    val playbackUri: String? get() = video ?: media.singleOrNull()
        ?.takeIf { it.kind == DiaryMediaKind.VIDEO }?.uri
    val durationMs: Long get() = media.sumOf {
        if (it.kind == DiaryMediaKind.IMAGE) MediaPlan.IMAGE_DURATION_MS else it.durationMs
    }
    fun files(): Set<String> = media.map { it.uri }.toSet() + listOfNotNull(video)
    fun toJson() = JSONObject().apply {
        put("media", JSONArray(media.map { it.toJson() }))
        put("video", video ?: JSONObject.NULL)
    }
    companion object {
        fun fromJson(json: JSONObject) = DiaryMediaSelection(
            DiaryMedia.fromArray(json.optJSONArray("media")),
            json.optString("video").takeUnless { it.isBlank() || it == "null" }
        )
    }
}

object MediaPlan {
    const val MAX_ITEMS = 9
    const val IMAGE_DURATION_MS = 3_000L
    fun requiresComposition(items: List<DiaryMedia>) =
        items.size > 1 && items.any { it.kind == DiaryMediaKind.VIDEO }

    /** Width/height already include the source rotation. Do not upscale small inputs. */
    fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(1.0, 720.0 / min(width, height))
        fun even(value: Int) = ((value * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return even(width) to even(height)
    }
}
