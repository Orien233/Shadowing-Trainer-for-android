package com.orien.shadowing.presentation.materiallist

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orien.shadowing.data.local.repository.MaterialRepository
import com.orien.shadowing.data.model.MaterialEntity
import com.orien.shadowing.domain.usecase.DemoMaterialGenerator
import com.orien.shadowing.domain.usecase.ImportMediaUseCase
import com.orien.shadowing.domain.usecase.ImportMaterialUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class MaterialListUiState(
    val materials: List<MaterialEntity> = emptyList(),
    val isLoading: Boolean = false,
    val importMessage: String? = null,
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
    private val importMediaUseCase: ImportMediaUseCase,
    private val importMaterialUseCase: ImportMaterialUseCase,
    private val demoMaterialGenerator: DemoMaterialGenerator
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
    }

    fun showImportSheet() {
        _uiState.update { it.copy(showImportSheet = true) }
    }

    fun hideImportSheet() {
        _uiState.update { it.copy(showImportSheet = false) }
    }

    fun importFromDirectory(dir: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importMessage = "Importing material package...") }
            hideImportSheet()

            when (val result = importMaterialUseCase.importFromDirectory(dir)) {
                is ImportMaterialUseCase.ImportResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, importMessage = null) }
                    _events.emit(
                        MaterialListEvent.ShowSnackbar(
                            "Imported ${result.sentenceCount} sentences."
                        )
                    )
                }

                is ImportMaterialUseCase.ImportResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, importMessage = null) }
                    _events.emit(MaterialListEvent.ShowSnackbar(result.message))
                }
            }
        }
    }

    fun importFromDirectoryUri(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importMessage = "Copying selected folder...") }
            hideImportSheet()

            val tempDir = File(context.cacheDir, "import_${System.currentTimeMillis()}")
            try {
                tempDir.mkdirs()

                val root = DocumentFile.fromTreeUri(context, uri)
                if (root == null || !root.isDirectory) {
                    _uiState.update { it.copy(isLoading = false, importMessage = null) }
                    _events.emit(MaterialListEvent.ShowSnackbar("The selected folder is not valid."))
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    copyDocumentTree(root, tempDir)
                }

                when (val result = importMaterialUseCase.importFromDirectory(tempDir)) {
                    is ImportMaterialUseCase.ImportResult.Success -> {
                        _uiState.update { it.copy(isLoading = false, importMessage = null) }
                        _events.emit(
                            MaterialListEvent.ShowSnackbar(
                                "Imported ${result.sentenceCount} sentences."
                            )
                        )
                    }

                    is ImportMaterialUseCase.ImportResult.Error -> {
                        _uiState.update { it.copy(isLoading = false, importMessage = null) }
                        _events.emit(MaterialListEvent.ShowSnackbar(result.message))
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                _uiState.update { it.copy(isLoading = false, importMessage = null) }
                _events.emit(
                    MaterialListEvent.ShowSnackbar(
                        "Import failed: ${error.message ?: "unknown error"}"
                    )
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    fun importFromMediaUri(uri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importMessage = "Copying selected media...") }
            hideImportSheet()

            var tempFile: File? = null
            try {
                val document = DocumentFile.fromSingleUri(context, uri)
                val displayName = document?.name ?: uri.lastPathSegment ?: "imported_media"
                val mimeType = context.contentResolver.getType(uri)
                val copiedFile = File(
                    context.cacheDir,
                    "media_${System.currentTimeMillis()}_${resolveTempFileName(displayName, mimeType)}"
                )
                tempFile = copiedFile

                val copiedSuccessfully = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        copiedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } != null
                }
                if (!copiedSuccessfully) {
                    _uiState.update { it.copy(isLoading = false, importMessage = null) }
                    _events.emit(MaterialListEvent.ShowSnackbar("The selected media file could not be read."))
                    return@launch
                }

                _uiState.update { it.copy(importMessage = "Analyzing media and generating sentences...") }

                when (val result = importMediaUseCase.importFromMediaFile(copiedFile, displayName, mimeType)) {
                    is ImportMaterialUseCase.ImportResult.Success -> {
                        _uiState.update { it.copy(isLoading = false, importMessage = null) }
                        _events.emit(
                            MaterialListEvent.ShowSnackbar(
                                "Imported ${result.sentenceCount} sentences from media."
                            )
                        )
                    }

                    is ImportMaterialUseCase.ImportResult.Error -> {
                        _uiState.update { it.copy(isLoading = false, importMessage = null) }
                        _events.emit(MaterialListEvent.ShowSnackbar(result.message))
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                _uiState.update { it.copy(isLoading = false, importMessage = null) }
                _events.emit(
                    MaterialListEvent.ShowSnackbar(
                        "Import failed: ${error.message ?: "unknown error"}"
                    )
                )
            } finally {
                tempFile?.delete()
            }
        }
    }

    fun importDemo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importMessage = "Generating demo materials...") }
            hideImportSheet()

            when (val result = demoMaterialGenerator.generateAndImport()) {
                is ImportMaterialUseCase.ImportResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, importMessage = null) }
                    _events.emit(
                        MaterialListEvent.ShowSnackbar(
                            "Demo materials imported: ${result.sentenceCount} sentences."
                        )
                    )
                }

                is ImportMaterialUseCase.ImportResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, importMessage = null) }
                    _events.emit(MaterialListEvent.ShowSnackbar(result.message))
                }
            }
        }
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

    private fun copyDocumentTree(source: DocumentFile, destinationDir: File) {
        source.listFiles().forEach { child ->
            val childName = child.name ?: return@forEach
            if (child.isDirectory) {
                copyDocumentTree(child, File(destinationDir, childName).apply { mkdirs() })
            } else if (child.isFile) {
                val destinationFile = File(destinationDir, childName)
                destinationFile.parentFile?.mkdirs()
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    destinationFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun resolveTempFileName(displayName: String, mimeType: String?): String {
        val extension = displayName.substringAfterLast('.', "").ifBlank {
            mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }.orEmpty()
        }
        val baseName = displayName
            .substringBeforeLast('.', displayName)
            .ifBlank { "imported_media" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "imported_media" }

        return if (extension.isBlank()) {
            baseName
        } else {
            "$baseName.${extension.lowercase()}"
        }
    }
}
