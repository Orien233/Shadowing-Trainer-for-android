package com.orien.shadowing.data.local.dao

import androidx.room.*
import com.orien.shadowing.data.model.PracticeRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PracticeRecordDao {

    @Query("SELECT * FROM practice_records WHERE sentenceId = :sentenceId ORDER BY createdAt DESC")
    fun getRecordsBySentence(sentenceId: Long): Flow<List<PracticeRecordEntity>>

    @Query("SELECT * FROM practice_records WHERE materialId = :materialId ORDER BY createdAt DESC")
    fun getRecordsByMaterial(materialId: Long): Flow<List<PracticeRecordEntity>>

    @Query("SELECT * FROM practice_records WHERE id = :id")
    suspend fun getRecordById(id: Long): PracticeRecordEntity?

    @Insert
    suspend fun insert(record: PracticeRecordEntity): Long

    @Query("DELETE FROM practice_records WHERE sentenceId = :sentenceId")
    suspend fun deleteBySentence(sentenceId: Long)
}
