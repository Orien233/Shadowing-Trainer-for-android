package com.orien.shadowing.data.local.g2p

class G2pService(
    private val dictionary: G2pDictionary
) {
    fun textToPhonemes(text: String): List<String> {
        return textToWordPhonemes(text)
            .mapNotNull { it.phonemes }
            .flatten()
    }

    fun textToWordPhonemes(text: String): List<WordPhonemeResult> {
        return G2pTextNormalizer.tokenizeForLookup(text).map { token ->
            val phonemes = token.normalizedToken?.let(dictionary::lookup)
            WordPhonemeResult(
                originalToken = token.originalToken,
                normalizedToken = token.normalizedToken,
                phonemes = phonemes,
                foundInDictionary = phonemes != null
            )
        }
    }
}
