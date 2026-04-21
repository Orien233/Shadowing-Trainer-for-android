package com.orien.shadowing.data.local.dao

import androidx.room.*
import com.orien.shadowing.data.model.SentenceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SentenceDao {

    @Query("SELECT * FROM sentences WHERE materialId = :materialId ORDER BY `index` ASC")
    fun getSentencesByMaterial(materialId: Long): Flow<List<SentenceEntity>>

    @Query("SELECT * FROM sentences WHERE id = :id")
    suspend fun getSentenceById(id: Long): SentenceEntity?

    @Query("SELECT * FROM sentences WHERE materialId = :materialId ORDER BY `index` ASC LIMIT 1")
    suspend fun getFirstSentence(materialId: Long): SentenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sentence: SentenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sentences: List<SentenceEntity>)

    @Query("SELECT COUNT(*) FROM sentences WHERE materialId = :materialId")
    suspend fun countByMaterial(materialId: Long): Int

    @Query("SELECT * FROM sentences WHERE materialId = :materialId AND `index` > :currentIndex ORDER BY `index` ASC LIMIT 1")
    suspend fun getNextSentence(materialId: Long, currentIndex: Int): SentenceEntity?

    @Query("SELECT * FROM sentences WHERE materialId = :materialId AND `index` < :currentIndex ORDER BY `index` DESC LIMIT 1")
    suspend fun getPrevSentence(materialId: Long, currentIndex: Int): SentenceEntity?

    @Query("DELETE FROM sentences WHERE materialId = :materialId")
    suspend fun deleteByMaterial(materialId: Long)
}
