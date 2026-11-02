package com.betterme.app.diary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

/** Complete saved record. The original table name is retained for database upgrades. */
@Entity(
    tableName = "diary_metadata",
    indices = [
        Index(value = ["kind", "updatedAt", "id"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]),
        Index(value = ["kind", "topicKey", "updatedAt", "id"], orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.DESC])
    ]
)
internal data class DiaryMetadata(
    @PrimaryKey val id: String,
    val kind: String,
    val topicKey: String,
    val topicLabel: String,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "''") val date: String,
    @ColumnInfo(defaultValue = "''") val title: String,
    @ColumnInfo(defaultValue = "''") val content: String,
    @ColumnInfo(defaultValue = "''") val weather: String,
    @ColumnInfo(defaultValue = "''") val mood: String,
    @ColumnInfo(defaultValue = "''") val activity: String,
    @ColumnInfo(defaultValue = "'[]'") val mediaJson: String,
    val video: String?,
    @ColumnInfo(defaultValue = "16") val fontSize: Int,
    @ColumnInfo(defaultValue = "0") val paper: Int
) {
    fun toEntry() = DiaryEntry(
        id = id,
        date = date,
        title = title,
        content = content,
        weather = weather,
        mood = mood,
        activity = activity,
        media = DiaryMedia.fromArray(JSONArray(mediaJson)),
        video = video,
        fontSize = fontSize,
        paper = paper,
        updatedAt = updatedAt,
        kind = DiaryEntryKind.valueOf(kind),
        topic = topicLabel
    )

    companion object {
        fun from(entry: DiaryEntry) = DiaryMetadata(
            id = entry.id,
            kind = entry.kind.name,
            topicKey = GrowthTopics.key(entry.topic),
            topicLabel = GrowthTopics.normalize(entry.topic),
            updatedAt = entry.updatedAt,
            date = entry.date,
            title = entry.title,
            content = entry.content,
            weather = entry.weather,
            mood = entry.mood,
            activity = entry.activity,
            mediaJson = JSONArray(entry.media.map { it.toJson() }).toString(),
            video = entry.video,
            fontSize = entry.fontSize,
            paper = entry.paper
        )
    }
}

/** Row 1 is the legacy index marker; row 2 means complete JSON import was committed. */
@Entity(tableName = "diary_index_state")
internal data class DiaryIndexState(@PrimaryKey val id: Int = 2)

/** Indexed references avoid loading every saved record when cleaning up media. */
@Entity(
    tableName = "diary_media_reference",
    primaryKeys = ["entryId", "uri"],
    indices = [Index(value = ["uri"])],
    foreignKeys = [
        ForeignKey(
            entity = DiaryMetadata::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
internal data class DiaryMediaReference(val entryId: String, val uri: String) {
    companion object {
        fun from(entry: DiaryEntry): List<DiaryMediaReference> =
            entry.selection.files().map { DiaryMediaReference(entry.id, it) }
    }
}
