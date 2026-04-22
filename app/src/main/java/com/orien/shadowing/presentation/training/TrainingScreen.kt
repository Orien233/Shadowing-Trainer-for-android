package com.orien.shadowing.presentation.training

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.orien.shadowing.presentation.components.rememberAudioPermission
import kotlinx.coroutines.flow.collect
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScreen(
    onBack: () -> Unit,
    viewModel: TrainingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val audioPermission = rememberAudioPermission()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TrainingEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Sentence ${state.sentenceIndex + 1} / ${state.totalSentences}")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        state.sentence?.let { sentence ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Target text",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = buildTargetSentenceAnnotatedString(
                                sentence = sentence.textOriginal,
                                wordFeedback = state.wordFeedback
                            ),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Medium
                        )
                        sentence.textZh?.let { note ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (state.hasVideoPlayback) {
                    VideoPlaybackPanel(
                        videoAspectRatio = state.videoAspectRatio,
                        onBindVideoSurface = viewModel::bindVideoSurface,
                        onUnbindVideoSurface = viewModel::unbindVideoSurface
                    )
                }

                PlaybackControls(
                    isPlaying = state.activePlaybackTarget == TrainingPlaybackTarget.SENTENCE &&
                        state.isPlaying,
                    playbackSpeed = state.playbackSpeed,
                    loopEnabled = state.loopEnabled,
                    onPlay = viewModel::playSentence,
                    onPause = viewModel::pausePlayback,
                    onStop = viewModel::stopPlayback,
                    onSpeedChange = viewModel::setPlaybackSpeed,
                    onToggleLoop = viewModel::toggleLoop
                )

                if (!audioPermission.hasPermission) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.MicOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Microphone permission is required to record.",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Grant permission below to record your practice audio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(onClick = { audioPermission.request() }) {
                                Icon(Icons.Default.Mic, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Allow microphone")
                            }
                        }
                    }
                } else {
                    RecordingControls(
                        isRecording = state.isRecording,
                        isTranscribing = state.isTranscribing,
                        hasReplayableRecording = state.recordingPath != null,
                        recordingDurationMs = state.recordingDurationMs,
                        segmentDurationMs = state.segmentDurationMs,
                        attemptPlaybackState = state.attemptPlaybackState,
                        onStartRecording = viewModel::startRecording,
                        onStopRecording = viewModel::stopRecordingAndEvaluate,
                        onCancelRecording = viewModel::cancelRecording,
                        onToggleAttemptPlayback = viewModel::toggleAttemptPlayback
                    )
                }

                if (state.compareResult != null) {
                    ResultPanel(
                        compareResult = state.compareResult,
                        pronunciationHints = state.pronunciationHints
                    )
                }

                state.latestResult?.let { latest ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Previous attempt",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Score ${(latest.latestScore * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                NavigationControls(
                    onPrev = viewModel::prevSentence,
                    onNext = viewModel::nextSentence
                )
            }
        } ?: Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun VideoPlaybackPanel(
    videoAspectRatio: Float?,
    onBindVideoSurface: (SurfaceHolder) -> Unit,
    onUnbindVideoSurface: (SurfaceHolder) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Video", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val resolvedAspectRatio = videoAspectRatio?.takeIf { it > 0f } ?: (16f / 9f)
                val containerAspectRatio = maxWidth / maxHeight
                val (contentWidth, contentHeight) = if (resolvedAspectRatio > containerAspectRatio) {
                    maxWidth to (maxWidth / resolvedAspectRatio)
                } else {
                    (maxHeight * resolvedAspectRatio) to maxHeight
                }

                AndroidView(
                    modifier = Modifier
                        .width(contentWidth)
                        .height(contentHeight),
                    factory = { context ->
                        SurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onBindVideoSurface(holder)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int
                                ) {
                                    onBindVideoSurface(holder)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onUnbindVideoSurface(holder)
                                }
                            })
                        }
                    },
                    update = { view ->
                        onBindVideoSurface(view.holder)
                    }
                )
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    playbackSpeed: Float,
    loopEnabled: Boolean,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onToggleLoop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Playback", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(onClick = if (isPlaying) onPause else onPlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
                IconButton(onClick = onToggleLoop) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = "Loop",
                        tint = if (loopEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Speed:", style = MaterialTheme.typography.labelSmall)
                listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f).forEach { speed ->
                    FilterChip(
                        selected = playbackSpeed == speed,
                        onClick = { onSpeedChange(speed) },
                        label = { Text("${speed}x") }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingControls(
    isRecording: Boolean,
    isTranscribing: Boolean,
    hasReplayableRecording: Boolean,
    recordingDurationMs: Long?,
    segmentDurationMs: Long?,
    attemptPlaybackState: AttemptPlaybackState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onToggleAttemptPlayback: () -> Unit
) {
    val durationSummary = buildDurationSummary(
        recordingDurationMs = recordingDurationMs,
        segmentDurationMs = segmentDurationMs
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Record practice", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(12.dp))

            when {
                isTranscribing -> {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Transcribing...", style = MaterialTheme.typography.bodySmall)
                }

                isRecording -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FilledTonalButton(onClick = onStopRecording) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop and score")
                        }
                        OutlinedButton(onClick = onCancelRecording) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Recording...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> {
                    FilledTonalButton(onClick = onStartRecording) {
                        Icon(Icons.Default.Mic, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start recording")
                    }
                    if (hasReplayableRecording || durationSummary != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (hasReplayableRecording) {
                        TextButton(onClick = onToggleAttemptPlayback) {
                            Icon(
                                imageVector = if (attemptPlaybackState == AttemptPlaybackState.PLAYING) {
                                    Icons.Default.Stop
                                } else {
                                    Icons.Default.PlayArrow
                                },
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (attemptPlaybackState == AttemptPlaybackState.PLAYING) {
                                    "\u505c\u6b62\u56de\u653e"
                                } else {
                                    "\u56de\u653e\u672c\u6b21\u5f55\u97f3"
                                }
                            )
                        }
                    }
                    durationSummary?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(
    compareResult: com.orien.shadowing.domain.usecase.TextCompareUseCase.CompareResult?,
    pronunciationHints: List<WordPronunciationHint>
) {
    compareResult ?: return
    val criticalHints = pronunciationHints.filter { it.status == TrainingWordStatus.WRONG_OR_MISSING }
    val improvementHints =
        pronunciationHints.filter { it.status == TrainingWordStatus.NEEDS_IMPROVEMENT }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("发音反馈", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(8.dp))
            val scorePercent = (compareResult.matchScore * 100).toInt()
            Text(
                text = "匹配分：$scorePercent%",
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    compareResult.matchScore >= 0.8f -> MaterialTheme.colorScheme.primary
                    compareResult.matchScore >= 0.6f -> Color(0xFFE68619)
                    else -> MaterialTheme.colorScheme.error
                }
            )

            if (pronunciationHints.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (compareResult.matchScore >= 0.95f) {
                        "这次读得比较稳，没有需要单独提示的词。"
                    } else {
                        "这次还没有足够明确的词级提示，建议再录一次试试。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                if (criticalHints.isNotEmpty()) {
                    Text(
                        text = "优先纠正",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    criticalHints.forEachIndexed { index, hint ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        PronunciationHintItem(hint = hint)
                    }
                }

                if (criticalHints.isNotEmpty() && improvementHints.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (improvementHints.isNotEmpty()) {
                    Text(
                        text = "继续打磨",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFE68619)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    improvementHints.forEachIndexed { index, hint ->
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        PronunciationHintItem(hint = hint)
                    }
                }
            }
        }
    }
}

@Composable
private fun PronunciationHintItem(hint: WordPronunciationHint) {
    val titleText = listOfNotNull(
        hint.targetWord,
        hint.targetIpa.takeIf { it.isNotBlank() }
    ).joinToString(" ")

    Text(
        text = titleText,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = colorForWordStatus(hint.status)
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = hint.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun buildTargetSentenceAnnotatedString(
    sentence: String,
    wordFeedback: List<TargetWordFeedback>
) = buildAnnotatedString {
    var wordIndex = 0
    var previousWordStatus: TrainingWordStatus? = null

    SentenceWordTokenizer.tokenize(sentence).forEach { chunk ->
        if (chunk.isWord) {
            val status = wordFeedback.getOrNull(wordIndex)?.status ?: TrainingWordStatus.UNKNOWN
            withStyle(SpanStyle(color = colorForWordStatus(status))) {
                append(chunk.text)
            }
            previousWordStatus = status
            wordIndex++
        } else {
            val separatorColor = if (previousWordStatus != null) {
                colorForWordStatus(previousWordStatus)
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            withStyle(SpanStyle(color = separatorColor)) {
                append(chunk.text)
            }
        }
    }
}

@Composable
private fun colorForWordStatus(status: TrainingWordStatus): Color = when (status) {
    TrainingWordStatus.CORRECT -> Color(0xFF2E9B57)
    TrainingWordStatus.NEEDS_IMPROVEMENT -> Color(0xFFE68619)
    TrainingWordStatus.WRONG_OR_MISSING -> MaterialTheme.colorScheme.error
    TrainingWordStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun buildDurationSummary(
    recordingDurationMs: Long?,
    segmentDurationMs: Long?
): String? {
    val parts = listOfNotNull(
        recordingDurationMs?.let { "\u5f55\u97f3 ${formatDurationMs(it)}" },
        segmentDurationMs?.let { "\u9009\u6bb5 ${formatDurationMs(it)}" }
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00b7 ")
}

private fun formatDurationMs(durationMs: Long): String {
    return String.format(Locale.US, "%.1fs", durationMs.coerceAtLeast(0L) / 1000f)
}

@Composable
private fun NavigationControls(
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Previous")
        }
        OutlinedButton(onClick = onNext) {
            Text("Next")
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}
