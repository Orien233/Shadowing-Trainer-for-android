package com.orien.shadowing.data.local.g2p

interface G2pDictionary {
    fun lookup(word: String): List<String>?

    fun isLoaded(): Boolean
}
