package com.orien.shadowing.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.orien.shadowing.data.local.dao.MaterialDao
import com.orien.shadowing.data.local.dao.PracticeRecordDao
import com.orien.shadowing.data.local.dao.SentenceDao
import com.orien.shadowing.data.local.dao.SentenceLatestResultDao
import com.orien.shadowing.data.model.MaterialEntity
import com.orien.shadowing.data.model.PracticeRecordEntity
import com.orien.shadowing.data.model.SentenceEntity
import com.orien.shadowing.data.model.SentenceLatestResultEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE materials ADD COLUMN fallbackAudioPath TEXT"
        )
    }
}

@Database(
    entities = [
        MaterialEntity::class,
        SentenceEntity::class,
        PracticeRecordEntity::class,
        SentenceLatestResultEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun materialDao(): MaterialDao
    abstract fun sentenceDao(): SentenceDao
    abstract fun practiceRecordDao(): PracticeRecordDao
    abstract fun sentenceLatestResultDao(): SentenceLatestResultDao
}
