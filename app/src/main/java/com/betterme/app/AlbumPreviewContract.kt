package com.betterme.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Pass one preview item plus at most nine selections, never an entire album through Binder. */
object AlbumPreviewContract {
    const val CURRENT = "preview_current"
    const val SELECTED = "preview_selected"
    const val CONFIRM = "preview_confirm"
    const val LAST_LIMIT_TOAST = "preview_last_limit_toast"

    fun intent(context: Context, current: MediaItem, selected: Collection<MediaItem>, lastLimitToast: Long) =
        Intent(context, AlbumPreviewActivity::class.java)
            .putExtra(CURRENT, encode(current).toString())
            .putExtra(SELECTED, encodeList(selected))
            .putExtra(LAST_LIMIT_TOAST, lastLimitToast)

    fun encode(item: MediaItem) = JSONObject().apply {
        put("uri", item.uri.toString())
        put("id", item.id)
        put("kind", item.kind.name)
        put("dateAdded", item.dateAdded)
        put("durationMs", item.durationMs)
        put("bucketId", item.bucketId)
        put("bucketName", item.bucketName)
        put("localUri", item.localUri?.toString() ?: JSONObject.NULL)
    }

    fun decode(json: JSONObject) = MediaItem(
        Uri.parse(json.getString("uri")), json.optLong("id"), MediaItem.Kind.valueOf(json.getString("kind")),
        json.optLong("dateAdded"), json.optLong("durationMs"), json.optString("bucketId"),
        json.optString("bucketName", "相册"),
        json.optString("localUri").takeUnless { it.isBlank() || it == "null" }?.let(Uri::parse)
    )

    fun encodeList(items: Collection<MediaItem>): String = JSONArray(items.map(::encode)).toString()
    fun decodeList(value: String): List<MediaItem> = JSONArray(value).let { array ->
        (0 until minOf(array.length(), AlbumActivity.MAX_SELECT)).map { decode(array.getJSONObject(it)) }
            .distinctBy { it.uri }
    }
}
