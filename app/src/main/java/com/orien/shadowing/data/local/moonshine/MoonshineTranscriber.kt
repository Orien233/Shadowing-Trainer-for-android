package com.orien.shadowing.data.local.moonshine

/**
 * Thin Kotlin wrapper around the Moonshine JNI bridge.
 */
class MoonshineTranscriber {

    companion object {
        init {
            System.loadLibrary("shadowing-moonshine")
        }

        const val ARCH_TINY = 0
        const val ARCH_BASE = 1
        const val ARCH_TINY_STREAMING = 2
        const val ARCH_BASE_STREAMING = 3
        const val ARCH_SMALL_STREAMING = 4
        const val ARCH_MEDIUM_STREAMING = 5
    }

    private var handle: Int = -1
    private var streamHandle: Int = -1

    fun loadFromFiles(modelDir: String, arch: Int) {
        handle = nativeLoadFromFiles(modelDir, arch)
        if (handle < 0) {
            throw RuntimeException("Failed to load Moonshine model from $modelDir")
        }
    }

    fun loadFromMemory(
        encoderData: ByteArray,
        decoderData: ByteArray,
        tokenizerData: ByteArray,
        arch: Int
    ) {
        handle = nativeLoadFromMemory(encoderData, decoderData, tokenizerData, arch)
        if (handle < 0) {
            throw RuntimeException("Failed to load Moonshine model from memory")
        }
    }

    fun transcribe(audioData: FloatArray, sampleRate: Int = 16000): Transcript {
        require(handle >= 0) { "Transcriber not loaded. Call loadFromFiles or loadFromMemory first." }
        return nativeTranscribe(handle, audioData, sampleRate)
    }

    fun isLoaded(): Boolean = handle >= 0

    fun release() {
        if (streamHandle >= 0) {
            nativeFreeStream(handle, streamHandle)
            streamHandle = -1
        }
        if (handle >= 0) {
            nativeFreeTranscriber(handle)
            handle = -1
        }
    }

    @Suppress("deprecation")
    protected fun finalize() {
        release()
    }

    private external fun nativeLoadFromFiles(path: String, modelArch: Int): Int

    private external fun nativeLoadFromMemory(
        encoderData: ByteArray,
        decoderData: ByteArray,
        tokenizerData: ByteArray,
        modelArch: Int
    ): Int

    private external fun nativeFreeTranscriber(handle: Int)

    private external fun nativeTranscribe(
        handle: Int,
        audioData: FloatArray,
        sampleRate: Int
    ): Transcript

    private external fun nativeFreeStream(transcriberHandle: Int, streamHandle: Int)

    data class Transcript(
        val text: String,
        val lines: Array<TranscriptLine>,
        val tokens: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Transcript) return false
            return text == other.text &&
                lines.contentEquals(other.lines) &&
                tokens.contentEquals(other.tokens)
        }

        override fun hashCode(): Int {
            var result = text.hashCode()
            result = 31 * result + lines.contentHashCode()
            result = 31 * result + tokens.contentHashCode()
            return result
        }
    }

    data class TranscriptLine(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long
    )
}
