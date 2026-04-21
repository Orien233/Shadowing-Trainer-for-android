package com.orien.shadowing.presentation.training

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.orien.shadowing.data.local.AudioPlayer
import com.orien.shadowing.data.local.AudioRecorder
import com.orien.shadowing.data.local.MoonshineAsr
import com.orien.shadowing.data.local.dao.SentenceDao
import com.orien.shadowing.data.local.repository.MaterialRepository
import com.orien.shadowing.data.local.repository.PracticeRepository
import com.orien.shadowing.data.model.MaterialEntity
import com.orien.shadowing.data.model.SentenceEntity
import com.orien.shadowing.data.model.SentenceLatestResultEntity
import com.orien.shadowing.domain.usecase.TextCompareUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

data class TrainingUiState(
    val material: MaterialEntity? = null,
    val sentence: SentenceEntity? = null,
    val sentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val playbackSourcePath: String? = null,
    val playbackStartTimeMs: Long? = null,
    val playbackEndTimeMs: Long? = null,
    val hasVideoPlayback: Boolean = false,
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val recognizedText: String? = null,
    val compareResult: TextCompareUseCase.CompareResult? = null,
    val latestResult: SentenceLatestResultEntity? = null,
    val recordingPath: String? = null,
    val playbackSpeed: Float = 1.0f,
    val loopEnabled: Boolean = false,
    val errorMessage: String? = null
)

sealed interface TrainingEvent {
    data class ShowMessage(val message: String) : TrainingEvent
}

