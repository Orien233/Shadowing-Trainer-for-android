package com.orien.shadowing.domain.usecase

import javax.inject.Inject

/**
 * Lightweight token-level comparison between target and recognized text.
 */
class TextCompareUseCase @Inject constructor() {
    data class CompareResult(
        val matchScore: Float,
        val missedWords: List<String>,
        val extraWords: List<String>,
        val wrongWords: List<String>,
        val simpleFeedback: String,
        val targetWords: List<String> = emptyList(),
        val recognizedWords: List<String> = emptyList(),
        val wordAlignments: List<WordAlignment> = emptyList()
    )

    enum class AlignmentType {
        Match,
        Replace,
        Delete,
        Insert
    }

    data class WordAlignment(
        val type: AlignmentType,
        val targetIndex: Int? = null,
        val targetWord: String? = null,
        val recognizedIndex: Int? = null,
        val recognizedWord: String? = null
    )

    fun compare(target: String, recognized: String): CompareResult {
        val targetTokens = normalize(target)
        val recognizedTokens = normalize(recognized)

        if (targetTokens.isEmpty()) {
            return CompareResult(
                matchScore = if (recognizedTokens.isEmpty()) 1f else 0f,
                missedWords = emptyList(),
                extraWords = recognizedTokens,
                wrongWords = emptyList(),
                simpleFeedback = if (recognizedTokens.isEmpty()) {
                    "Perfect."
                } else {
                    "Target sentence is empty, but speech was detected."
                },
                targetWords = targetTokens,
                recognizedWords = recognizedTokens,
                wordAlignments = recognizedTokens.mapIndexed { index, word ->
                    WordAlignment(
                        type = AlignmentType.Insert,
                        recognizedIndex = index,
                        recognizedWord = word
                    )
                }
            )
        }

        val operations = align(targetTokens, recognizedTokens)
        val matchedWords = operations.count { it.type == AlignmentType.Match }
        val missedWords = operations.filter { it.type == AlignmentType.Delete }.mapNotNull { it.targetWord }
        val extraWords = operations.filter { it.type == AlignmentType.Insert }.mapNotNull { it.recognizedWord }
        val wrongWords = operations.filter { it.type == AlignmentType.Replace }.map {
            "${it.recognizedWord ?: ""} -> ${it.targetWord ?: ""}"
        }

        val matchScore = matchedWords.toFloat() / targetTokens.size.coerceAtLeast(1)
        return CompareResult(
            matchScore = matchScore.coerceIn(0f, 1f),
            missedWords = missedWords,
            extraWords = extraWords,
            wrongWords = wrongWords,
            simpleFeedback = buildFeedback(matchScore, missedWords, extraWords, wrongWords),
            targetWords = targetTokens,
            recognizedWords = recognizedTokens,
            wordAlignments = operations
        )
    }

    private fun normalize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[\\u2018\\u2019\\u201b\\u2032\\u02bc]"), "'")
            .replace(Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2212]"), " ")
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun align(target: List<String>, recognized: List<String>): List<WordAlignment> {
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
                if (target[row - 1] == recognized[col - 1]) {
                    dp[row][col] = dp[row - 1][col - 1]
                } else {
                    dp[row][col] = minOf(
                        dp[row - 1][col] + 1,
                        dp[row][col - 1] + 1,
                        dp[row - 1][col - 1] + 1
                    )
                }
            }
        }

        val operations = mutableListOf<WordAlignment>()
        var row = target.size
        var col = recognized.size
        while (row > 0 || col > 0) {
            when {
                row > 0 && col > 0 && target[row - 1] == recognized[col - 1] -> {
                    operations.add(
                        WordAlignment(
                            type = AlignmentType.Match,
                            targetIndex = row - 1,
                            targetWord = target[row - 1],
                            recognizedIndex = col - 1,
                            recognizedWord = recognized[col - 1]
                        )
                    )
                    row--
                    col--
                }

                row > 0 && col > 0 && dp[row][col] == dp[row - 1][col - 1] + 1 -> {
                    operations.add(
                        WordAlignment(
                            type = AlignmentType.Replace,
                            targetIndex = row - 1,
                            targetWord = target[row - 1],
                            recognizedIndex = col - 1,
                            recognizedWord = recognized[col - 1]
                        )
                    )
                    row--
                    col--
                }

                row > 0 && dp[row][col] == dp[row - 1][col] + 1 -> {
                    operations.add(
                        WordAlignment(
                            type = AlignmentType.Delete,
                            targetIndex = row - 1,
                            targetWord = target[row - 1]
                        )
                    )
                    row--
                }

                else -> {
                    operations.add(
                        WordAlignment(
                            type = AlignmentType.Insert,
                            recognizedIndex = col - 1,
                            recognizedWord = recognized[col - 1]
                        )
                    )
                    col--
                }
            }
        }

        operations.reverse()
        return operations
    }

    private fun buildFeedback(
        score: Float,
        missedWords: List<String>,
        extraWords: List<String>,
        wrongWords: List<String>
    ): String = when {
        score >= 0.95f && extraWords.isEmpty() && wrongWords.isEmpty() -> "Perfect."
        score >= 0.8f -> "Good attempt. Clean up a few words."
        score >= 0.6f -> "Fair attempt. Focus on the missing words."
        missedWords.isEmpty() && extraWords.isNotEmpty() -> "You added extra words."
        wrongWords.isNotEmpty() -> "Some words were replaced with different ones."
        else -> "Needs more practice."
    }
}
