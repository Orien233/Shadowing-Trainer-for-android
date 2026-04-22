package com.orien.shadowing.presentation.training

import com.orien.shadowing.data.local.g2p.G2pDictionary
import com.orien.shadowing.data.local.g2p.G2pService
import com.orien.shadowing.domain.usecase.TextCompareUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingFeedbackComposerTest {
    private val dictionaryEntries = mapOf(
        "i" to listOf("AY"),
        "think" to listOf("TH", "IH", "NG", "K"),
        "sink" to listOf("S", "IH", "NG", "K"),
        "world" to listOf("W", "ER", "L", "D"),
        "word" to listOf("W", "ER", "D"),
        "bike" to listOf("B", "AY", "K"),
        "back" to listOf("B", "AE", "K"),
        "cat" to listOf("K", "AE", "T")
    )

    private val dictionary = object : G2pDictionary {
        override fun lookup(word: String): List<String>? = dictionaryEntries[word]

        override fun isLoaded(): Boolean = true
    }

    private val composer = TrainingFeedbackComposer(
        g2pService = G2pService(dictionary),
        textCompare = TextCompareUseCase()
    )

    @Test
    fun `formatter renders ipa for ui display`() {
        assertEquals("/ba\u026ak/", ArpabetToIpaDisplayFormatter.toIpa(listOf("B", "AY", "K")))
        assertEquals("/w\u025dld/", ArpabetToIpaDisplayFormatter.toIpa(listOf("W", "ER", "L", "D")))
        assertEquals("/?t/", ArpabetToIpaDisplayFormatter.toIpa(listOf("ZZ", "T")))
    }

    @Test
    fun `composer keeps close substitutions in needs improvement and produces hints for all`() {
        val result = composer.evaluate(
            targetText = "think world bike",
            recognizedText = "sink word back"
        )

        assertEquals(
            listOf(
                TrainingWordStatus.NEEDS_IMPROVEMENT,
                TrainingWordStatus.NEEDS_IMPROVEMENT,
                TrainingWordStatus.NEEDS_IMPROVEMENT
            ),
            result.wordFeedback.map { it.status }
        )
        assertEquals(3, result.pronunciationHints.size)
        assertEquals(
            setOf("think", "world", "bike"),
            result.pronunciationHints.map { it.targetWord.lowercase() }.toSet()
        )
        assertTrue(result.pronunciationHints.all { it.message.isNotBlank() })
    }

    @Test
    fun `composer keeps low evidence attempts conservative`() {
        val result = composer.evaluate(
            targetText = "think world",
            recognizedText = ""
        )

        assertEquals(
            listOf(TrainingWordStatus.UNKNOWN, TrainingWordStatus.UNKNOWN),
            result.wordFeedback.map { it.status }
        )
        assertTrue(result.pronunciationHints.isEmpty())
    }

    @Test
    fun `composer reserves red for severe mismatch`() {
        val result = composer.evaluate(
            targetText = "bike",
            recognizedText = "cat"
        )

        assertEquals(
            listOf(TrainingWordStatus.WRONG_OR_MISSING),
            result.wordFeedback.map { it.status }
        )
        assertEquals(1, result.pronunciationHints.size)
        assertEquals("/ba\u026ak/", result.pronunciationHints.single().targetIpa)
    }

    @Test
    fun `composer orders red hints before yellow hints`() {
        val result = composer.evaluate(
            targetText = "bike world",
            recognizedText = "cat word"
        )

        assertEquals(
            listOf(
                TrainingWordStatus.WRONG_OR_MISSING,
                TrainingWordStatus.NEEDS_IMPROVEMENT
            ),
            result.pronunciationHints.map { it.status }
        )
        assertEquals(
            listOf("bike", "world"),
            result.pronunciationHints.map { it.targetWord.lowercase() }
        )
    }

    @Test
    fun `composer still produces fallback hint when target word has no ipa`() {
        val result = composer.evaluate(
            targetText = "mystery bike",
            recognizedText = "cat back"
        )

        assertEquals(
            listOf(
                TrainingWordStatus.NEEDS_IMPROVEMENT,
                TrainingWordStatus.NEEDS_IMPROVEMENT
            ),
            result.wordFeedback.map { it.status }
        )
        assertEquals(2, result.pronunciationHints.size)
        assertTrue(
            result.pronunciationHints.any { hint ->
                hint.targetWord.equals("mystery", ignoreCase = true) &&
                    hint.targetIpa.isBlank() &&
                    hint.message.isNotBlank()
            }
        )
    }

    @Test
    fun `composer treats short missed words as needs improvement instead of red`() {
        val result = composer.evaluate(
            targetText = "I think",
            recognizedText = "think"
        )

        assertEquals(
            listOf(
                TrainingWordStatus.NEEDS_IMPROVEMENT,
                TrainingWordStatus.CORRECT
            ),
            result.wordFeedback.map { it.status }
        )
        assertEquals(1, result.pronunciationHints.size)
        assertFalse(result.pronunciationHints.single().message.isBlank())
    }
}
