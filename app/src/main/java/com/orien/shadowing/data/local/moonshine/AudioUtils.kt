package com.orien.shadowing.data.local.moonshine

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max

/**
 * Converts different audio formats into the mono 16kHz float samples Moonshine expects.
 */
object AudioUtils {
    const val TARGET_SAMPLE_RATE = 16000
    private const val MAX_ASR_DURATION_MS = 10 * 60 * 1000L
    private const val MAX_TARGET_SAMPLE_COUNT = TARGET_SAMPLE_RATE * 60 * 10L

    fun readAudioAsFloat(filePath: String, targetSampleRate: Int = TARGET_SAMPLE_RATE): FloatArray {
        val inputFile = File(filePath)
        require(inputFile.exists()) { "Audio file not found: $filePath" }

        return when (inputFile.extension.lowercase()) {
            "wav" -> readWavAsFloat(inputFile, targetSampleRate)
            else -> decodeMediaToFloat(inputFile, targetSampleRate)
        }
    }

    private fun readWavAsFloat(file: File, targetSampleRate: Int): FloatArray {
        FileInputStream(file).use { input ->
            val header = ByteArray(44)
            readFully(input, header)

            val numChannels = header.getChannelCount()
            val sampleRate = header.getSampleRate()
            val bitsPerSample = header.getBitsPerSample()
            val dataSize = header.getDataSize()
            require(numChannels > 0) { "Invalid WAV channel count in ${file.name}" }
            require(sampleRate > 0) { "Invalid WAV sample rate in ${file.name}" }
            require(dataSize >= 0) { "Invalid WAV data size in ${file.name}" }
            ensureSampleCountWithinLimit(
                sampleCount = sampleCountFromBytes(dataSize, bitsPerSample, numChannels),
                sourceRate = sampleRate,
                targetRate = targetSampleRate,
                fileName = file.name
            )

            val pcmData = ByteArray(dataSize)
            readFully(input, pcmData)

            val samples = when (bitsPerSample) {
                16 -> convertInt16ToFloat(pcmData, numChannels)
                32 -> convertFloat32ToFloat(pcmData, numChannels)
                else -> throw UnsupportedOperationException("Unsupported WAV bit depth: $bitsPerSample")
            }

            val normalizedSamples = if (sampleRate != targetSampleRate) {
                resample(samples, sampleRate, targetSampleRate)
            } else {
                samples
            }
            ensureTargetSampleCountWithinLimit(normalizedSamples.size.toLong(), file.name)
            return normalizedSamples
        }
    }

    private fun decodeMediaToFloat(file: File, targetSampleRate: Int): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var codec: MediaCodec? = null
        try {
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(index)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = index
                    inputFormat = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || inputFormat == null) {
                throw IllegalArgumentException("No audio track found in ${file.name}")
            }

