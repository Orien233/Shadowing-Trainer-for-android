package com.orien.shadowing.presentation.training

import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.util.Log
import android.view.SurfaceHolder
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.orien.shadowing.data.local.AudioPlayer
import com.orien.shadowing.data.local.AudioRecorder
import com.orien.shadowing.data.local.MoonshineAsr
import com.orien.shadowing.data.local.MoonshineAsrFactory
import com.orien.shadowing.data.local.dao.SentenceDao
import com.orien.shadowing.data.local.moonshine.AudioUtils
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
    val videoAspectRatio: Float? = null,
    val isPlaying: Boolean = false,
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val recognizedText: String? = null,
    val compareResult: TextCompareUseCase.CompareResult? = null,
    val wordFeedback: List<TargetWordFeedback> = emptyList(),
    val pronunciationHints: List<WordPronunciationHint> = emptyList(),
    val latestResult: SentenceLatestResultEntity? = null,
    val recordingPath: String? = null,
    val recordingDurationMs: Long? = null,
    val segmentDurationMs: Long? = null,
    val attemptPlaybackState: AttemptPlaybackState = AttemptPlaybackState.IDLE,
    val activePlaybackTarget: TrainingPlaybackTarget? = null,
    val playbackSpeed: Float = 1.0f,
    val loopEnabled: Boolean = false,
    val errorMessage: String? = null
)

sealed interface TrainingEvent {
    data class ShowMessage(val message: String) : TrainingEvent
}

enum class TrainingPlaybackTarget {
    SENTENCE,
    ATTEMPT
}

enum class AttemptPlaybackState {
    IDLE,
    PLAYING,
    STOPPED,
    COMPLETED
}

