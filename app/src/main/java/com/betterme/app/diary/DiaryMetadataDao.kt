package com.betterme.app.diary

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface DiaryMetadataDao {
    @Query("SELECT EXISTS(SELECT 1 FROM diary_index_state WHERE id = 2)")
    fun isImported(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun markImported(state: DiaryIndexState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: DiaryMetadata)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMediaReferences(references: List<DiaryMediaReference>)

    @Query("SELECT EXISTS(SELECT 1 FROM diary_media_reference WHERE uri = :uri)")
    fun isMediaReferenced(uri: String): Boolean

    @Query("SELECT * FROM diary_metadata WHERE id = :id")
    fun get(id: String): DiaryMetadata?

    @Query("SELECT * FROM diary_metadata WHERE :kind IS NULL OR kind = :kind ORDER BY updatedAt DESC, id DESC")
    fun list(kind: String?): List<DiaryMetadata>

    @Query("DELETE FROM diary_metadata WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM diary_metadata")
    fun clear()

    @Query("SELECT * FROM diary_metadata WHERE kind = :kind ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    fun firstPage(kind: String, limit: Int): List<DiaryMetadata>

    @Query("""SELECT * FROM diary_metadata WHERE kind = :kind AND updatedAt <= :time
        AND (updatedAt < :time OR id < :id)
        ORDER BY updatedAt DESC, id DESC LIMIT :limit""")
    fun nextPage(kind: String, time: Long, id: String, limit: Int): List<DiaryMetadata>

    @Query("""SELECT * FROM diary_metadata WHERE kind = :kind AND topicKey = :topic
        ORDER BY updatedAt DESC, id DESC LIMIT :limit""")
    fun firstTopicPage(kind: String, topic: String, limit: Int): List<DiaryMetadata>

    @Query("""SELECT * FROM diary_metadata WHERE kind = :kind AND topicKey = :topic
        AND updatedAt <= :time AND (updatedAt < :time OR id < :id)
        ORDER BY updatedAt DESC, id DESC LIMIT :limit""")
    fun nextTopicPage(kind: String, topic: String, time: Long, id: String, limit: Int): List<DiaryMetadata>

    @Query("""SELECT EXISTS(SELECT 1 FROM diary_metadata
        WHERE kind = :kind AND updatedAt <= :time AND (updatedAt < :time OR id < :id))""")
    fun hasNextPage(kind: String, time: Long, id: String): Boolean

    @Query("""SELECT EXISTS(SELECT 1 FROM diary_metadata
        WHERE kind = :kind AND topicKey = :topic
        AND updatedAt <= :time AND (updatedAt < :time OR id < :id))""")
    fun hasNextTopicPage(kind: String, topic: String, time: Long, id: String): Boolean

    // Take the label from the newest entry, including a deterministic ID tie-breaker.
    @Query("""SELECT grouped.topicKey AS `key`,
        (SELECT newest.topicLabel FROM diary_metadata AS newest
         WHERE newest.kind = :kind AND newest.topicKey = grouped.topicKey
         ORDER BY newest.updatedAt DESC, newest.id DESC LIMIT 1) AS label,
        grouped.total AS count
        FROM (SELECT topicKey, COUNT(*) AS total, MAX(updatedAt) AS latest
              FROM diary_metadata WHERE kind = :kind GROUP BY topicKey) AS grouped
        ORDER BY grouped.latest DESC, grouped.topicKey ASC""")
    fun topics(kind: String): List<DiaryTopic>
}
