package com.betterme.app

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Xml
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.nio.ByteBuffer

/** Detect standard Motion Photo / legacy MicroVideo metadata without decoding the full image.
 * Verify the embedded video too: editors may leave XMP behind after stripping the video.
 * Independent JPEG/MOV pairs and proprietary vendor formats without this metadata are not guessed.
 */
class MotionPhotoDetector(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val cache = LruCache<String, Boolean>(256)

    suspend fun isMotionPhoto(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Keep the guarded call in the same branch so API checks do not have to
        // infer a minimum version across labelled returns and nested lambdas.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            detectMotionPhotoApi29(uri)
        } else {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun detectMotionPhotoApi29(uri: Uri): Boolean {
        cache.get(uri.toString())?.let { return it }
        return try {
            val xmp = indexedXmp(uri) ?: resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttribute(ExifInterface.TAG_XMP)
            }
            val video = xmp?.let(::parseVideo)
            val result = video != null && hasEmbeddedVideo(uri, video)
            cache.put(uri.toString(), result)
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false } // Missing metadata or revoked access must not hide the photo.
    }

    private fun indexedXmp(uri: Uri): String? {
        if (Build.VERSION.SDK_INT < 31 || uri.scheme != "content") return null
        return try {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.XMP), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getBlob(0)?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) { null } // Some providers do not expose an indexed XMP column.
    }

    private data class Part(val mime: String?, val length: Long, val padding: Long)
    private data class Video(val offsetFromEnd: Long, val length: Long)

    private fun parseVideo(xmp: String): Video? {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(xmp))
        }
        var motion: Int? = null
        var legacy: Int? = null
        var legacyOffset = 0L
        val parts = mutableListOf<Part>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            for (i in 0 until parser.attributeCount) {
                if (parser.getAttributeNamespace(i) == "http://ns.google.com/photos/1.0/camera/") {
                    val value = parser.getAttributeValue(i)
                    when (parser.getAttributeName(i)) {
                        "MotionPhoto" -> motion = value.toIntOrNull()
                        "MicroVideo" -> legacy = value.toIntOrNull()
                        "MicroVideoOffset" -> legacyOffset = value.toLongOrNull() ?: 0
                    }
                }
            }
            if (parser.name == "Item") {
                val ns = "http://ns.google.com/photos/1.0/container/item/"
                parts += Part(parser.getAttributeValue(ns, "Mime"),
                    parser.getAttributeValue(ns, "Length")?.toLongOrNull() ?: 0,
                    parser.getAttributeValue(ns, "Padding")?.toLongOrNull() ?: 0)
            }
        }
        if ((motion ?: legacy) != 1) return null
        var trailing = 0L
        for (part in parts.asReversed()) {
            if (part.length < 0 || part.padding < 0) return null
            if (part.length > Long.MAX_VALUE - part.padding) return null
            val bytes = part.length + part.padding
            if (trailing > Long.MAX_VALUE - bytes) return null
            trailing += bytes
            if (part.mime == "video/mp4" && part.length >= 12) return Video(trailing, part.length)
        }
        return legacyOffset.takeIf { it >= 12 }?.let { Video(it, it) }
    }

    private fun hasEmbeddedVideo(uri: Uri, video: Video): Boolean {
        return resolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
            asset.createInputStream().use stream@ { input ->
                val channel = input.channel
                val length = if (asset.length >= 0) asset.length else channel.size() - asset.startOffset
                if (video.offsetFromEnd <= 0 || video.offsetFromEnd >= length || video.length > video.offsetFromEnd) return@stream false
                channel.position(asset.startOffset + length - video.offsetFromEnd)
                val header = ByteBuffer.allocate(12)
                while (header.hasRemaining()) if (channel.read(header) <= 0) return@stream false
                val bytes = header.array()
                // ISO BMFF video starts with its file-type box; do not label an ordinary still as live.
                String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp"
            }
        } ?: false
    }
}
