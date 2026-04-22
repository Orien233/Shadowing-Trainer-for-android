package com.orien.shadowing.presentation.sentencelist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orien.shadowing.data.local.repository.MaterialRepository
import com.orien.shadowing.data.local.repository.PracticeRepository
import com.orien.shadowing.data.model.MaterialEntity
import com.orien.shadowing.data.model.SentenceEntity
import com.orien.shadowing.data.model.SentenceLatestResultEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SentenceListUiState(
    val material: MaterialEntity? = null,
    val sentences: List<SentenceEntity> = emptyList(),
    val latestResults: Map<Long, SentenceLatestResultEntity> = emptyMap(),
    val isLoading: Boolean = true
)

@HiltViewModel
class SentenceListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val materialRepository: MaterialRepository,
    private val practiceRepository: PracticeRepository
) : ViewModel() {

    private val materialId: Long = savedStateHandle.get<Long>("materialId") ?: 0L

    private val _uiState = MutableStateFlow(SentenceListUiState())
    val uiState: StateFlow<SentenceListUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            combine(
                materialFlow(),
                materialRepository.getSentences(materialId),
                practiceRepository.getLatestResultsByMaterial(materialId)
            ) { material, sentences, latestResults ->
                Triple(material, sentences, latestResults)
            }
                .map { (material, sentences, latestResults) ->
                    SentenceListUiState(
                        material = material,
                        sentences = sentences,
                        latestResults = latestResults.associateBy(SentenceLatestResultEntity::sentenceId),
                        isLoading = false
                    )
                }
                .flowOn(Dispatchers.Default)
                .collect { state ->
                    _uiState.value = state
                }
        }
    }

    private fun materialFlow(): Flow<MaterialEntity?> = flow {
        emit(materialRepository.getMaterial(materialId))
    }
}
