package com.orien.shadowing.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.orien.shadowing.data.local.dao.*
import com.orien.shadowing.data.model.*

@Database(
    entities = [
        MaterialEntity::class,
        SentenceEntity::class,
        PracticeRecordEntity::class,
        SentenceLatestResultEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun materialDao(): MaterialDao
    abstract fun sentenceDao(): SentenceDao
    abstract fun practiceRecordDao(): PracticeRecordDao
    abstract fun sentenceLatestResultDao(): SentenceLatestResultDao
}
