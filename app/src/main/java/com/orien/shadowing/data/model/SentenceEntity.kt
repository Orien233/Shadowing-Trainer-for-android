package com.orien.shadowing.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sentences",
    foreignKeys = [
        ForeignKey(
            entity = MaterialEntity::class,
            parentColumns = ["id"],
            childColumns = ["materialId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("materialId")]
)
data class SentenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val materialId: Long,
    val index: Int,
    val textOriginal: String,
    val textZh: String?,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val clipPath: String?,
    val createdAt: Long = System.currentTimeMillis()
)
