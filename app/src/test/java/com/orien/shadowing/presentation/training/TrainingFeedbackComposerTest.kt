package com.orien.shadowing.presentation.training

import com.orien.shadowing.data.local.g2p.G2pDictionary
import com.orien.shadowing.data.local.g2p.G2pService
import com.orien.shadowing.domain.usecase.TextCompareUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingFeedbackComposerTest {
    private val dictionaryEntries = mapOf(
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
        assertEquals("/baɪk/", ArpabetToIpaDisplayFormatter.toIpa(listOf("B", "AY", "K")))
        assertEquals("/wɝld/", ArpabetToIpaDisplayFormatter.toIpa(listOf("W", "ER", "L", "D")))
        assertEquals("/?t/", ArpabetToIpaDisplayFormatter.toIpa(listOf("ZZ", "T")))
    }

    @Test
    fun `composer marks close phoneme substitutions as needs improvement with ipa hints`() {
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
        assertEquals(
            listOf("/θɪŋk/", "/wɝld/", "/baɪk/"),
            result.wordFeedback.map { it.targetIpa }
        )
        assertEquals(3, result.pronunciationHints.size)
        assertEquals(
            setOf("think", "world", "bike"),
            result.pronunciationHints.map { it.targetWord.lowercase() }.toSet()
        )
        assertTrue(result.pronunciationHints.any { it.message.contains("开头音 /θ/") })
        assertTrue(result.pronunciationHints.any { it.message.contains("结尾辅音可能不够完整") })
        assertTrue(result.pronunciationHints.any { it.message.contains("元音部分可以更清晰") })
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
    fun `composer marks clearly different words as wrong or missing`() {
        val result = composer.evaluate(
            targetText = "bike",
            recognizedText = "cat"
        )

        assertEquals(listOf(TrainingWordStatus.WRONG_OR_MISSING), result.wordFeedback.map { it.status })
        assertEquals("/baɪk/", result.pronunciationHints.single().targetIpa)
    }
}
