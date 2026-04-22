package com.orien.shadowing.presentation.training

import com.orien.shadowing.data.local.g2p.G2pService
import com.orien.shadowing.data.local.g2p.WordPhonemeResult
import com.orien.shadowing.domain.usecase.TextCompareUseCase
import javax.inject.Inject

enum class TrainingWordStatus {
    CORRECT,
    NEEDS_IMPROVEMENT,
    WRONG_OR_MISSING,
    UNKNOWN
}

data class TargetWordFeedback(
    val index: Int,
    val targetWord: String,
    val normalizedTargetWord: String,
    val targetIpa: String?,
    val status: TrainingWordStatus
)

data class WordPronunciationHint(
    val targetWord: String,
    val targetIpa: String,
    val message: String,
    val status: TrainingWordStatus
)

data class TrainingFeedbackResult(
    val compareResult: TextCompareUseCase.CompareResult,
    val wordFeedback: List<TargetWordFeedback>,
    val pronunciationHints: List<WordPronunciationHint>
)

class TrainingFeedbackComposer @Inject constructor(
    private val g2pService: G2pService,
    private val textCompare: TextCompareUseCase
) {
    private data class WordEvidence(
        val feedback: TargetWordFeedback,
        val targetPhonemes: List<String>?,
        val recognizedPhonemes: List<String>?,
        val phonemeSimilarity: Float?
    )

    private data class HintCandidate(
        val hint: WordPronunciationHint,
        val wordIndex: Int
    )

    fun evaluate(targetText: String, recognizedText: String): TrainingFeedbackResult {
        val compareResult = textCompare.compare(targetText, recognizedText)
        val targetDisplayWords = SentenceWordTokenizer.wordTokens(targetText)
        val targetPhonemes = alignWordPhonemes(
            compareResult.targetWords,
            g2pService.textToWordPhonemes(targetText)
        )
        val recognizedPhonemes = alignWordPhonemes(
            compareResult.recognizedWords,
            g2pService.textToWordPhonemes(recognizedText)
        )
        val alignmentByTargetIndex = compareResult.wordAlignments
            .filter { it.targetIndex != null }
            .associateBy { it.targetIndex!! }
        val lowEvidence = hasLowEvidence(compareResult)

        val evidenceList = compareResult.targetWords.mapIndexed { index, normalizedWord ->
            val targetResult = targetPhonemes.getOrNull(index)
            val alignment = alignmentByTargetIndex[index]
            val recognizedResult = alignment
                ?.recognizedIndex
                ?.let { recognizedPhonemes.getOrNull(it) }
            val status = resolveStatus(
                alignment = alignment,
                targetWord = normalizedWord,
                targetPhonemes = targetResult?.phonemes,
                recognizedPhonemes = recognizedResult?.phonemes,
                lowEvidence = lowEvidence,
                matchScore = compareResult.matchScore
            )
            val feedback = TargetWordFeedback(
                index = index,
                targetWord = targetDisplayWords.getOrNull(index) ?: normalizedWord,
                normalizedTargetWord = normalizedWord,
                targetIpa = ArpabetToIpaDisplayFormatter.toIpa(targetResult?.phonemes),
                status = status
            )
            WordEvidence(
                feedback = feedback,
                targetPhonemes = targetResult?.phonemes,
                recognizedPhonemes = recognizedResult?.phonemes,
                phonemeSimilarity = phonemeSimilarity(
                    targetResult?.phonemes,
                    recognizedResult?.phonemes
                )
            )
        }

        val pronunciationHints = evidenceList
            .mapNotNull(::buildHintCandidate)
            .sortedWith(
                compareBy<HintCandidate>(
                    { hintPriority(it.hint.status) },
                    { it.wordIndex }
                )
            )
            .map { it.hint }

        return TrainingFeedbackResult(
            compareResult = compareResult,
            wordFeedback = evidenceList.map { it.feedback },
            pronunciationHints = pronunciationHints
        )
    }

    private fun buildHintCandidate(evidence: WordEvidence): HintCandidate? {
        val status = evidence.feedback.status
        if (status == TrainingWordStatus.CORRECT || status == TrainingWordStatus.UNKNOWN) {
            return null
        }

        val targetIpa = evidence.feedback.targetIpa.orEmpty()
        val message = when {
            evidence.targetPhonemes.isNullOrEmpty() || targetIpa.isBlank() ->
                buildFallbackMessage(
                    targetWord = evidence.feedback.targetWord,
                    status = status
                )

            evidence.recognizedPhonemes.isNullOrEmpty() ->
                buildMissingWordMessage(
                    targetIpa = targetIpa,
                    status = status
                )

            else -> buildMessage(
                targetPhonemes = evidence.targetPhonemes,
                recognizedPhonemes = evidence.recognizedPhonemes,
                targetIpa = targetIpa
            )
        }

        return HintCandidate(
            hint = WordPronunciationHint(
                targetWord = evidence.feedback.targetWord,
                targetIpa = targetIpa,
                message = message,
                status = status
            ),
            wordIndex = evidence.feedback.index
        )
    }

    private fun buildMissingWordMessage(
        targetIpa: String,
        status: TrainingWordStatus
    ): String = when (status) {
        TrainingWordStatus.WRONG_OR_MISSING ->
            "这个词可能漏读了，或和目标读音差距较大。目标发音 $targetIpa"

        else ->
            "这个词再读清楚一点。目标发音 $targetIpa"
    }

    private fun buildMessage(
        targetPhonemes: List<String>,
        recognizedPhonemes: List<String>,
        targetIpa: String
    ): String {
        val mismatchIndex = firstMismatchTargetIndex(targetPhonemes, recognizedPhonemes)
            ?: return "整体发音还可以更稳定一些。目标发音 $targetIpa"

        return when {
            mismatchIndex == 0 -> {
                val leadingIpa = ArpabetToIpaDisplayFormatter.toIpa(
                    listOf(targetPhonemes.first())
                ) ?: targetIpa
                "注意开头音 $leadingIpa"
            }

            isTrailingConsonantIssue(targetPhonemes, mismatchIndex) ->
                "结尾辅音可能不够完整，目标发音 $targetIpa"

            isVowel(targetPhonemes[mismatchIndex]) ->
                "元音部分可以更清晰，目标发音 $targetIpa"

            else ->
                "辅音部分可以更清晰，目标发音 $targetIpa"
        }
    }

    private fun buildFallbackMessage(
        targetWord: String,
        status: TrainingWordStatus
    ): String = when (status) {
        TrainingWordStatus.WRONG_OR_MISSING ->
            "$targetWord 这个词和目标读法差距较大，建议放慢一点再读一遍"

        TrainingWordStatus.NEEDS_IMPROVEMENT ->
            "$targetWord 这个词还可以更清晰一些，建议对照原句再读一遍"

        else -> "$targetWord 这个词再读清楚一点"
    }

    private fun resolveStatus(
        alignment: TextCompareUseCase.WordAlignment?,
        targetWord: String,
        targetPhonemes: List<String>?,
        recognizedPhonemes: List<String>?,
        lowEvidence: Boolean,
        matchScore: Float
    ): TrainingWordStatus {
        if (alignment == null) {
            return TrainingWordStatus.UNKNOWN
        }

        return when (alignment.type) {
            TextCompareUseCase.AlignmentType.Match -> TrainingWordStatus.CORRECT

            TextCompareUseCase.AlignmentType.Replace -> {
                if (lowEvidence) {
                    return TrainingWordStatus.UNKNOWN
                }

                val similarity = phonemeSimilarity(targetPhonemes, recognizedPhonemes)
                when {
                    similarity == null -> TrainingWordStatus.NEEDS_IMPROVEMENT
                    similarity >= PHONEME_NEEDS_IMPROVEMENT_THRESHOLD ->
                        TrainingWordStatus.NEEDS_IMPROVEMENT

                    isLenientTargetWord(targetWord, targetPhonemes) ->
                        TrainingWordStatus.NEEDS_IMPROVEMENT

                    similarity >= PHONEME_SEVERE_MISMATCH_THRESHOLD ->
                        TrainingWordStatus.NEEDS_IMPROVEMENT

                    else -> TrainingWordStatus.WRONG_OR_MISSING
                }
            }

            TextCompareUseCase.AlignmentType.Delete -> {
                if (lowEvidence) {
                    TrainingWordStatus.UNKNOWN
                } else if (
                    matchScore >= DELETE_LENIENT_SCORE ||
                    isLenientTargetWord(targetWord, targetPhonemes)
                ) {
                    TrainingWordStatus.NEEDS_IMPROVEMENT
                } else {
                    TrainingWordStatus.WRONG_OR_MISSING
                }
            }

            TextCompareUseCase.AlignmentType.Insert -> TrainingWordStatus.UNKNOWN
        }
    }

    private fun hasLowEvidence(compareResult: TextCompareUseCase.CompareResult): Boolean {
        val recognizedCount = compareResult.recognizedWords.size
        val targetCount = compareResult.targetWords.size
        return recognizedCount == 0 ||
            (
                targetCount >= 3 &&
                    recognizedCount <= maxOf(1, targetCount / 3) &&
                    compareResult.matchScore < 0.34f
                )
    }

    private fun phonemeSimilarity(
        targetPhonemes: List<String>?,
        recognizedPhonemes: List<String>?
    ): Float? {
        if (targetPhonemes.isNullOrEmpty() || recognizedPhonemes.isNullOrEmpty()) {
            return null
        }

        val distance = phonemeEditDistance(targetPhonemes, recognizedPhonemes)
        val maxLength = maxOf(targetPhonemes.size, recognizedPhonemes.size).coerceAtLeast(1)
        return (1f - distance.toFloat() / maxLength).coerceIn(0f, 1f)
    }

    private fun phonemeEditDistance(target: List<String>, recognized: List<String>): Int {
        val rows = target.size + 1
        val cols = recognized.size + 1
        val dp = Array(rows) { IntArray(cols) }

        for (row in 0 until rows) {
            dp[row][0] = row
        }
        for (col in 0 until cols) {
            dp[0][col] = col
        }

        for (row in 1 until rows) {
            for (col in 1 until cols) {
                dp[row][col] = if (
                    normalizePhoneme(target[row - 1]) == normalizePhoneme(recognized[col - 1])
                ) {
                    dp[row - 1][col - 1]
                } else {
                    minOf(
                        dp[row - 1][col] + 1,
                        dp[row][col - 1] + 1,
                        dp[row - 1][col - 1] + 1
                    )
                }
            }
        }

        return dp[target.size][recognized.size]
    }

    private fun firstMismatchTargetIndex(
        targetPhonemes: List<String>,
        recognizedPhonemes: List<String>
    ): Int? {
        val minLength = minOf(targetPhonemes.size, recognizedPhonemes.size)
        for (index in 0 until minLength) {
            if (normalizePhoneme(targetPhonemes[index]) != normalizePhoneme(recognizedPhonemes[index])) {
                return index
            }
        }

        return when {
            targetPhonemes.size > recognizedPhonemes.size ->
                recognizedPhonemes.size.coerceAtMost(targetPhonemes.lastIndex)

            recognizedPhonemes.size > targetPhonemes.size -> targetPhonemes.lastIndex
            else -> null
        }
    }

    private fun isTrailingConsonantIssue(
        targetPhonemes: List<String>,
        mismatchIndex: Int
    ): Boolean {
        if (mismatchIndex >= targetPhonemes.size) {
            return false
        }

        val trailing = targetPhonemes.drop(mismatchIndex)
        return trailing.isNotEmpty() && trailing.all { !isVowel(it) }
    }

    private fun isVowel(phoneme: String): Boolean =
        normalizePhoneme(phoneme) in vowelPhonemes

    private fun normalizePhoneme(phoneme: String): String =
        phoneme.uppercase().replace(Regex("\\d"), "")

    private fun alignWordPhonemes(
        normalizedWords: List<String>,
        phonemeResults: List<WordPhonemeResult>
    ): List<WordPhonemeResult?> {
        if (normalizedWords.isEmpty()) {
            return emptyList()
        }

        val aligned = MutableList<WordPhonemeResult?>(normalizedWords.size) { null }
        var cursor = 0
        normalizedWords.forEachIndexed { index, normalizedWord ->
            while (cursor < phonemeResults.size) {
                val candidate = phonemeResults[cursor++]
                if (candidate.normalizedToken == normalizedWord) {
                    aligned[index] = candidate
                    break
                }
            }
        }
        return aligned
    }

    private fun isLenientTargetWord(
        targetWord: String,
        targetPhonemes: List<String>?
    ): Boolean {
        return targetWord.length <= SHORT_WORD_LENGTH ||
            targetPhonemes.isNullOrEmpty() ||
            targetPhonemes.size <= SHORT_PHONEME_LENGTH
    }

    private fun hintPriority(status: TrainingWordStatus): Int = when (status) {
        TrainingWordStatus.WRONG_OR_MISSING -> 0
        TrainingWordStatus.NEEDS_IMPROVEMENT -> 1
        TrainingWordStatus.UNKNOWN -> 2
        TrainingWordStatus.CORRECT -> 3
    }

    private companion object {
        private const val PHONEME_NEEDS_IMPROVEMENT_THRESHOLD = 0.5f
        private const val PHONEME_SEVERE_MISMATCH_THRESHOLD = 0.25f
        private const val DELETE_LENIENT_SCORE = 0.7f
        private const val SHORT_WORD_LENGTH = 3
        private const val SHORT_PHONEME_LENGTH = 2
        private val vowelPhonemes = setOf(
            "AA",
            "AE",
            "AH",
            "AO",
            "AW",
            "AX",
            "AXR",
            "AY",
            "EH",
            "ER",
            "EY",
            "IH",
            "IY",
            "OW",
            "OY",
            "UH",
            "UW"
        )
    }
}

internal data class SentenceTextChunk(
    val text: String,
    val isWord: Boolean
)

internal object SentenceWordTokenizer {
    private val apostrophes = setOf('\'', '\u2018', '\u2019', '\u201b', '\u2032', '\u02bc')

    fun tokenize(text: String): List<SentenceTextChunk> {
        if (text.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<SentenceTextChunk>()
        val current = StringBuilder()
        var currentIsWord: Boolean? = null

        fun flush() {
            if (current.isEmpty() || currentIsWord == null) {
                return
            }
            chunks += SentenceTextChunk(
                text = current.toString(),
                isWord = currentIsWord == true
            )
            current.clear()
        }

        text.forEach { character ->
            val isWord = character.isLetterOrDigit() || character in apostrophes
            when {
                currentIsWord == null -> {
                    currentIsWord = isWord
                    current.append(character)
                }

                currentIsWord == isWord -> current.append(character)
                else -> {
                    flush()
                    currentIsWord = isWord
                    current.append(character)
                }
            }
        }
        flush()

        return chunks
    }

    fun wordTokens(text: String): List<String> =
        tokenize(text)
            .filter { it.isWord }
            .map { it.text }
}
