package com.orien.shadowing.di

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import com.orien.shadowing.data.local.AudioPlayer
import com.orien.shadowing.data.local.AudioRecorder
import com.orien.shadowing.data.local.db.AppDatabase
import com.orien.shadowing.data.local.db.MIGRATION_1_2
import com.orien.shadowing.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // --- Database ---

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "shadowing.db"
        )
            .addMigrations(MIGRATION_1_2)
            .apply {
                // Dev builds may bounce between APKs with different schema versions.
                if (isDebuggable) {
                    fallbackToDestructiveMigrationOnDowngrade()
                }
            }
            .build()
    }

    @Provides fun provideMaterialDao(db: AppDatabase): MaterialDao = db.materialDao()
    @Provides fun provideSentenceDao(db: AppDatabase): SentenceDao = db.sentenceDao()
    @Provides fun providePracticeRecordDao(db: AppDatabase): PracticeRecordDao = db.practiceRecordDao()
    @Provides fun provideSentenceLatestResultDao(db: AppDatabase): SentenceLatestResultDao = db.sentenceLatestResultDao()

    // --- Audio ---

    @Provides
    @Singleton
    fun provideAudioPlayer(@ApplicationContext context: Context): AudioPlayer =
        AudioPlayer(context)

    @Provides
    @Singleton
    fun provideAudioRecorder(@ApplicationContext context: Context): AudioRecorder =
        AudioRecorder(context)
}