@HiltViewModel
class TrainingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sentenceDao: SentenceDao,
    private val materialRepository: MaterialRepository,
    private val practiceRepository: PracticeRepository,
    private val audioPlayer: AudioPlayer,
    private val audioRecorder: AudioRecorder,
    private val moonshineAsr: MoonshineAsr,
    private val textCompare: TextCompareUseCase
) : ViewModel() {
    private data class PlaybackSource(
        val filePath: String,
        val startTimeMs: Long?,
        val endTimeMs: Long?,
        val isVideo: Boolean
    )

    private val materialId: Long = savedStateHandle.get<Long>("materialId") ?: 0L
    private val initialSentenceId: Long = savedStateHandle.get<Long>("sentenceId") ?: 0L

    private val _uiState = MutableStateFlow(TrainingUiState())
    val uiState: StateFlow<TrainingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TrainingEvent>()
    val events = _events.asSharedFlow()

    companion object {
        private const val TAG = "TrainingViewModel"
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4v", "3gp")
    }

    init {
        loadSentence(initialSentenceId)

        viewModelScope.launch {
            audioPlayer.isPlaying.collect { playing ->
                _uiState.update { it.copy(isPlaying = playing) }
            }
        }

        viewModelScope.launch {
            audioRecorder.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }
    }

    fun playSentence() {
        val state = _uiState.value
        val playbackSource = resolvePlaybackSource(state)
        if (playbackSource == null) {
            viewModelScope.launch {
                _events.emit(TrainingEvent.ShowMessage("No playable media was found for this sentence."))
            }
            return
        }

        audioPlayer.playSegment(
            filePath = playbackSource.filePath,
            startTimeMs = playbackSource.startTimeMs,
            endTimeMs = playbackSource.endTimeMs,
            loop = state.loopEnabled
        )
    }

    fun getVideoPlayer(): ExoPlayer = audioPlayer.getPlayer()

    fun pausePlayback() {
        audioPlayer.pause()
    }

    fun stopPlayback() {
        audioPlayer.stop()
    }

    fun setPlaybackSpeed(speed: Float) {
        audioPlayer.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun toggleLoop() {
        _uiState.update { it.copy(loopEnabled = !it.loopEnabled) }
    }

    fun startRecording() {
        if (_uiState.value.isRecording || _uiState.value.isTranscribing) {
            return
        }

        val sentence = _uiState.value.sentence ?: return
        audioPlayer.stop()
        runCatching {
            audioRecorder.startRecording(materialId, sentence.id)
        }.onSuccess { recordingPath ->
            _uiState.update { it.copy(recordingPath = recordingPath, errorMessage = null) }
        }.onFailure { error ->
            Log.e(TAG, "Failed to start recording.", error)
            viewModelScope.launch {
                _events.emit(
                    TrainingEvent.ShowMessage(
                        error.message ?: "Unable to start recording on this device."
                    )
                )
            }
        }
    }

    fun stopRecordingAndEvaluate() {
        if (_uiState.value.isTranscribing) {
            return
        }

        val recordingPath = runCatching {
            audioRecorder.stopRecording()
        }.getOrElse { error ->
            Log.e(TAG, "Failed to stop recording.", error)
            viewModelScope.launch {
                _events.emit(
                    TrainingEvent.ShowMessage(
                        error.message ?: "Recording could not be finalized on this device."
                    )
                )
            }
            return
        }

        if (recordingPath == null) {
            viewModelScope.launch {
                _events.emit(TrainingEvent.ShowMessage("Recording failed."))
            }
            return
        }

        _uiState.update { it.copy(isTranscribing = true) }
        viewModelScope.launch {
            val sentence = _uiState.value.sentence
            if (sentence == null) {
                _uiState.update { it.copy(isTranscribing = false) }
                return@launch
            }
            try {
                val asrResult = moonshineAsr.transcribe(recordingPath)
                if (asrResult.errorMessage != null) {
                    _uiState.update {
                        it.copy(
                            isTranscribing = false,
                            errorMessage = asrResult.errorMessage
                        )
                    }
                    _events.emit(TrainingEvent.ShowMessage(asrResult.errorMessage))
                    return@launch
                }
                val compareResult = textCompare.compare(sentence.textOriginal, asrResult.text)
                val errorTagsJson = buildErrorTags(compareResult)

                val recordId = practiceRepository.savePracticeResult(
                    materialId = materialId,
                    sentenceId = sentence.id,
                    recordingPath = recordingPath,
                    recognizedText = asrResult.text,
                    matchScore = compareResult.matchScore,
                    errorTags = errorTagsJson
                )

                val latestResult = SentenceLatestResultEntity(
                    sentenceId = sentence.id,
                    latestPracticeRecordId = recordId,
                    latestRecognizedText = asrResult.text,
                    latestScore = compareResult.matchScore
                )

                _uiState.update {
                    it.copy(
                        isTranscribing = false,
                        recognizedText = asrResult.text,
                        compareResult = compareResult,
                        latestResult = latestResult,
                        recordingPath = recordingPath,
                        errorMessage = null
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                Log.e(TAG, "Failed to evaluate recording.", error)
                _uiState.update {
                    it.copy(
                        isTranscribing = false,
                        errorMessage = error.message ?: "Scoring failed on this device."
                    )
                }
                _events.emit(
                    TrainingEvent.ShowMessage(
                        error.message ?: "Scoring failed on this device."
                    )
                )
            }
        }
    }

    fun cancelRecording() {
        audioRecorder.cancelRecording()
    }

    fun nextSentence() {
        val currentSentence = _uiState.value.sentence ?: return
        viewModelScope.launch {
            val nextSentence = sentenceDao.getNextSentence(materialId, currentSentence.index)
            if (nextSentence != null) {
                resetForNavigation()
                loadSentence(nextSentence.id)
            } else {
                _events.emit(TrainingEvent.ShowMessage("This is already the last sentence."))
            }
        }
    }

    fun prevSentence() {
        val currentSentence = _uiState.value.sentence ?: return
        viewModelScope.launch {
            val previousSentence = sentenceDao.getPrevSentence(materialId, currentSentence.index)
            if (previousSentence != null) {
                resetForNavigation()
                loadSentence(previousSentence.id)
            } else {
                _events.emit(TrainingEvent.ShowMessage("This is already the first sentence."))
            }
        }
    }

    fun jumpToSentence(sentenceId: Long) {
        resetForNavigation()
        loadSentence(sentenceId)
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        audioRecorder.cancelRecording()
        moonshineAsr.release()
    }

    private fun loadSentence(sentenceId: Long) {
        viewModelScope.launch {
            val sentence = sentenceDao.getSentenceById(sentenceId)
            if (sentence == null) {
                _uiState.update { it.copy(errorMessage = "Sentence not found.") }
                return@launch
            }

            val material = materialRepository.getMaterial(materialId)
            val allSentences = materialRepository.getSentences(materialId).first()
            val sentenceIndex = allSentences.indexOfFirst { it.id == sentenceId }.coerceAtLeast(0)
            val latestResult = practiceRepository.getLatestResult(sentenceId)
            val playbackSource = resolvePlaybackSource(sentence, material)

            _uiState.update {
                it.copy(
                    material = material,
                    sentence = sentence,
                    sentenceIndex = sentenceIndex,
                    totalSentences = allSentences.size,
                    playbackSourcePath = playbackSource?.filePath,
                    playbackStartTimeMs = playbackSource?.startTimeMs,
                    playbackEndTimeMs = playbackSource?.endTimeMs,
                    hasVideoPlayback = playbackSource?.isVideo == true,
                    recognizedText = null,
                    compareResult = null,
                    latestResult = latestResult,
                    recordingPath = null,
                    errorMessage = null
                )
            }
        }
    }

    private fun resolvePlaybackSource(state: TrainingUiState): PlaybackSource? =
        resolvePlaybackSource(state.sentence, state.material)

    private fun resolvePlaybackSource(
        sentence: SentenceEntity?,
        material: MaterialEntity?
    ): PlaybackSource? {
        if (sentence == null) {
            return null
        }

        val clipPath = sentence.clipPath?.takeIf { path ->
            path.isNotBlank() && File(path).exists()
        }
        val sourcePath = material?.sourcePath?.takeIf { path ->
            path.isNotBlank() && File(path).exists()
        }
        val startTimeMs = sentence.startTimeMs
        val endTimeMs = sentence.endTimeMs
        val hasTimedSegment = startTimeMs != null && endTimeMs != null && endTimeMs > startTimeMs

        // If this material has a video source, prefer video playback over audio-only clips.
        if (sourcePath != null && isVideoFile(sourcePath)) {
            val videoClipPath = clipPath?.takeIf(::isVideoFile)
            if (videoClipPath != null) {
                return PlaybackSource(
                    filePath = videoClipPath,
                    startTimeMs = null,
                    endTimeMs = null,
                    isVideo = true
                )
            }

            return if (hasTimedSegment) {
                PlaybackSource(
                    filePath = sourcePath,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    isVideo = true
                )
            } else {
                PlaybackSource(
                    filePath = sourcePath,
                    startTimeMs = null,
                    endTimeMs = null,
                    isVideo = true
                )
            }
        }

        if (clipPath != null) {
            return PlaybackSource(
                filePath = clipPath,
                startTimeMs = null,
                endTimeMs = null,
                isVideo = isVideoFile(clipPath)
            )
        }

        sourcePath ?: return null

        return if (hasTimedSegment) {
            PlaybackSource(
                filePath = sourcePath,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                isVideo = isVideoFile(sourcePath)
            )
        } else {
            PlaybackSource(
                filePath = sourcePath,
                startTimeMs = null,
                endTimeMs = null,
                isVideo = isVideoFile(sourcePath)
            )
        }
    }

    private fun isVideoFile(path: String): Boolean {
        val sanitizedPath = path.substringBefore('?').substringBefore('#')
        val extension = sanitizedPath.substringAfterLast('.', "").lowercase()
        return extension in VIDEO_EXTENSIONS
    }

    private fun buildErrorTags(compareResult: TextCompareUseCase.CompareResult): String? {
        val tags = buildList {
            addAll(compareResult.missedWords.map { "missed:$it" })
            addAll(compareResult.extraWords.map { "extra:$it" })
            addAll(compareResult.wrongWords.map { "wrong:$it" })
        }
        return if (tags.isEmpty()) {
            null
        } else {
            Json.encodeToString(tags)
        }
    }

    private fun resetForNavigation() {
        audioPlayer.stop()
        audioRecorder.cancelRecording()
    }
}
