package com.orien.shadowing.data.local.dao

import androidx.room.*
import com.orien.shadowing.data.model.MaterialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MaterialDao {

    @Query("SELECT * FROM materials ORDER BY createdAt DESC")
    fun getAllMaterials(): Flow<List<MaterialEntity>>

    @Query("SELECT * FROM materials WHERE id = :id")
    suspend fun getMaterialById(id: Long): MaterialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(material: MaterialEntity): Long

    @Update
    suspend fun update(material: MaterialEntity)

    @Query("UPDATE materials SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameById(id: Long, title: String, updatedAt: Long)

    @Delete
    suspend fun delete(material: MaterialEntity)

    @Query("DELETE FROM materials WHERE id = :id")
    suspend fun deleteById(id: Long)
}
