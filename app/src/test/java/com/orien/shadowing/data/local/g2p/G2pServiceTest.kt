package com.orien.shadowing.data.local.g2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class G2pServiceTest {
    private val dictionaryEntries = mapOf(
        "i" to listOf("AY"),
        "ride" to listOf("R", "AY", "D"),
        "a" to listOf("AH"),
        "bike" to listOf("B", "AY", "K"),
        "a's" to listOf("EY", "Z"),
        "twenty" to listOf("T", "W", "EH", "N", "T", "IY"),
        "one" to listOf("W", "AH", "N")
    )

    private val dictionary = object : G2pDictionary {
        override fun lookup(word: String): List<String>? = dictionaryEntries[word]

        override fun isLoaded(): Boolean = true
    }

    private val service = G2pService(dictionary)

    @Test
    fun `textToPhonemes normalizes text and skips unknown words`() {
        val phonemes = service.textToPhonemes("I ride a Bike. mystery")

        assertEquals(
            listOf("AY", "R", "AY", "D", "AH", "B", "AY", "K"),
            phonemes
        )
    }

    @Test
    fun `textToWordPhonemes keeps missing words and splits hyphenated tokens`() {
        val results = service.textToWordPhonemes("Bike. A’s twenty-one mystery")

        assertEquals(
            listOf(
                WordPhonemeResult("Bike.", "bike", listOf("B", "AY", "K"), true),
                WordPhonemeResult("A’s", "a's", listOf("EY", "Z"), true),
                WordPhonemeResult("twenty", "twenty", listOf("T", "W", "EH", "N", "T", "IY"), true),
                WordPhonemeResult("one", "one", listOf("W", "AH", "N"), true),
                WordPhonemeResult("mystery", "mystery", null, false)
            ),
            results
        )
    }

    @Test
    fun `normalizer handles punctuation apostrophes hyphens and empty tokens`() {
        assertEquals("bike", G2pTextNormalizer.normalizeWordForLookup("Bike."))
        assertEquals("bike", G2pTextNormalizer.normalizeWordForLookup("“Bike”"))
        assertEquals("a's", G2pTextNormalizer.normalizeWordForLookup("A’s"))
        assertEquals(listOf("twenty", "one"), G2pTextNormalizer.splitTokenForLookup("Twenty-one"))
        assertNull(G2pTextNormalizer.normalizeWordForLookup("..."))
    }
}
