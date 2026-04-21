package com.orien.shadowing.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "practice_records",
    foreignKeys = [
        ForeignKey(
            entity = MaterialEntity::class,
            parentColumns = ["id"],
            childColumns = ["materialId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SentenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sentenceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("materialId"), Index("sentenceId")]
)
data class PracticeRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val materialId: Long,
    val sentenceId: Long,
    val recordingPath: String,
    val recognizedText: String,
    val matchScore: Float,
    val errorTags: String?, // JSON array of error descriptions
    val createdAt: Long = System.currentTimeMillis()
)
