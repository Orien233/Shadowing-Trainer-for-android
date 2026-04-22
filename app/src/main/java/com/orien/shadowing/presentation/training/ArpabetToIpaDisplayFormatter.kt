package com.orien.shadowing.presentation.training

object ArpabetToIpaDisplayFormatter {
    private val tokenMap = mapOf(
        "AA" to "ɑ",
        "AE" to "æ",
        "AH" to "ʌ",
        "AO" to "ɔ",
        "AW" to "aʊ",
        "AX" to "ə",
        "AXR" to "ɚ",
        "AY" to "aɪ",
        "B" to "b",
        "CH" to "tʃ",
        "D" to "d",
        "DH" to "ð",
        "EH" to "ɛ",
        "EL" to "l",
        "EM" to "m",
        "EN" to "n",
        "ER" to "ɝ",
        "EY" to "eɪ",
        "F" to "f",
        "G" to "ɡ",
        "HH" to "h",
        "IH" to "ɪ",
        "IY" to "iː",
        "JH" to "dʒ",
        "K" to "k",
        "L" to "l",
        "M" to "m",
        "N" to "n",
        "NG" to "ŋ",
        "NX" to "n",
        "OW" to "oʊ",
        "OY" to "ɔɪ",
        "P" to "p",
        "Q" to "ʔ",
        "R" to "r",
        "S" to "s",
        "SH" to "ʃ",
        "T" to "t",
        "TH" to "θ",
        "UH" to "ʊ",
        "UW" to "uː",
        "V" to "v",
        "W" to "w",
        "Y" to "j",
        "Z" to "z",
        "ZH" to "ʒ"
    )

    fun toIpa(phonemes: List<String>?): String? {
        if (phonemes.isNullOrEmpty()) {
            return null
        }

        val rendered = phonemes.joinToString(separator = "") { token ->
            tokenMap[normalizeToken(token)] ?: "?"
        }

        return "/${rendered.ifBlank { "?" }}/"
    }

    private fun normalizeToken(token: String): String =
        token.uppercase().replace(Regex("\\d"), "")
}
