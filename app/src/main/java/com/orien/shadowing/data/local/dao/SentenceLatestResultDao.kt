package com.orien.shadowing.data.local.dao

import androidx.room.*
import com.orien.shadowing.data.model.SentenceLatestResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SentenceLatestResultDao {

    @Query("SELECT * FROM sentence_latest_results WHERE sentenceId = :sentenceId")
    suspend fun getLatestResult(sentenceId: Long): SentenceLatestResultEntity?

    @Query(
        """SELECT slr.* FROM sentence_latest_results slr 
           INNER JOIN sentences s ON slr.sentenceId = s.id 
           WHERE s.materialId = :materialId 
           ORDER BY s.`index` ASC"""
    )
    fun getLatestResultsByMaterial(materialId: Long): Flow<List<SentenceLatestResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(result: SentenceLatestResultEntity)

    @Query("DELETE FROM sentence_latest_results WHERE sentenceId = :sentenceId")
    suspend fun deleteBySentence(sentenceId: Long)
}
