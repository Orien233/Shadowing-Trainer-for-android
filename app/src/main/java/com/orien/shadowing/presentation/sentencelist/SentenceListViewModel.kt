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
import kotlinx.coroutines.flow.*
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
            val material = materialRepository.getMaterial(materialId)
            _uiState.update { it.copy(material = material) }
        }

        viewModelScope.launch {
            materialRepository.getSentences(materialId).collect { sentences ->
                _uiState.update { it.copy(sentences = sentences, isLoading = false) }
            }
        }

        viewModelScope.launch {
            practiceRepository.getLatestResultsByMaterial(materialId).collect { results ->
                _uiState.update {
                    it.copy(latestResults = results.associateBy { r -> r.sentenceId })
                }
            }
        }
    }
}