            extractor.selectTrack(audioTrackIndex)
            ensureDurationWithinLimit(
                durationMs = resolveDurationMs(file, inputFormat),
                fileName = file.name
            )

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Missing audio mime type in ${file.name}")
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val bufferInfo = MediaCodec.BufferInfo()
            val decodedSamples = FloatAccumulator()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Input buffer unavailable")
                        inputBuffer.clear()

                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }

                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                                ?: throw IllegalStateException("Output buffer unavailable")
                            val decodedChunk =
                                decodeOutputChunk(outputBuffer, bufferInfo, pcmEncoding, channelCount)
                            decodedSamples.append(decodedChunk)
                            ensureSampleCountWithinLimit(
                                sampleCount = decodedSamples.size.toLong(),
                                sourceRate = sampleRate,
                                targetRate = targetSampleRate,
                                fileName = file.name
                            )
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            val monoSamples = decodedSamples.toFloatArray()
            val normalizedSamples = if (sampleRate != targetSampleRate) {
                resample(monoSamples, sampleRate, targetSampleRate)
            } else {
                monoSamples
            }
            ensureTargetSampleCountWithinLimit(normalizedSamples.size.toLong(), file.name)
            return normalizedSamples
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun decodeOutputChunk(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        pcmEncoding: Int,
        channelCount: Int
    ): FloatArray {
        val slice = outputBuffer.duplicate()
        slice.position(bufferInfo.offset)
        slice.limit(bufferInfo.offset + bufferInfo.size)

        val bytes = ByteArray(bufferInfo.size)
        slice.get(bytes)

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> convertFloat32ToFloat(bytes, channelCount)
            AudioFormat.ENCODING_PCM_16BIT, 0 -> convertInt16ToFloat(bytes, channelCount)
            else -> throw UnsupportedOperationException("Unsupported PCM encoding: $pcmEncoding")
        }
    }

    private fun ByteArray.getChannelCount(): Int =
        (this[22].toInt() and 0xFF) or ((this[23].toInt() and 0xFF) shl 8)

    private fun ByteArray.getSampleRate(): Int =
        (this[24].toInt() and 0xFF) or
            ((this[25].toInt() and 0xFF) shl 8) or
            ((this[26].toInt() and 0xFF) shl 16) or
            ((this[27].toInt() and 0xFF) shl 24)

    private fun ByteArray.getBitsPerSample(): Int =
        (this[34].toInt() and 0xFF) or ((this[35].toInt() and 0xFF) shl 8)

    private fun ByteArray.getDataSize(): Int =
        (this[40].toInt() and 0xFF) or
            ((this[41].toInt() and 0xFF) shl 8) or
            ((this[42].toInt() and 0xFF) shl 16) or
            ((this[43].toInt() and 0xFF) shl 24)

    private fun convertInt16ToFloat(pcmData: ByteArray, numChannels: Int): FloatArray {
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val sampleCount = buffer.remaining()
        val samples = FloatArray(sampleCount)
        for (index in 0 until sampleCount) {
            samples[index] = buffer.get().toFloat() / 32768f
        }
        return if (numChannels > 1) stereoToMono(samples, numChannels) else samples
    }

    private fun convertFloat32ToFloat(pcmData: ByteArray, numChannels: Int): FloatArray {
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val sampleCount = buffer.remaining()
        val samples = FloatArray(sampleCount)
        buffer.get(samples)
        return if (numChannels > 1) stereoToMono(samples, numChannels) else samples
    }

    private fun stereoToMono(samples: FloatArray, numChannels: Int): FloatArray {
        val monoLength = samples.size / numChannels
        val mono = FloatArray(monoLength)
        for (frameIndex in 0 until monoLength) {
            var sum = 0f
            for (channelIndex in 0 until numChannels) {
                sum += samples[frameIndex * numChannels + channelIndex]
            }
            mono[frameIndex] = sum / numChannels
        }
        return mono
    }

    fun resample(samples: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate) {
            return samples
        }

        val ratio = sourceRate.toDouble() / targetRate
        val outputLength = (samples.size / ratio).toInt()
        val resampled = FloatArray(outputLength)

        for (index in 0 until outputLength) {
            val sourcePosition = index * ratio
            val sourceIndex = sourcePosition.toInt()
            val fraction = (sourcePosition - sourceIndex).toFloat()

            resampled[index] = when {
                sourceIndex + 1 < samples.size ->
                    samples[sourceIndex] * (1f - fraction) + samples[sourceIndex + 1] * fraction

                sourceIndex < samples.size -> samples[sourceIndex]
                else -> 0f
            }
        }

        return resampled
    }

    private fun resolveDurationMs(file: File, inputFormat: MediaFormat): Long? {
        val formatDurationMs = runCatching {
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L
            } else {
                null
            }
        }.getOrNull()
        if (formatDurationMs != null) {
            return formatDurationMs
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun ensureDurationWithinLimit(durationMs: Long?, fileName: String) {
        if (durationMs != null && durationMs > MAX_ASR_DURATION_MS) {
            throw IllegalArgumentException(
                "$fileName is longer than ${MAX_ASR_DURATION_MS / 60_000} minutes. " +
                    "Split the media into shorter clips before importing."
            )
        }
    }

    private fun ensureSampleCountWithinLimit(
        sampleCount: Long,
        sourceRate: Int,
        targetRate: Int,
        fileName: String
    ) {
        val estimatedTargetSamples = if (sourceRate <= 0) {
            sampleCount
        } else {
            ceil(sampleCount.toDouble() * targetRate / sourceRate).toLong()
        }
        ensureTargetSampleCountWithinLimit(estimatedTargetSamples, fileName)
    }

    private fun ensureTargetSampleCountWithinLimit(sampleCount: Long, fileName: String) {
        if (sampleCount > MAX_TARGET_SAMPLE_COUNT) {
            throw IllegalArgumentException(
                "$fileName is too large for on-device analysis. " +
                    "Split the media into clips under ${MAX_ASR_DURATION_MS / 60_000} minutes."
            )
        }
    }

    private fun sampleCountFromBytes(dataSize: Int, bitsPerSample: Int, numChannels: Int): Long {
        val bytesPerFrame = max(1, (bitsPerSample / 8) * numChannels)
        return dataSize / bytesPerFrame.toLong()
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val bytesRead = input.read(buffer, offset, buffer.size - offset)
            if (bytesRead < 0) {
                throw IOException("Unexpected end of audio stream.")
            }
            offset += bytesRead
        }
    }

    private class FloatAccumulator(initialCapacity: Int = 0) {
        private var buffer = FloatArray(max(16, initialCapacity))
        var size: Int = 0
            private set

        fun append(values: FloatArray) {
            ensureCapacity(size + values.size)
            values.copyInto(buffer, destinationOffset = size)
            size += values.size
        }

        fun toFloatArray(): FloatArray = buffer.copyOf(size)

        private fun ensureCapacity(requiredCapacity: Int) {
            if (requiredCapacity <= buffer.size) {
                return
            }
            var newCapacity = buffer.size
            while (newCapacity < requiredCapacity) {
                newCapacity *= 2
            }
            buffer = buffer.copyOf(newCapacity)
        }
    }
}
