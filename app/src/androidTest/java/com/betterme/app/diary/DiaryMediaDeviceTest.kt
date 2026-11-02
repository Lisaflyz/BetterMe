package com.betterme.app.diary

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiaryMediaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private fun fixture(name: String): File = File(app.cacheDir, "test-$name").also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
    }
    private fun media(name: String): DiaryMedia {
        val file = fixture(name)
        return when (name) {
            "lake.jpg" -> DiaryMedia(Uri.fromFile(file).toString(), DiaryMediaKind.IMAGE, width = 480, height = 320)
            "sound.mp4" -> DiaryMedia(Uri.fromFile(file).toString(), DiaryMediaKind.VIDEO, durationMs = 2000, width = 320, height = 240)
            else -> DiaryMedia(Uri.fromFile(file).toString(), DiaryMediaKind.VIDEO, durationMs = 2000, width = 240, height = 320)
        }
    }
    @Test fun photoFirstMixedExportHasOrderedPicturesAudioAndExpectedGeometry() = runBlocking {
        val output = File(app.cacheDir, "verified-mixed.mp4")
        withTimeout(90000) { DiaryVideoComposer(app).export(listOf(media("lake.jpg"), media("sound.mp4"), media("silent.mp4")), output) {} }
        assertTrue(output.length() > 1000)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("duration=$duration", duration in 6800..7300)
            assertEquals("320", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("240", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            val photo = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val blue = retriever.getFrameAtTime(4_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val red = retriever.getFrameAtTime(6_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            fun center(b: android.graphics.Bitmap) = b.getPixel(b.width / 2, b.height / 2)
            assertTrue(android.graphics.Color.red(center(photo)) > 150)
            assertTrue(android.graphics.Color.blue(center(blue)) > 150)
            assertTrue(android.graphics.Color.red(center(red)) > 150)
            photo.recycle()
            blue.recycle()
            red.recycle()
        } finally { retriever.release() }
        assertTracks(output)
    }
    @Test fun silentFirstVideoStillRetainsLaterAudioAndPortraitOutput() = runBlocking {
        val output = File(app.cacheDir, "verified-portrait.mp4")
        withTimeout(90000) { DiaryVideoComposer(app).export(listOf(media("silent.mp4"), media("sound.mp4")), output) {} }
        assertTracks(output)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rotated = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)!!.toInt() % 180 != 0
            assertEquals(240, if (rotated) height else width)
            assertEquals(320, if (rotated) width else height)
        } finally { retriever.release() }
    }
    @Test fun cancellingAnExportRemovesTheIncompleteOutput() = runBlocking {
        val output = File(app.cacheDir, "cancelled-export.mp4")
        val items = listOf(media("lake.jpg")) + List(8) { media("sound.mp4") }
        val task = launch { DiaryVideoComposer(app).export(items, output) {} }
        delay(100)
        task.cancelAndJoin()
        assertFalse(output.exists())
    }
    @Test fun originalsArePrivateAndCleanupProtectsSavedReferences() = runBlocking {
        val id = UUID.randomUUID().toString()
        val repo = DiaryMediaRepository(app)
        val store = DiaryStore(app)
        val source = fixture("lake.jpg")
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "BetterMe-test-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        })!!
        var imported: DiaryMedia? = null
        try {
            app.contentResolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            val pending = repo.import(id, repo.begin(id, listOf(uri.toString()), DiaryMediaSelection())) {}
            imported = pending.imported.single()
            assertTrue(repo.isLocal(imported.uri))
            app.contentResolver.delete(uri, null, null)
            assertTrue(File(Uri.parse(imported.uri).path!!).isFile)
            val entry = DiaryEntry(id = id, date = "2026-10-21", media = listOf(imported))
            store.save(entry)
            repo.discard(id)
            assertTrue(File(Uri.parse(imported.uri).path!!).isFile)
            assertEquals(entry, store.list().first { it.id == id })
        } finally {
            runCatching { app.contentResolver.delete(uri, null, null) }
            if (store.list().any { it.id == id }) {
                store.delete(id)
            }
            repo.discard(id)
            imported?.let { repo.prune(listOf(it.uri), emptySet()) }
        }
        assertFalse(File(Uri.parse(imported!!.uri).path!!).exists())
    }
    private fun assertTracks(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "video/avc" })
            assertTrue(formats.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
        } finally { extractor.release() }
    }
}
