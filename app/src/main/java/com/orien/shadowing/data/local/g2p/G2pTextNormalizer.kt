package com.orien.shadowing.data.local.g2p

import java.util.Locale

object G2pTextNormalizer {
    private val whitespaceRegex = Regex("\\s+")
    private val dashSplitRegex = Regex("-+")
    private val apostropheVariantRegex = Regex("[\\u2018\\u2019\\u201B\\u2032\\u02BC]")
    private val dashVariantRegex = Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2212]")

    fun normalizeWordForLookup(word: String): String? {
        val canonicalWord = canonicalize(word)
        val trimmedWord = trimEdgePunctuation(canonicalWord)
        if (trimmedWord.isEmpty()) {
            return null
        }

        val normalizedWord = trimmedWord.lowercase(Locale.ROOT)
        return normalizedWord.takeIf { normalized ->
            normalized.any(Char::isLetterOrDigit)
        }
    }

    fun splitTokenForLookup(token: String): List<String> {
        return splitRawTokenParts(token)
            .mapNotNull(::normalizeWordForLookup)
    }

    internal fun tokenizeForLookup(text: String): List<G2pLookupToken> {
        if (text.isBlank()) {
            return emptyList()
        }

        return text.split(whitespaceRegex)
            .filter(String::isNotBlank)
            .flatMap(::expandToken)
    }

    private fun expandToken(rawToken: String): List<G2pLookupToken> {
        val rawParts = splitRawTokenParts(rawToken)
        if (rawParts.isEmpty()) {
            return listOf(G2pLookupToken(originalToken = rawToken, normalizedToken = null))
        }

        val lookupTokens = rawParts.mapNotNull { rawPart ->
            normalizeWordForLookup(rawPart)?.let { normalizedPart ->
                G2pLookupToken(
                    originalToken = rawPart,
                    normalizedToken = normalizedPart
                )
            }
        }

        if (lookupTokens.isEmpty()) {
            return listOf(G2pLookupToken(originalToken = rawToken, normalizedToken = null))
        }

        return if (lookupTokens.size == 1) {
            listOf(lookupTokens.first().copy(originalToken = rawToken))
        } else {
            lookupTokens
        }
    }

    private fun canonicalize(token: String): String {
        return token
            .replace(apostropheVariantRegex, "'")
            .replace(dashVariantRegex, "-")
    }

    private fun splitRawTokenParts(token: String): List<String> {
        val canonicalToken = canonicalize(token)
        val trimmedToken = trimEdgePunctuation(canonicalToken)
        if (trimmedToken.isEmpty()) {
            return emptyList()
        }

        return dashSplitRegex.split(trimmedToken)
            .filter(String::isNotBlank)
    }

    private fun trimEdgePunctuation(token: String): String {
        var startIndex = 0
        var endIndex = token.length

        while (startIndex < endIndex && !token[startIndex].isLetterOrDigit()) {
            startIndex++
        }
        while (endIndex > startIndex && !token[endIndex - 1].isLetterOrDigit()) {
            endIndex--
        }

        return token.substring(startIndex, endIndex)
    }
}

internal data class G2pLookupToken(
    val originalToken: String,
    val normalizedToken: String?
)
