package com.orien.shadowing.data.local.repository

import com.orien.shadowing.data.local.dao.MaterialDao
import com.orien.shadowing.data.local.dao.SentenceDao
import com.orien.shadowing.data.model.MaterialEntity
import com.orien.shadowing.data.model.SentenceEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MaterialRepository @Inject constructor(
    private val materialDao: MaterialDao,
    private val sentenceDao: SentenceDao
) {

    fun getAllMaterials(): Flow<List<MaterialEntity>> = materialDao.getAllMaterials()

    suspend fun getMaterial(id: Long): MaterialEntity? = materialDao.getMaterialById(id)

    suspend fun createMaterial(
        title: String,
        type: String,
        sourcePath: String? = null,
        fallbackAudioPath: String? = null,
        coverPath: String? = null,
        language: String = "en"
    ): Long {
        val material = MaterialEntity(
            title = title,
            type = type,
            sourcePath = sourcePath,
            fallbackAudioPath = fallbackAudioPath,
            coverPath = coverPath,
            language = language
        )
        return materialDao.insert(material)
    }

    suspend fun updateMaterial(material: MaterialEntity) {
        materialDao.update(material.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun renameMaterial(materialId: Long, title: String) {
        materialDao.renameById(
            id = materialId,
            title = title,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Delete material and all associated files in storage directory.
     * Room CASCADE handles DB rows, this handles filesystem cleanup.
     */
    suspend fun deleteMaterial(materialId: Long, storageRoot: File) {
        // Delete DB rows (cascade handles sentences, records, latest results)
        materialDao.deleteById(materialId)

        // Delete files
        val materialDir = File(storageRoot, "materials/$materialId")
        if (materialDir.exists()) {
            materialDir.deleteRecursively()
        }
    }

    // --- Sentences ---

    fun getSentences(materialId: Long): Flow<List<SentenceEntity>> =
        sentenceDao.getSentencesByMaterial(materialId)

    suspend fun insertSentences(sentences: List<SentenceEntity>) {
        sentenceDao.insertAll(sentences)
    }

    suspend fun getSentenceCount(materialId: Long): Int =
        sentenceDao.countByMaterial(materialId)
}
