package com.orien.shadowing.di

import android.content.Context
import androidx.room.Room
import com.orien.shadowing.data.local.AudioPlayer
import com.orien.shadowing.data.local.AudioRecorder
import com.orien.shadowing.data.local.MoonshineAsr
import com.orien.shadowing.data.local.db.AppDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "shadowing.db"
        )
            .build()

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

    // --- ASR ---

    @Provides
    @Singleton
    fun provideMoonshineAsr(@ApplicationContext context: Context): MoonshineAsr =
        MoonshineAsr(context)
}
