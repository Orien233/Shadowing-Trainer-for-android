package com.orien.shadowing.data.local.g2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetG2pDictionaryTest {

    @Test
    fun `lookup loads dictionary lazily and reuses cache`() {
        var loadCount = 0
        val dictionary = AssetG2pDictionary(
            assetContentLoader = {
                loadCount++
                """
                {
                  "bike": ["B", "AY", "K"],
                  "school": ["S", "K", "UW", "L", "#", "comment"]
                }
                """.trimIndent()
            },
            assetPath = "test-dictionary.json"
        )

        assertFalse(dictionary.isLoaded())

        assertEquals(listOf("B", "AY", "K"), dictionary.lookup("Bike"))
        assertEquals(listOf("S", "K", "UW", "L"), dictionary.lookup("school"))

        assertTrue(dictionary.isLoaded())
        assertEquals(1, loadCount)
    }

    @Test
    fun `lookup throws clear exception when dictionary json is invalid`() {
        val dictionary = AssetG2pDictionary(
            assetContentLoader = { "{invalid-json" },
            assetPath = "broken.json"
        )

        val error = assertThrows(IllegalStateException::class.java) {
            dictionary.lookup("bike")
        }

        assertEquals("Failed to load G2P dictionary asset: broken.json", error.message)
    }
}
