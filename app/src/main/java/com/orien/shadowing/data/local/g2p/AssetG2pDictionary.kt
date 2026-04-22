package com.orien.shadowing.data.local.g2p

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.util.Locale

class AssetG2pDictionary(
    private val assetContentLoader: () -> String,
    private val json: Json = DEFAULT_JSON,
    private val assetPath: String = DEFAULT_ASSET_PATH
) : G2pDictionary {

    constructor(
        context: Context,
        json: Json = DEFAULT_JSON,
        assetPath: String = DEFAULT_ASSET_PATH
    ) : this(
        assetContentLoader = {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        },
        json = json,
        assetPath = assetPath
    )

    private val dictionaryEntries = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        loadDictionary()
    }

    override fun lookup(word: String): List<String>? {
        val normalizedWord = word.trim().lowercase(Locale.ROOT)
        if (normalizedWord.isEmpty()) {
            return null
        }
        return dictionaryEntries.value[normalizedWord]
    }

    override fun isLoaded(): Boolean = dictionaryEntries.isInitialized()

    private fun loadDictionary(): Map<String, List<String>> {
        return runCatching {
            val rawEntries = json.decodeFromString<Map<String, List<String>>>(assetContentLoader())
            rawEntries.mapNotNull { (word, pronunciation) ->
                val normalizedWord = word.trim().lowercase(Locale.ROOT)
                val normalizedPronunciation = pronunciation
                    .asSequence()
                    .map(String::trim)
                    .takeWhile { it != INLINE_METADATA_MARKER }
                    .filter(String::isNotEmpty)
                    .toList()

                if (normalizedWord.isEmpty() || normalizedPronunciation.isEmpty()) {
                    null
                } else {
                    normalizedWord to normalizedPronunciation
                }
            }.toMap()
        }.onFailure { error ->
            runCatching {
                Log.e(TAG, "Failed to load G2P dictionary from $assetPath", error)
            }
        }.getOrElse { error ->
            throw IllegalStateException(
                "Failed to load G2P dictionary asset: $assetPath",
                error
            )
        }
    }

    companion object {
        private const val TAG = "AssetG2pDictionary"
        private const val INLINE_METADATA_MARKER = "#"
        const val DEFAULT_ASSET_PATH = "dictionary/base_g2p_dict.json"

        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
