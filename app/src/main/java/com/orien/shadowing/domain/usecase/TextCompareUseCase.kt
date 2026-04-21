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
        val simpleFeedback: String
    )

    private enum class OpType {
        Match,
        Replace,
        Delete,
        Insert
    }

    private data class Op(
        val type: OpType,
        val target: String? = null,
        val recognized: String? = null
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
                }
            )
        }

        val operations = align(targetTokens, recognizedTokens)
        val matchedWords = operations.count { it.type == OpType.Match }
        val missedWords = operations.filter { it.type == OpType.Delete }.mapNotNull { it.target }
        val extraWords = operations.filter { it.type == OpType.Insert }.mapNotNull { it.recognized }
        val wrongWords = operations.filter { it.type == OpType.Replace }.map {
            "${it.recognized ?: ""} -> ${it.target ?: ""}"
        }

        val matchScore = matchedWords.toFloat() / targetTokens.size.coerceAtLeast(1)
        return CompareResult(
            matchScore = matchScore.coerceIn(0f, 1f),
            missedWords = missedWords,
            extraWords = extraWords,
            wrongWords = wrongWords,
            simpleFeedback = buildFeedback(matchScore, missedWords, extraWords, wrongWords)
        )
    }

    private fun normalize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun align(target: List<String>, recognized: List<String>): List<Op> {
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

        val operations = mutableListOf<Op>()
        var row = target.size
        var col = recognized.size
        while (row > 0 || col > 0) {
            when {
                row > 0 && col > 0 && target[row - 1] == recognized[col - 1] -> {
                    operations.add(Op(OpType.Match, target[row - 1], recognized[col - 1]))
                    row--
                    col--
                }

                row > 0 && col > 0 && dp[row][col] == dp[row - 1][col - 1] + 1 -> {
                    operations.add(Op(OpType.Replace, target[row - 1], recognized[col - 1]))
                    row--
                    col--
                }

                row > 0 && dp[row][col] == dp[row - 1][col] + 1 -> {
                    operations.add(Op(OpType.Delete, target[row - 1], null))
                    row--
                }

                else -> {
                    operations.add(Op(OpType.Insert, null, recognized[col - 1]))
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
