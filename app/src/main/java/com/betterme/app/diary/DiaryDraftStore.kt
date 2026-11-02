package com.betterme.app.diary

import android.content.Context
import org.json.JSONObject

data class DiaryDraft(val entry: DiaryEntry, val initialFingerprint: String?)

/** Daily drafts keep legacy keys; growth, todo and edited entries have independent keys. */
class DiaryDraftStore(
    context: Context,
    private val entryId: String?,
    private val newEntryKind: DiaryEntryKind = DiaryEntryKind.DAILY
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val suffix = entryId ?: when (newEntryKind) {
        DiaryEntryKind.DAILY -> null
        DiaryEntryKind.GROWTH -> "growth"
        DiaryEntryKind.TODO -> "todo"
    }
    private val entryKey = suffix?.let { "entry:$it" } ?: "entry"
    private val fingerprintKey = suffix?.let { "initialFingerprint:$it" } ?: "initialFingerprint"

    fun read(): DiaryDraft? {
        val json = preferences.getString(entryKey, null) ?: return null
        val entry = DiaryEntry.fromJson(JSONObject(json))
        require(entryId == null || entry.id == entryId) { "草稿与日记不匹配" }
        require(entryId != null || entry.kind == newEntryKind) { "草稿与记录类型不匹配" }
        return DiaryDraft(entry, preferences.getString(fingerprintKey, null))
    }

    fun save(entry: DiaryEntry, initialFingerprint: String) {
        require(entryId == null || entry.id == entryId)
        require(entryId != null || entry.kind == newEntryKind)
        preferences.edit().putString(entryKey, entry.toJson().toString())
            .putString(fingerprintKey, initialFingerprint).apply()
    }

    fun clear() {
        preferences.edit().remove(entryKey).remove(fingerprintKey).apply()
    }

    /** Called on IO after deleting a saved entry, so cleanup follows persisted draft removal. */
    fun clearCommitted(): Boolean = preferences.edit()
        .remove(entryKey).remove(fingerprintKey).commit()

    companion object {
        private const val PREFERENCES = "diary_draft"

        /** Fail closed if any draft is unreadable, so cleanup never deletes another draft's media. */
        fun referencedFiles(context: Context): Set<String> = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).all
            .filterKeys { it == "entry" || it.startsWith("entry:") }
            .values.flatMap { value ->
                DiaryEntry.fromJson(JSONObject(value as String)).selection.files()
            }.toSet()
    }
}
