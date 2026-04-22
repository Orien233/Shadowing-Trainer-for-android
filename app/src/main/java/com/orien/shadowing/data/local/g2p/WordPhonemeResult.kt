package com.orien.shadowing.data.local.g2p

data class WordPhonemeResult(
    val originalToken: String,
    val normalizedToken: String?,
    val phonemes: List<String>?,
    val foundInDictionary: Boolean
)
