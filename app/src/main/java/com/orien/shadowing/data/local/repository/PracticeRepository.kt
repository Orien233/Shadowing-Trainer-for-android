package com.orien.shadowing.data.local.repository

import com.orien.shadowing.data.local.dao.PracticeRecordDao
import com.orien.shadowing.data.local.dao.SentenceDao
import com.orien.shadowing.data.local.dao.SentenceLatestResultDao
import com.orien.shadowing.data.model.PracticeRecordEntity
import com.orien.shadowing.data.model.SentenceEntity
import com.orien.shadowing.data.model.SentenceLatestResultEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PracticeRepository @Inject constructor(
    private val practiceRecordDao: PracticeRecordDao,
    private val sentenceLatestResultDao: SentenceLatestResultDao,
    private val sentenceDao: SentenceDao
) {

    fun getRecordsBySentence(sentenceId: Long): Flow<List<PracticeRecordEntity>> =
        practiceRecordDao.getRecordsBySentence(sentenceId)

    fun getRecordsByMaterial(materialId: Long): Flow<List<PracticeRecordEntity>> =
        practiceRecordDao.getRecordsByMaterial(materialId)

    fun getLatestResultsByMaterial(materialId: Long): Flow<List<SentenceLatestResultEntity>> =
        sentenceLatestResultDao.getLatestResultsByMaterial(materialId)

    suspend fun getLatestResult(sentenceId: Long): SentenceLatestResultEntity? =
        sentenceLatestResultDao.getLatestResult(sentenceId)

    /**
     * Save a practice record and update the latest result snapshot.
     */
    suspend fun savePracticeResult(
        materialId: Long,
        sentenceId: Long,
        recognizedText: String,
        matchScore: Float,
        errorTags: String?
    ): Long {
        val record = PracticeRecordEntity(
            materialId = materialId,
            sentenceId = sentenceId,
            recordingPath = "",
            recognizedText = recognizedText,
            matchScore = matchScore,
            errorTags = errorTags
        )
        val recordId = practiceRecordDao.insert(record)

        // Update latest snapshot
        sentenceLatestResultDao.insertOrUpdate(
            SentenceLatestResultEntity(
                sentenceId = sentenceId,
                latestPracticeRecordId = recordId,
                latestRecognizedText = recognizedText,
                latestScore = matchScore
            )
        )

        return recordId
    }

    /**
     * Get adjacent sentences for navigation.
     */
    suspend fun getNextSentence(currentId: Long, materialId: Long): SentenceEntity? {
        val current = sentenceDao.getSentenceById(currentId) ?: return null
        return sentenceDao.getNextSentence(materialId, current.index)
    }
}
