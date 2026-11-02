package com.betterme.app.diary

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One-time import, called under DiaryStore's mutex before any saved-record access. */
internal class DiaryLegacyMigrator(context: Context, private val database: DiaryDatabase) {
    private val directory = File(context.filesDir, "diary_entries")

    fun ensureImported() {
        val dao = database.metadata()
        // Once committed, neither pagination nor writes inspect the legacy directory again.
        if (dao.isImported()) {
            return
        }
        database.runInTransaction {
            if (!dao.isImported()) {
                val files = if (directory.exists()) {
                    checkNotNull(directory.listFiles()) { "无法读取日记目录" }
                } else {
                    emptyArray<File>()
                }
                dao.clear()
                val importedNames = mutableSetOf<String>()
                for (file in files) {
                    val name = when {
                        file.name.endsWith(".json") -> file.name
                        file.name.endsWith(".json.bak") -> file.name.removeSuffix(".bak")
                        else -> continue
                    }
                    if (!importedNames.add(name)) {
                        continue
                    }
                    val bytes = AtomicFile(File(directory, name)).readFully()
                    val entry = DiaryEntry.fromJson(JSONObject(bytes.toString(Charsets.UTF_8)))
                    require(UUID.fromString(entry.id).toString() == entry.id)
                    check(name == "${entry.id}.json") { "日记文件与 ID 不匹配" }
                    dao.upsert(DiaryMetadata.from(entry))
                    dao.insertMediaReferences(DiaryMediaReference.from(entry))
                }
                // Import rows, references and completion marker commit or roll back together.
                // Original JSON files stay on disk for recovery; they are never imported again.
                dao.markImported(DiaryIndexState())
            }
        }
    }
}
