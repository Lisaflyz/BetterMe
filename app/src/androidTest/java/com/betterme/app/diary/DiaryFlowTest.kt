package com.betterme.app.diary

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.betterme.app.DiaryActivity
import com.betterme.app.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiaryFlowTest {
    @Test fun albumMixedSelectionPlaybackSaveAndReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val folder = "BetterMeTest-${System.currentTimeMillis()}"
        val title = "Media test ${UUID.randomUUID()}"
        val uris = mutableListOf<Uri>()
        val draft = app.getSharedPreferences("diary_draft", Context.MODE_PRIVATE)
        val previousDraft = draft.getString("entry", null)
        draft.edit().clear().commit()
        var scenario: ActivityScenario<DiaryActivity>? = null
        try {
            listOf("sound.mp4", "silent.mp4", "lake.jpg").forEach { name ->
                val image = name.endsWith("jpg")
                val collection = if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val uri = app.contentResolver.insert(collection, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (image) "image/jpeg" else "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/$folder")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })!!
                uris += uri
                app.contentResolver.openOutputStream(uri)!!.use { out -> instrumentation.context.assets.open(name).use { it.copyTo(out) } }
                app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            scenario = ActivityScenario.launch(Intent(app, DiaryActivity::class.java))
            scenario.onActivity {
                it.findViewById<EditText>(R.id.etTitle).setText(title)
                it.findViewById<EditText>(R.id.etContent).setText("文字不参与合成，返回后仍然保留。")
                it.findViewById<android.view.View>(R.id.btnAddPhoto).performClick()
            }
            // On a fresh install choose full access; existing grants remain untouched.
            val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller", "permission_allow_all_button")), 1500)
            allow?.click()
            device.wait(Until.findObject(By.res("com.betterme.app", "tvTitle")), 5000)!!.click()
            device.wait(Until.findObject(By.textStartsWith(folder)), 5000)!!.click()
            device.wait(Until.findObject(By.text("照片")), 5000)!!.click()
            device.wait(Until.findObject(By.desc("照片，未选择")), 5000)!!.click()
            device.findObject(By.text("视频")).click()
            device.wait(Until.findObject(By.desc("视频，未选择")), 5000)!!.click()
            device.wait(Until.findObject(By.desc("视频，未选择")), 5000)!!.click()
            assertEquals("下一步(3)", device.findObject(By.res("com.betterme.app", "btnNext")).text)
            device.takeScreenshot(File(app.getExternalFilesDir(null), "album-test.png"))
            device.findObject(By.res("com.betterme.app", "btnNext")).click()
            scenario.recreate()
            val play = device.wait(Until.findObject(By.desc("全屏播放视频")), 60000)
            assertNotNull("Composition did not produce a video", play)
            device.takeScreenshot(File(app.getExternalFilesDir(null), "diary-test.png"))
            play!!.click()
            assertTrue(device.wait(Until.hasObject(By.text("‹ 返回")), 5000))
            device.setOrientationLeft()
            assertTrue(device.wait(Until.hasObject(By.text("‹ 返回")), 5000))
            device.takeScreenshot(File(app.getExternalFilesDir(null), "player-test.png"))
            device.pressBack()
            device.setOrientationNatural()
            assertTrue(device.wait(Until.hasObject(By.res("com.betterme.app", "btnSave")), 5000))
            scenario.onActivity {
                assertEquals(title, it.findViewById<EditText>(R.id.etTitle).text.toString())
                it.findViewById<android.view.View>(R.id.btnSave).performClick()
            }
            device.wait(Until.gone(By.res("com.betterme.app", "btnSave")), 5000)
            val saved = runBlocking { DiaryStore(app).list().first { it.title == title } }
            assertEquals(3, saved.media.size)
            assertNotNull(saved.video)
            scenario.close()
            scenario = ActivityScenario.launch(Intent(app, DiaryActivity::class.java))
            scenario.onActivity { it.findViewById<android.view.View>(R.id.btnLibrary).performClick() }
            device.wait(Until.findObject(By.textContains(title)), 5000)!!.click()
            assertNotNull(device.wait(Until.findObject(By.desc("全屏播放视频")), 5000))
            scenario.onActivity { assertEquals("文字不参与合成，返回后仍然保留。", it.findViewById<EditText>(R.id.etContent).text.toString()) }
        } catch (error: Throwable) {
            device.takeScreenshot(File(app.getExternalFilesDir(null), "flow-failure.png"))
            device.dumpWindowHierarchy(File(app.getExternalFilesDir(null), "flow-failure.xml"))
            throw error
        } finally {
            scenario?.close()
            device.unfreezeRotation()
            draft.edit().clear().commit()
            runBlocking {
                val store = DiaryStore(app)
                store.list().filter { it.title == title }.forEach {
                    store.delete(it.id)
                    DiaryMediaRepository(app).discard(it.id)
                    store.deleteUnreferenced(it.selection.files())
                }
            }
            uris.forEach { runCatching { app.contentResolver.delete(it, null, null) } }
            draft.edit().putString("entry", previousDraft).commit()
        }
    }
}
