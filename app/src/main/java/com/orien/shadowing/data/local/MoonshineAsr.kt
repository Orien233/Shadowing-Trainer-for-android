package com.orien.shadowing.data.local

import android.content.Context
import com.orien.shadowing.data.local.moonshine.AudioUtils
import com.orien.shadowing.data.local.moonshine.MoonshineTranscriber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device ASR wrapper backed by Moonshine.
 */
@Singleton
class MoonshineAsr @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class ModelSpec(
        val directory: File,
        val arch: Int
    )

    private data class BundledModel(
        val assetDirName: String,
        val arch: Int
    )

    data class AsrResult(
        val text: String,
        val lines: List<AsrLine> = emptyList(),
        val confidence: Float = 0f,
        val durationMs: Long = 0L,
        val disputedLines: List<AsrLine> = emptyList(),
        val errorMessage: String? = null
    )

    data class AsrLine(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long
    )

    private var transcriber: MoonshineTranscriber? = null
    private var isInitialized = false
    private val transcriberLock = Any()
    @Volatile
    private var lastInitErrorMessage: String? = null

    companion object {
        private const val MIN_CHUNK_DURATION_MS = 20_000L
        private const val MAX_CHUNK_DURATION_MS = 180_000L
        private const val CHUNK_OVERLAP_MS = 2_000L
        private const val CHUNK_HEAP_FRACTION = 0.08
        private const val BYTES_PER_SAMPLE = 4L
        private const val BOUNDARY_MATCH_WINDOW_MS = 3_000L
        private const val SMALL_PAUSE_PROTECTION_MS = 650L
        private const val HARD_BOUNDARY_MIN_GAP_MS = 250L
        private const val MAX_MERGED_SENTENCE_SPAN_MS = 25_000L
        private const val SHORT_FRAGMENT_CHAR_THRESHOLD = 12
        private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '。', '！', '？', ';', '；', '…')
        private val CONTINUATION_WORDS = setOf(
            "and", "but", "or", "so", "because", "if", "when", "while",
            "then", "that", "to", "of", "for", "with", "in", "on", "at",
            "from", "by", "as", "also"
        )
        private val CONTINUATION_PREFIXES = setOf(
            "然后", "但是", "因为", "所以", "并且", "而且", "如果", "当", "同时", "此外"
        )
    }

    suspend fun initialize(
        modelPath: String? = null,
        arch: Int = MoonshineTranscriber.ARCH_MEDIUM_STREAMING
    ): Boolean = withContext(Dispatchers.IO) {
        synchronized(transcriberLock) {
            initializeLocked(modelPath, arch)
        }
    }

    suspend fun transcribe(audioPath: String): AsrResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        synchronized(transcriberLock) {
            if (!isInitialized) {
                val ready = initializeLocked(
                    modelPath = null,
                    arch = MoonshineTranscriber.ARCH_MEDIUM_STREAMING
                )
                if (!ready) {
                    return@withContext AsrResult(
                        text = "",
                        durationMs = System.currentTimeMillis() - startTime,
                        errorMessage = "Moonshine model is not available."
                    )
                }
            }

            val loadedTranscriber = transcriber ?: return@withContext AsrResult(
                text = "",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = "Moonshine transcriber is unavailable."
            )

            try {
                val file = File(audioPath)
                val result = if (shouldUseChunkedTranscription(file)) {
                    transcribeInChunks(file, loadedTranscriber)
                } else {
                    val samples = AudioUtils.readAudioAsFloat(audioPath)
                    val transcript = loadedTranscriber.transcribe(samples, AudioUtils.TARGET_SAMPLE_RATE)
                    val normalizedLines = applySmallPauseProtection(
                        normalizeLines(transcript.lines.asList())
                    )
                    AsrResult(
                        text = normalizedLines.joinToString(" ") { it.text },
                        lines = normalizedLines,
                        confidence = 1f
                    )
                }
                result.copy(durationMs = System.currentTimeMillis() - startTime)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                android.util.Log.e("MoonshineAsr", "Failed to transcribe $audioPath", error)
                AsrResult(
                    text = "",
                    durationMs = System.currentTimeMillis() - startTime,
                    errorMessage = error.message ?: "Failed to process audio."
                )
            }
        }
    }

    private fun shouldUseChunkedTranscription(file: File): Boolean {
        return !file.extension.equals("wav", ignoreCase = true)
    }

    private fun transcribeInChunks(
        file: File,
        loadedTranscriber: MoonshineTranscriber
    ): AsrResult {
        val totalDurationMs = AudioUtils.resolveMediaDurationMs(file.absolutePath)
            ?.takeIf { it > 0L }

        val chunkDurationMs = resolveChunkDurationMs()
        val overlapMs = min(CHUNK_OVERLAP_MS, max(500L, chunkDurationMs / 5))
        val stepMs = max(1L, chunkDurationMs - overlapMs)

        val mergedLines = mutableListOf<AsrLine>()
        val disputedLines = mutableListOf<AsrLine>()
        var previousTailLines: List<AsrLine> = emptyList()
        var chunkStartMs = 0L

        while (true) {
            val chunkEndMs = if (totalDurationMs != null) {
                min(totalDurationMs, chunkStartMs + chunkDurationMs)
            } else {
                chunkStartMs + chunkDurationMs
            }
            val chunkSamples = AudioUtils.decodeMediaSegmentAsFloat(
                filePath = file.absolutePath,
                startTimeMs = chunkStartMs,
                endTimeMs = chunkEndMs,
                targetSampleRate = AudioUtils.TARGET_SAMPLE_RATE
            )
            if (chunkSamples.isEmpty()) {
                break
            }
            val chunkTranscript = loadedTranscriber.transcribe(chunkSamples, AudioUtils.TARGET_SAMPLE_RATE)
            val normalizedChunkLines = normalizeLines(chunkTranscript.lines.asList(), offsetMs = chunkStartMs)
            if (chunkStartMs == 0L) {
                mergedLines.addAll(normalizedChunkLines)
            } else {
                val leadingBoundaryEndMs = chunkStartMs + overlapMs
                val leadingBoundaryLines = normalizedChunkLines.filter { it.startTimeMs < leadingBoundaryEndMs }
                val regularLines = normalizedChunkLines.filterNot { it.startTimeMs < leadingBoundaryEndMs }
                mergedLines.addAll(regularLines)
                leadingBoundaryLines
                    .filter { boundary ->
                        previousTailLines.none { existing ->
                            isLikelySameBoundaryLine(existing, boundary)
                        }
                    }
                    .forEach(disputedLines::add)
            }

            previousTailLines = normalizedChunkLines.filter { line ->
                line.startTimeMs < chunkEndMs && line.endTimeMs > chunkEndMs - overlapMs
            }

            if (totalDurationMs != null && chunkEndMs >= totalDurationMs) {
                break
            }
            chunkStartMs += stepMs
        }

        val finalLines = applySmallPauseProtection(mergeDuplicateLines(mergedLines))
        val finalDisputed = applySmallPauseProtection(mergeDuplicateLines(disputedLines)).filter { dispute ->
            finalLines.none { regular -> isLikelySameBoundaryLine(regular, dispute) }
        }
        return AsrResult(
            text = finalLines.joinToString(" ") { it.text },
            lines = finalLines,
            confidence = 1f,
            disputedLines = finalDisputed
        )
    }

    private fun normalizeLines(
        lines: List<MoonshineTranscriber.TranscriptLine>,
        offsetMs: Long = 0L
    ): List<AsrLine> {
        return lines.mapNotNull { line ->
            val normalizedText = line.text.trim()
            if (normalizedText.isEmpty()) {
                null
            } else {
                val safeStartTimeMs = (line.startTimeMs + offsetMs).coerceAtLeast(0L)
                AsrLine(
                    text = normalizedText,
                    startTimeMs = safeStartTimeMs,
                    endTimeMs = (line.endTimeMs + offsetMs).coerceAtLeast(safeStartTimeMs)
                )
            }
        }.sortedBy { it.startTimeMs }
    }

    private fun mergeDuplicateLines(lines: List<AsrLine>): List<AsrLine> {
        if (lines.isEmpty()) {
            return emptyList()
        }

        val merged = mutableListOf<AsrLine>()
        lines.sortedBy { it.startTimeMs }.forEach { candidate ->
            val safeStart = candidate.startTimeMs.coerceAtLeast(0L)
            val safeCandidate = candidate.copy(
                startTimeMs = safeStart,
                endTimeMs = candidate.endTimeMs.coerceAtLeast(safeStart)
            )
            val last = merged.lastOrNull()
            if (last != null && isLikelySameBoundaryLine(last, safeCandidate)) {
                merged[merged.lastIndex] = last.copy(
                    text = if (last.text.length >= safeCandidate.text.length) last.text else safeCandidate.text,
                    startTimeMs = min(last.startTimeMs, safeCandidate.startTimeMs),
                    endTimeMs = max(last.endTimeMs, safeCandidate.endTimeMs)
                )
            } else {
                merged.add(safeCandidate)
            }
        }
        return merged
    }

    private fun applySmallPauseProtection(lines: List<AsrLine>): List<AsrLine> {
        if (lines.size < 2) {
            return lines
        }

        val merged = mutableListOf<AsrLine>()
        lines.sortedBy { it.startTimeMs }.forEach { current ->
            val safeCurrent = current.copy(
                startTimeMs = current.startTimeMs.coerceAtLeast(0L),
                endTimeMs = current.endTimeMs.coerceAtLeast(current.startTimeMs.coerceAtLeast(0L))
            )
            val previous = merged.lastOrNull()
            if (previous != null && shouldMergeBySmallPause(previous, safeCurrent)) {
                merged[merged.lastIndex] = mergeLines(previous, safeCurrent)
            } else {
                merged.add(safeCurrent)
            }
        }
        return merged
    }

    private fun shouldMergeBySmallPause(previous: AsrLine, current: AsrLine): Boolean {
        val pauseGapMs = current.startTimeMs - previous.endTimeMs
        if (pauseGapMs > SMALL_PAUSE_PROTECTION_MS) {
            return false
        }
        if (current.endTimeMs - previous.startTimeMs > MAX_MERGED_SENTENCE_SPAN_MS) {
            return false
        }

        val previousText = previous.text.trim()
        val currentText = current.text.trim()
        if (previousText.isEmpty() || currentText.isEmpty()) {
            return false
        }

        val previousEndsSentence = previousText.lastOrNull() in TERMINAL_PUNCTUATION
        val currentStartsContinuation = startsWithContinuation(currentText)
        val hasShortFragment =
            previousText.length <= SHORT_FRAGMENT_CHAR_THRESHOLD ||
                currentText.length <= SHORT_FRAGMENT_CHAR_THRESHOLD

        if (previousEndsSentence && !currentStartsContinuation && pauseGapMs >= HARD_BOUNDARY_MIN_GAP_MS) {
            return false
        }

        if (hasShortFragment || currentStartsContinuation) {
            return true
        }

        return !previousEndsSentence
    }

    private fun mergeLines(previous: AsrLine, current: AsrLine): AsrLine {
        val previousText = previous.text.trim()
        val currentText = current.text.trim()
        val mergedText = if (previousText.isEmpty()) {
            currentText
        } else if (currentText.isEmpty()) {
            previousText
        } else {
            val separator = if (shouldInsertSpaceBetween(previousText, currentText)) " " else ""
            previousText + separator + currentText
        }
        return AsrLine(
            text = mergedText,
            startTimeMs = min(previous.startTimeMs, current.startTimeMs),
            endTimeMs = max(previous.endTimeMs, current.endTimeMs)
        )
    }

    private fun startsWithContinuation(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return false
        }
        val firstWord = normalized.lowercase().split(Regex("\\s+")).firstOrNull().orEmpty()
        if (firstWord in CONTINUATION_WORDS) {
            return true
        }
        return CONTINUATION_PREFIXES.any { normalized.startsWith(it) }
    }

    private fun shouldInsertSpaceBetween(left: String, right: String): Boolean {
        val leftLast = left.lastOrNull() ?: return false
        val rightFirst = right.firstOrNull() ?: return false
        if (!leftLast.isLetterOrDigit() || !rightFirst.isLetterOrDigit()) {
            return false
        }
        if (isCjk(leftLast) || isCjk(rightFirst)) {
            return false
        }
        return true
    }

    private fun isCjk(char: Char): Boolean {
        return when (Character.UnicodeScript.of(char.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }

    private fun isLikelySameBoundaryLine(first: AsrLine, second: AsrLine): Boolean {
        val firstText = normalizeTextForBoundaryMatch(first.text)
        val secondText = normalizeTextForBoundaryMatch(second.text)
        if (firstText.isEmpty() || secondText.isEmpty()) {
            return false
        }

        val sameText =
            firstText == secondText || firstText.contains(secondText) || secondText.contains(firstText)
        val closeInTime = abs(first.startTimeMs - second.startTimeMs) <= BOUNDARY_MATCH_WINDOW_MS
        val overlapsInTime =
            first.startTimeMs <= second.endTimeMs && second.startTimeMs <= first.endTimeMs
        return sameText && (closeInTime || overlapsInTime)
    }

    private fun normalizeTextForBoundaryMatch(text: String): String {
        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun resolveChunkDurationMs(): Long {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        if (maxHeapBytes <= 0L) {
            return 60_000L
        }

        val chunkBudgetBytes = (maxHeapBytes * CHUNK_HEAP_FRACTION).toLong()
        val targetBytesPerSecond = AudioUtils.TARGET_SAMPLE_RATE.toLong() * BYTES_PER_SAMPLE
        val chunkSeconds = (chunkBudgetBytes / targetBytesPerSecond).coerceIn(
            MIN_CHUNK_DURATION_MS / 1000L,
            MAX_CHUNK_DURATION_MS / 1000L
        )
        return chunkSeconds * 1000L
    }

    fun isReady(): Boolean = synchronized(transcriberLock) {
        isInitialized && transcriber?.isLoaded() == true
    }

    fun release() {
        synchronized(transcriberLock) {
            transcriber?.release()
            transcriber = null
            isInitialized = false
        }
    }

    fun isModelAvailable(modelPath: String? = null): Boolean {
        val modelSpec = resolveModelSpec(modelPath, MoonshineTranscriber.ARCH_MEDIUM_STREAMING)
        return hasRequiredFiles(modelSpec.directory, modelSpec.arch)
    }

    fun getLastInitErrorMessage(): String? = lastInitErrorMessage

    private fun initializeLocked(
        modelPath: String?,
        arch: Int
    ): Boolean {
        if (isInitialized) {
            lastInitErrorMessage = null
            return true
        }

        return try {
            val modelSpec = resolveModelSpec(modelPath, arch)
            if (!hasRequiredFiles(modelSpec.directory, modelSpec.arch)) {
                lastInitErrorMessage =
                    "Missing model files in ${modelSpec.directory.absolutePath} for arch=${modelSpec.arch}"
                android.util.Log.w(
                    "MoonshineAsr",
                    lastInitErrorMessage ?: "Missing model files"
                )
                false
            } else {
                val loadedTranscriber = MoonshineTranscriber()
                loadedTranscriber.loadFromFiles(modelSpec.directory.absolutePath, modelSpec.arch)
                transcriber = loadedTranscriber
                isInitialized = true
                lastInitErrorMessage = null

                android.util.Log.i(
                    "MoonshineAsr",
                    "Loaded Moonshine model from ${modelSpec.directory.absolutePath} with arch=${modelSpec.arch}"
                )
                true
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            android.util.Log.e("MoonshineAsr", "Failed to initialize Moonshine", error)
            transcriber?.release()
            transcriber = null
            isInitialized = false
            lastInitErrorMessage = error.message ?: "Unknown native initialization error"
            false
        }
    }

    private fun resolveModelSpec(modelPath: String?, requestedArch: Int): ModelSpec {
        if (modelPath != null) {
            val explicitDir = File(modelPath)
            return ModelSpec(explicitDir, detectArch(explicitDir, requestedArch))
        }

        extractBundledModelIfNeeded()?.let { return it }

        val filesystemCandidates = listOf(
            ModelSpec(File(context.filesDir, "moonshine/medium-streaming-en"), MoonshineTranscriber.ARCH_MEDIUM_STREAMING),
            ModelSpec(File(context.filesDir, "moonshine/small-streaming-en"), MoonshineTranscriber.ARCH_SMALL_STREAMING),
            ModelSpec(File(context.filesDir, "moonshine/base-streaming-en"), MoonshineTranscriber.ARCH_BASE_STREAMING),
            ModelSpec(File(context.filesDir, "moonshine/tiny-streaming-en"), MoonshineTranscriber.ARCH_TINY_STREAMING),
            ModelSpec(File(context.filesDir, "moonshine/base-en"), MoonshineTranscriber.ARCH_BASE),
            ModelSpec(File(context.filesDir, "moonshine/tiny-en"), MoonshineTranscriber.ARCH_TINY)
        )

        filesystemCandidates.firstOrNull { hasRequiredFiles(it.directory, it.arch) }?.let {
            return it
        }

        return ModelSpec(
            File(context.filesDir, "moonshine/${defaultDirectoryName(requestedArch)}"),
            requestedArch
        )
    }

    private fun extractBundledModelIfNeeded(): ModelSpec? {
        val assetChildren = context.assets.list("moonshine")?.toSet().orEmpty()
        val candidates = listOf(
            BundledModel("medium-streaming-en", MoonshineTranscriber.ARCH_MEDIUM_STREAMING),
            BundledModel("small-streaming-en", MoonshineTranscriber.ARCH_SMALL_STREAMING),
            BundledModel("base-streaming-en", MoonshineTranscriber.ARCH_BASE_STREAMING),
            BundledModel("tiny-streaming-en", MoonshineTranscriber.ARCH_TINY_STREAMING),
            BundledModel("base-en", MoonshineTranscriber.ARCH_BASE),
            BundledModel("tiny-en", MoonshineTranscriber.ARCH_TINY)
        )

        val bundled = candidates.firstOrNull { assetChildren.contains(it.assetDirName) } ?: return null
        val outputDir = File(context.filesDir, "moonshine/${bundled.assetDirName}")
        if (!hasRequiredFiles(outputDir, bundled.arch)) {
            copyAssetTree("moonshine/${bundled.assetDirName}", outputDir)
        }

        return ModelSpec(outputDir, bundled.arch)
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                destination.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        destination.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(destination, child))
        }
    }

    private fun hasRequiredFiles(directory: File, arch: Int): Boolean {
        if (!directory.exists() || !directory.isDirectory) {
            return false
        }

        return requiredFilesForArch(arch).all { required ->
            File(directory, required).exists()
        }
    }

    private fun requiredFilesForArch(arch: Int): List<String> {
        return if (isStreamingArch(arch)) {
            listOf(
                "frontend.ort",
                "encoder.ort",
                "adapter.ort",
                "cross_kv.ort",
                "decoder_kv.ort",
                "streaming_config.json",
                "tokenizer.bin"
            )
        } else {
            listOf("encoder_model.ort", "decoder_model_merged.ort", "tokenizer.bin")
        }
    }

    private fun detectArch(directory: File, requestedArch: Int): Int {
        if (hasRequiredFiles(directory, requestedArch)) {
            return requestedArch
        }

        return when {
            File(directory, "streaming_config.json").exists() -> when {
                isStreamingArch(requestedArch) -> requestedArch
                directory.name.contains("tiny", ignoreCase = true) -> MoonshineTranscriber.ARCH_TINY_STREAMING
                directory.name.contains("base", ignoreCase = true) -> MoonshineTranscriber.ARCH_BASE_STREAMING
                directory.name.contains("small", ignoreCase = true) -> MoonshineTranscriber.ARCH_SMALL_STREAMING
                else -> MoonshineTranscriber.ARCH_MEDIUM_STREAMING
            }

            File(directory, "encoder_model.ort").exists() -> when {
                requestedArch == MoonshineTranscriber.ARCH_BASE -> requestedArch
                directory.name.contains("base", ignoreCase = true) -> MoonshineTranscriber.ARCH_BASE
                else -> MoonshineTranscriber.ARCH_TINY
            }

            else -> requestedArch
        }
    }

    private fun isStreamingArch(arch: Int): Boolean {
        return arch == MoonshineTranscriber.ARCH_TINY_STREAMING ||
            arch == MoonshineTranscriber.ARCH_BASE_STREAMING ||
            arch == MoonshineTranscriber.ARCH_SMALL_STREAMING ||
            arch == MoonshineTranscriber.ARCH_MEDIUM_STREAMING
    }

    private fun defaultDirectoryName(arch: Int): String = when (arch) {
        MoonshineTranscriber.ARCH_TINY -> "tiny-en"
        MoonshineTranscriber.ARCH_BASE -> "base-en"
        MoonshineTranscriber.ARCH_TINY_STREAMING -> "tiny-streaming-en"
        MoonshineTranscriber.ARCH_BASE_STREAMING -> "base-streaming-en"
        MoonshineTranscriber.ARCH_SMALL_STREAMING -> "small-streaming-en"
        else -> "medium-streaming-en"
    }
}
