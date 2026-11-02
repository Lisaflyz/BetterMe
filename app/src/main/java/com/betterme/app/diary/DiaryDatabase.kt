package com.betterme.app.diary

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DiaryMetadata::class, DiaryIndexState::class, DiaryMediaReference::class],
    version = 2,
    exportSchema = true
)
internal abstract class DiaryDatabase : RoomDatabase() {
    abstract fun metadata(): DiaryMetadataDao

    companion object {
        @Volatile
        private var instance: DiaryDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (column in listOf("date", "title", "content", "weather", "mood", "activity")) {
                    db.execSQL("ALTER TABLE diary_metadata ADD COLUMN `$column` TEXT NOT NULL DEFAULT ''")
                }
                db.execSQL("ALTER TABLE diary_metadata ADD COLUMN mediaJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE diary_metadata ADD COLUMN video TEXT")
                db.execSQL("ALTER TABLE diary_metadata ADD COLUMN fontSize INTEGER NOT NULL DEFAULT 16")
                db.execSQL("ALTER TABLE diary_metadata ADD COLUMN paper INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS diary_media_reference (
                        entryId TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        PRIMARY KEY(entryId, uri),
                        FOREIGN KEY(entryId) REFERENCES diary_metadata(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_diary_media_reference_uri ON diary_media_reference(uri)")
                // Keep the old metadata until the separate, atomic JSON import succeeds.
                // The v1 marker (id = 1) must never imply that complete records were imported.
            }
        }

        fun get(context: Context): DiaryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                DiaryDatabase::class.java,
                "diary_metadata.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
