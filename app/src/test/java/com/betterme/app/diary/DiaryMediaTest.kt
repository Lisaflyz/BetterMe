package com.betterme.app.diary

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiaryMediaTest {
    private val photo = DiaryMedia("file:///photo.jpg", DiaryMediaKind.IMAGE)
    private val video = DiaryMedia("file:///video.mp4", DiaryMediaKind.VIDEO, durationMs = 2000)
    @Test fun compositionOnlyWhenThereAreMultipleItemsIncludingVideo() {
        assertFalse(MediaPlan.requiresComposition(emptyList()))
        assertFalse(MediaPlan.requiresComposition(listOf(photo, photo)))
        assertFalse(MediaPlan.requiresComposition(listOf(video)))
        assertTrue(MediaPlan.requiresComposition(listOf(photo, video)))
        assertTrue(MediaPlan.requiresComposition(listOf(video, video)))
    }
    @Test fun dimensionsFollowDisplayOrientationAndNeverUpscale() {
        assertEquals(1280 to 720, MediaPlan.outputSize(1920, 1080))
        assertEquals(720 to 1280, MediaPlan.outputSize(1080, 1920))
        assertEquals(320 to 240, MediaPlan.outputSize(320, 240))
        val size = MediaPlan.outputSize(853, 479)
        assertEquals(0, size.first % 2)
        assertEquals(0, size.second % 2)
        assertTrue(size.first <= 853 && size.second <= 479)
    }
    @Test fun oldPhotoDiaryMigratesAndRoundTripsWithoutLosingText() {
        val old = JSONObject("""{"id":"d32710c6-f7e7-4b52-8174-76f648ac1492","date":"2026-10-21","content":"old diary","photos":["content://old/2","content://old/1"]}""")
        val entry = DiaryEntry.fromJson(old)
        assertEquals(listOf("content://old/2", "content://old/1"), entry.media.map { it.uri })
        assertTrue(entry.media.all { it.kind == DiaryMediaKind.IMAGE })
        assertEquals(entry, DiaryEntry.fromJson(entry.toJson()))
    }
    @Test fun orderedSourcesAndOutputRemainSeparateAndDurationsIncludePhotos() {
        val selection = DiaryMediaSelection(listOf(photo, video), "file:///output.mp4")
        assertEquals(selection, DiaryMediaSelection.fromJson(selection.toJson()))
        assertEquals(5000L, selection.durationMs)
        assertEquals("file:///output.mp4", selection.playbackUri)
        assertEquals(video.uri, DiaryMediaSelection(listOf(video)).playbackUri)
        assertNull(DiaryMediaSelection(listOf(photo)).playbackUri)
    }
    @Test fun pendingOperationPersistsOriginalResultUntilCommit() {
        val original = DiaryMediaSelection(listOf(video))
        val pending = PendingMedia(listOf(photo.uri, video.uri), original, listOf(photo), listOf(photo.uri))
        val restored = PendingMedia.fromJson(pending.toJson())
        assertEquals(pending, restored)
        assertNull(restored.result)
        assertEquals(original, restored.previous)
    }
}