@HiltViewModel
class TrainingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sentenceDao: SentenceDao,
    private val materialRepository: MaterialRepository,
    private val practiceRepository: PracticeRepository,
    private val audioPlayer: AudioPlayer,
    private val audioRecorder: AudioRecorder,
    moonshineAsrFactory: MoonshineAsrFactory,
    private val trainingFeedbackComposer: TrainingFeedbackComposer
) : ViewModel() {
    private data class PlaybackSource(
        val filePath: String,
        val startTimeMs: Long?,
        val endTimeMs: Long?,
        val isVideo: Boolean,
        val fallbackAudioPath: String? = null
    )

    private val materialId: Long = savedStateHandle.get<Long>("materialId") ?: 0L
    private val initialSentenceId: Long = savedStateHandle.get<Long>("sentenceId") ?: 0L
    private val moonshineAsr: MoonshineAsr = moonshineAsrFactory.create("training:$materialId")

    private val _uiState = MutableStateFlow(TrainingUiState())
    val uiState: StateFlow<TrainingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<TrainingEvent>()
    val events = _events.asSharedFlow()
    private val hasVideoTrackCache = mutableMapOf<String, Boolean>()
    private var pendingPlaybackTarget: TrainingPlaybackTarget? = null
    private var manualAttemptPlaybackStop = false

    companion object {
        private const val TAG = "TrainingViewModel"
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4v", "3gp", "qt")
    }

    init {
        loadSentence(initialSentenceId)

        viewModelScope.launch {
            audioPlayer.isPlaying.collect { playing ->
                _uiState.update { current ->
                    if (playing) {
                        val playbackTarget = pendingPlaybackTarget ?: current.activePlaybackTarget
                        pendingPlaybackTarget = null
                        current.copy(
                            isPlaying = true,
                            activePlaybackTarget = playbackTarget,
                            attemptPlaybackState = if (playbackTarget == TrainingPlaybackTarget.ATTEMPT) {
                                AttemptPlaybackState.PLAYING
                            } else {
                                current.attemptPlaybackState
                            }
                        )
                    } else {
                        val attemptPlaybackState = when {
                            current.recordingPath == null -> AttemptPlaybackState.IDLE
                            current.activePlaybackTarget == TrainingPlaybackTarget.ATTEMPT &&
                                current.isPlaying -> {
                                if (manualAttemptPlaybackStop) {
                                    AttemptPlaybackState.STOPPED
                                } else {
                                    AttemptPlaybackState.COMPLETED
                                }
                            }

                            else -> current.attemptPlaybackState
                        }
                        manualAttemptPlaybackStop = false
                        current.copy(
                            isPlaying = false,
                            activePlaybackTarget = null,
                            attemptPlaybackState = attemptPlaybackState
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            audioRecorder.isRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }

        viewModelScope.launch {
            audioPlayer.playbackMessages.collect { message ->
                _events.emit(TrainingEvent.ShowMessage(message))
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

        beginPlayback(TrainingPlaybackTarget.SENTENCE)
        audioPlayer.playSegment(
            filePath = playbackSource.filePath,
            startTimeMs = playbackSource.startTimeMs,
            endTimeMs = playbackSource.endTimeMs,
            loop = state.loopEnabled,
            fallbackAudioPath = playbackSource.fallbackAudioPath
        )
    }

    fun bindVideoSurface(holder: SurfaceHolder) {
        audioPlayer.bindVideoSurface(holder)
    }

    fun unbindVideoSurface(holder: SurfaceHolder) {
        audioPlayer.unbindVideoSurface(holder)
    }

    fun pausePlayback() {
        audioPlayer.pause()
    }

    fun stopPlayback() {
        markManualAttemptStopIfNeeded()
        pendingPlaybackTarget = null
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
        resetAttemptState(deleteRecording = true)
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
            resetAttemptState(deleteRecording = true)
            return
        }

        val recordingDurationMs = AudioUtils.resolveMediaDurationMs(recordingPath)
        _uiState.update {
            it.copy(
                isTranscribing = true,
                recordingPath = recordingPath,
                recordingDurationMs = recordingDurationMs,
                attemptPlaybackState = AttemptPlaybackState.IDLE
            )
        }
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
                val feedbackResult = trainingFeedbackComposer.evaluate(
                    targetText = sentence.textOriginal,
                    recognizedText = asrResult.text
                )
                val compareResult = feedbackResult.compareResult
                val errorTagsJson = buildErrorTags(compareResult)

                val recordId = practiceRepository.savePracticeResult(
                    materialId = materialId,
                    sentenceId = sentence.id,
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
                        wordFeedback = feedbackResult.wordFeedback,
                        pronunciationHints = feedbackResult.pronunciationHints,
                        latestResult = latestResult,
                        recordingPath = recordingPath,
                        recordingDurationMs = recordingDurationMs,
                        attemptPlaybackState = AttemptPlaybackState.IDLE,
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
                        recordingPath = recordingPath,
                        recordingDurationMs = recordingDurationMs,
                        attemptPlaybackState = AttemptPlaybackState.IDLE,
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
        resetAttemptState(deleteRecording = true)
    }

    fun toggleAttemptPlayback() {
        val state = _uiState.value
        if (state.isRecording || state.isTranscribing) {
            return
        }

        val recordingPath = state.recordingPath
            ?.takeIf { path -> path.isNotBlank() && File(path).exists() }

        if (recordingPath == null) {
            resetAttemptState(deleteRecording = true)
            viewModelScope.launch {
                _events.emit(TrainingEvent.ShowMessage("Current recording is no longer available."))
            }
            return
        }

        if (
            state.activePlaybackTarget == TrainingPlaybackTarget.ATTEMPT &&
            state.isPlaying
        ) {
            manualAttemptPlaybackStop = true
            pendingPlaybackTarget = null
            audioPlayer.stop()
            return
        }

        beginPlayback(TrainingPlaybackTarget.ATTEMPT)
        audioPlayer.playClip(recordingPath)
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
        pendingPlaybackTarget = null
        manualAttemptPlaybackStop = false
        audioPlayer.stop()
        audioRecorder.cancelRecording()
        deleteRecordingFile(_uiState.value.recordingPath)
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
            val videoAspectRatio = playbackSource
                ?.takeIf { it.isVideo }
                ?.filePath
                ?.let(::resolveVideoAspectRatio)

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
                    videoAspectRatio = videoAspectRatio,
                    recognizedText = null,
                    compareResult = null,
                    wordFeedback = emptyList(),
                    pronunciationHints = emptyList(),
                    latestResult = latestResult,
                    recordingPath = null,
                    recordingDurationMs = null,
                    segmentDurationMs = resolveSegmentDurationMs(sentence),
                    attemptPlaybackState = AttemptPlaybackState.IDLE,
                    activePlaybackTarget = null,
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
        val fallbackAudioPath = material?.fallbackAudioPath?.takeIf { path ->
            path.isNotBlank() && File(path).exists()
        }
        val startTimeMs = sentence.startTimeMs
        val endTimeMs = sentence.endTimeMs
        val hasTimedSegment = startTimeMs != null && endTimeMs != null && endTimeMs > startTimeMs
        val sourceHasVideo = sourcePath?.let { path ->
            isVideoSource(path, material?.type)
        } == true

        // If this material has a video source, prefer video playback over audio-only clips.
        if (sourcePath != null && sourceHasVideo) {
            if (hasTimedSegment) {
                return PlaybackSource(
                    filePath = sourcePath,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    isVideo = true,
                    fallbackAudioPath = fallbackAudioPath
                )
            }

            val videoClipPath = clipPath?.takeIf { isVideoSource(it, material?.type) }
            if (videoClipPath != null) {
                return PlaybackSource(
                    filePath = videoClipPath,
                    startTimeMs = null,
                    endTimeMs = null,
                    isVideo = true
                )
            }

            return PlaybackSource(
                filePath = sourcePath,
                startTimeMs = null,
                endTimeMs = null,
                isVideo = true,
                fallbackAudioPath = fallbackAudioPath
            )
        }

        if (clipPath != null) {
            return PlaybackSource(
                filePath = clipPath,
                startTimeMs = null,
                endTimeMs = null,
                isVideo = isVideoSource(clipPath, material?.type)
            )
        }

        sourcePath ?: return null

        return if (hasTimedSegment) {
            PlaybackSource(
                filePath = sourcePath,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                isVideo = sourceHasVideo,
                fallbackAudioPath = fallbackAudioPath
            )
        } else {
            PlaybackSource(
                filePath = sourcePath,
                startTimeMs = null,
                endTimeMs = null,
                isVideo = sourceHasVideo,
                fallbackAudioPath = fallbackAudioPath
            )
        }
    }

    private fun isVideoSource(path: String, materialTypeHint: String?): Boolean {
        val cached = hasVideoTrackCache[path]
        if (cached != null) {
            return cached
        }

        val detectedByTrack = detectHasVideoTrack(path)
        if (detectedByTrack != null) {
            hasVideoTrackCache[path] = detectedByTrack
            return detectedByTrack
        }

        val sanitizedPath = path.substringBefore('?').substringBefore('#')
        val extension = sanitizedPath.substringAfterLast('.', "").lowercase()
        return when {
            materialTypeHint.equals("video", ignoreCase = true) -> true
            materialTypeHint.equals("audio", ignoreCase = true) -> false
            extension in VIDEO_EXTENSIONS -> true
            else -> false
        }
    }

    private fun detectHasVideoTrack(path: String): Boolean? {
        if (path.startsWith("content://")) {
            // Current training flow persists local file paths. Keep a safe fallback for content Uris.
            return null
        }

        val localPath = if (path.startsWith("file://")) {
            android.net.Uri.parse(path).path
        } else {
            path
        } ?: return null

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(localPath)
            var hasAudioTrack = false
            var hasAnyVideoTrack = false
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                when {
                    mime?.startsWith("video/") == true -> hasAnyVideoTrack = true
                    mime?.startsWith("audio/") == true -> hasAudioTrack = true
                }
                if (hasAnyVideoTrack) {
                    return true
                }
            }
            when {
                hasAnyVideoTrack -> return true
                hasAudioTrack -> return false
            }
        } catch (_: Throwable) {
            // Fall through to metadata-retriever based detection.
        } finally {
            runCatching { extractor.release() }
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(localPath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            when {
                hasVideo.equals("yes", ignoreCase = true) -> true
                hasVideo == "1" -> true
                hasVideo.equals("no", ignoreCase = true) -> false
                hasVideo == "0" -> false
                else -> {
                    val width =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull() ?: 0
                    val height =
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull() ?: 0
                    if (width > 0 && height > 0) true else null
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveVideoAspectRatio(path: String): Float? {
        if (path.startsWith("content://")) {
            return null
        }

        val localPath = if (path.startsWith("file://")) {
            android.net.Uri.parse(path).path
        } else {
            path
        } ?: return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(localPath)
            val rawWidth =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toFloatOrNull()
                    ?: 0f
            val rawHeight =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toFloatOrNull()
                    ?: 0f
            if (rawWidth <= 0f || rawHeight <= 0f) {
                return null
            }

            val rotation =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull()
                    ?: 0
            val (displayWidth, displayHeight) = if (rotation == 90 || rotation == 270) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }
            (displayWidth / displayHeight).takeIf { it.isFinite() && it > 0f }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
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
        pendingPlaybackTarget = null
        manualAttemptPlaybackStop = false
        audioPlayer.stop()
        audioRecorder.cancelRecording()
        deleteRecordingFile(_uiState.value.recordingPath)
    }

    private fun beginPlayback(target: TrainingPlaybackTarget) {
        if (
            target != TrainingPlaybackTarget.ATTEMPT &&
            _uiState.value.activePlaybackTarget == TrainingPlaybackTarget.ATTEMPT &&
            _uiState.value.isPlaying
        ) {
            manualAttemptPlaybackStop = true
        }
        pendingPlaybackTarget = target
    }

    private fun markManualAttemptStopIfNeeded() {
        if (_uiState.value.activePlaybackTarget == TrainingPlaybackTarget.ATTEMPT) {
            manualAttemptPlaybackStop = true
        }
    }

    private fun resetAttemptState(deleteRecording: Boolean) {
        val recordingPath = _uiState.value.recordingPath
        pendingPlaybackTarget = null
        manualAttemptPlaybackStop = false
        if (deleteRecording) {
            deleteRecordingFile(recordingPath)
        }
        _uiState.update {
            it.copy(
                recognizedText = null,
                compareResult = null,
                wordFeedback = emptyList(),
                pronunciationHints = emptyList(),
                recordingPath = null,
                recordingDurationMs = null,
                attemptPlaybackState = AttemptPlaybackState.IDLE,
                activePlaybackTarget = null,
                errorMessage = null
            )
        }
    }

    private fun resolveSegmentDurationMs(sentence: SentenceEntity): Long? {
        val startTimeMs = sentence.startTimeMs
        val endTimeMs = sentence.endTimeMs
        return if (startTimeMs != null && endTimeMs != null && endTimeMs > startTimeMs) {
            endTimeMs - startTimeMs
        } else {
            null
        }
    }

    private fun deleteRecordingFile(path: String?) {
        if (path.isNullOrBlank()) {
            return
        }
        runCatching {
            File(path).takeIf(File::exists)?.delete()
        }.onFailure { error ->
            Log.w(TAG, "Failed to delete cached recording: $path", error)
        }
    }
}
