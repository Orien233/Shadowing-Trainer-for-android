package com.orien.shadowing.presentation.materiallist

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orien.shadowing.data.local.repository.MaterialRepository
import com.orien.shadowing.data.model.MaterialEntity
import com.orien.shadowing.domain.usecase.ImportTaskSnapshot
import com.orien.shadowing.domain.usecase.MaterialImportQueue
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class MaterialListUiState(
    val materials: List<MaterialEntity> = emptyList(),
    val importTasks: List<ImportTaskSnapshot> = emptyList(),
    val showImportSheet: Boolean = false
)

sealed interface MaterialListEvent {
    data class ShowSnackbar(val message: String) : MaterialListEvent
    data class NavigateToSentences(val materialId: Long) : MaterialListEvent
}

@HiltViewModel
class MaterialListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val materialRepository: MaterialRepository,
    private val materialImportQueue: MaterialImportQueue
) : ViewModel() {
    private val _uiState = MutableStateFlow(MaterialListUiState())
    val uiState: StateFlow<MaterialListUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MaterialListEvent>()
    val events = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            materialRepository.getAllMaterials().collect { materials ->
                _uiState.update { state -> state.copy(materials = materials) }
            }
        }

        viewModelScope.launch {
            materialImportQueue.tasks.collect { importTasks ->
                _uiState.update { state -> state.copy(importTasks = importTasks) }
            }
        }
    }

    fun showImportSheet() {
        _uiState.update { it.copy(showImportSheet = true) }
    }

    fun hideImportSheet() {
        _uiState.update { it.copy(showImportSheet = false) }
    }

    fun importFromDirectory(directory: File) {
        materialImportQueue.enqueueLocalDirectory(directory)
        hideImportSheet()
        emitQueuedSnackbar()
    }

    fun importFromDirectoryUri(uri: Uri) {
        materialImportQueue.enqueueDirectoryUri(uri)
        hideImportSheet()
        emitQueuedSnackbar()
    }

    fun importFromMediaUri(uri: Uri) {
        materialImportQueue.enqueueMediaUri(uri)
        hideImportSheet()
        emitQueuedSnackbar()
    }

    fun importDemo() {
        materialImportQueue.enqueueDemoImport()
        hideImportSheet()
        emitQueuedSnackbar()
    }

    fun onMaterialClick(materialId: Long) {
        viewModelScope.launch {
            _events.emit(MaterialListEvent.NavigateToSentences(materialId))
        }
    }

    fun deleteMaterial(materialId: Long) {
        viewModelScope.launch {
            val storageRoot = File(context.filesDir, "shadowing_data")
            materialRepository.deleteMaterial(materialId, storageRoot)
            _events.emit(MaterialListEvent.ShowSnackbar("Material deleted."))
        }
    }

    fun renameMaterial(materialId: Long, title: String) {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) {
            viewModelScope.launch {
                _events.emit(MaterialListEvent.ShowSnackbar("Material name cannot be empty."))
            }
            return
        }

        viewModelScope.launch {
            materialRepository.renameMaterial(materialId, normalizedTitle)
            _events.emit(MaterialListEvent.ShowSnackbar("Material renamed."))
        }
    }

    private fun emitQueuedSnackbar() {
        viewModelScope.launch {
            _events.emit(MaterialListEvent.ShowSnackbar("Added to import queue."))
        }
    }
}
