package com.orien.shadowing.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "sentence_latest_results",
    foreignKeys = [
        ForeignKey(
            entity = SentenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sentenceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SentenceLatestResultEntity(
    @PrimaryKey
    val sentenceId: Long,
    val latestPracticeRecordId: Long,
    val latestRecognizedText: String,
    val latestScore: Float,
    val updatedAt: Long = System.currentTimeMillis()
)
