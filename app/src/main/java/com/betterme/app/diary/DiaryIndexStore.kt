package com.betterme.app.diary

import android.content.Context
import java.util.concurrent.Callable

data class DiaryCursor internal constructor(val updatedAt: Long, val id: String)
data class DiaryPage(val entries: List<DiaryEntry>, val next: DiaryCursor?)
data class DiaryTopic(val key: String, val label: String, val count: Int)

/** Complete Room records; calls run on DiaryStore's IO dispatcher under its mutex. */
internal class DiaryIndexStore(context: Context) {
    private val database = DiaryDatabase.get(context)
    private val dao get() = database.metadata()
    private val migrator = DiaryLegacyMigrator(context, database)

    fun ensureReady() {
        migrator.ensureImported()
    }

    fun save(entry: DiaryEntry) {
        database.runInTransaction {
            // REPLACE removes the previous row and its cascading media references.
            dao.upsert(DiaryMetadata.from(entry))
            dao.insertMediaReferences(DiaryMediaReference.from(entry))
        }
    }

    fun delete(id: String) {
        dao.delete(id)
    }

    fun get(id: String): DiaryEntry = checkNotNull(dao.get(id)) { "日记不存在" }.toEntry()

    fun list(kind: DiaryEntryKind?): List<DiaryEntry> = dao.list(kind?.name).map { it.toEntry() }

    fun isMediaReferenced(uri: String): Boolean = dao.isMediaReferenced(uri)

    fun page(kind: DiaryEntryKind, topic: String?, after: DiaryCursor?, limit: Int): DiaryPage =
        database.runInTransaction(Callable {
            val topicKey = topic?.let(GrowthTopics::key)
            val rows = when {
                topicKey == null && after == null -> dao.firstPage(kind.name, limit)
                topicKey == null && after != null -> dao.nextPage(kind.name, after.updatedAt, after.id, limit)
                after == null -> dao.firstTopicPage(kind.name, requireNotNull(topicKey), limit)
                else -> dao.nextTopicPage(kind.name, requireNotNull(topicKey), after.updatedAt, after.id, limit)
            }
            val last = rows.lastOrNull()
            val hasMore = last != null && rows.size == limit && when (topicKey) {
                null -> dao.hasNextPage(kind.name, last.updatedAt, last.id)
                else -> dao.hasNextTopicPage(kind.name, topicKey, last.updatedAt, last.id)
            }
            val next = if (hasMore && last != null) DiaryCursor(last.updatedAt, last.id) else null
            DiaryPage(rows.map { it.toEntry() }, next)
        })

    fun topics(kind: DiaryEntryKind): List<DiaryTopic> = dao.topics(kind.name)
}
