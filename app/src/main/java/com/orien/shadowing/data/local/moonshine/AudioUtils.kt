package com.orien.shadowing.data.local.moonshine

import android.util.Log
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
import kotlin.math.min

/**
 * Converts different audio formats into the mono 16kHz float samples Moonshine expects.
 */
object AudioUtils {
    const val TARGET_SAMPLE_RATE = 16000
    private const val TAG = "AudioUtils"
    private const val MAX_ASR_DURATION_MINUTES = 30L
    private const val MAX_ASR_DURATION_MS = MAX_ASR_DURATION_MINUTES * 60 * 1000L
    private const val MAX_TARGET_SAMPLE_COUNT =
        TARGET_SAMPLE_RATE * 60L * MAX_ASR_DURATION_MINUTES
    private const val BYTES_PER_FLOAT = 4L
    private const val MIN_RUNTIME_HEADROOM_BYTES = 32L * 1024L * 1024L
    private const val MAX_AUDIO_BUFFER_HEAP_FRACTION = 0.2
    private const val MIN_DEVICE_SAFE_DURATION_MINUTES = 2L
    private const val CODEC_DEQUEUE_TIMEOUT_US = 10_000L
    private const val MAX_DECODE_STALL_MS = 15_000L

    fun readAudioAsFloat(filePath: String, targetSampleRate: Int = TARGET_SAMPLE_RATE): FloatArray {
        val inputFile = File(filePath)
        require(inputFile.exists()) { "Audio file not found: $filePath" }

        return when (inputFile.extension.lowercase()) {
            "wav" -> readWavAsFloat(inputFile, targetSampleRate)
            else -> decodeMediaToFloat(inputFile, targetSampleRate)
        }
    }

    fun decodeMediaSegmentAsFloat(
        filePath: String,
        startTimeMs: Long,
        endTimeMs: Long,
        targetSampleRate: Int = TARGET_SAMPLE_RATE
    ): FloatArray {
        require(endTimeMs > startTimeMs) { "Invalid media segment range: [$startTimeMs, $endTimeMs)" }

        val inputFile = File(filePath)
        require(inputFile.exists()) { "Audio file not found: $filePath" }

        return when (inputFile.extension.lowercase()) {
            // Keep WAV handling simple; raw-media imports are typically video/audio containers.
            "wav" -> {
                val full = readWavAsFloat(inputFile, targetSampleRate)
                val startIndex = ((startTimeMs * targetSampleRate) / 1000L).toInt().coerceAtLeast(0)
                val endIndex = ((endTimeMs * targetSampleRate) / 1000L).toInt().coerceAtMost(full.size)
                if (endIndex <= startIndex) {
                    FloatArray(0)
                } else {
                    full.copyOfRange(startIndex, endIndex)
                }
            }

            else -> decodeMediaRangeToFloat(
                file = inputFile,
                targetSampleRate = targetSampleRate,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                enforceDurationLimit = false
            )
        }
    }

    fun resolveMediaDurationMs(filePath: String): Long? {
        val file = File(filePath)
        if (!file.exists()) {
            return null
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun writeMono16BitWav(
        output: File,
        samples: FloatArray,
        sampleRate: Int = TARGET_SAMPLE_RATE
    ) {
        val bytesPerSample = 2
        val dataSize = samples.size * bytesPerSample
        val byteRate = sampleRate * bytesPerSample
        val blockAlign = bytesPerSample
        val chunkSize = 36 + dataSize

        output.parentFile?.mkdirs()
        output.outputStream().use { out ->
            fun writeIntLE(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
                out.write((value shr 16) and 0xFF)
                out.write((value shr 24) and 0xFF)
            }

            fun writeShortLE(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
            }

            out.write(
                byteArrayOf(
                    'R'.code.toByte(),
                    'I'.code.toByte(),
                    'F'.code.toByte(),
                    'F'.code.toByte()
                )
            )
            writeIntLE(chunkSize)
            out.write(
                byteArrayOf(
                    'W'.code.toByte(),
                    'A'.code.toByte(),
                    'V'.code.toByte(),
                    'E'.code.toByte()
                )
            )
            out.write(
                byteArrayOf(
                    'f'.code.toByte(),
                    'm'.code.toByte(),
                    't'.code.toByte(),
                    ' '.code.toByte()
                )
            )
            writeIntLE(16)
            writeShortLE(1)
            writeShortLE(1)
            writeIntLE(sampleRate)
            writeIntLE(byteRate)
            writeShortLE(blockAlign)
            writeShortLE(16)
            out.write(
                byteArrayOf(
                    'd'.code.toByte(),
                    'a'.code.toByte(),
                    't'.code.toByte(),
                    'a'.code.toByte()
                )
            )
            writeIntLE(dataSize)

            samples.forEach { sample ->
                val clamped = sample.coerceIn(-1f, 1f)
                val pcm = (clamped * 32767f).toInt()
                writeShortLE(pcm)
            }
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
        return decodeMediaRangeToFloat(
            file = file,
            targetSampleRate = targetSampleRate,
            startTimeMs = null,
            endTimeMs = null,
            enforceDurationLimit = true
        )
    }

    private fun decodeMediaRangeToFloat(
        file: File,
        targetSampleRate: Int,
        startTimeMs: Long?,
        endTimeMs: Long?,
        enforceDurationLimit: Boolean
    ): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var codec: MediaCodec? = null
        try {
            val decodeStartMs = System.currentTimeMillis()
            val (audioTrackIndex, inputFormat) = selectAudioTrack(extractor, file)

            extractor.selectTrack(audioTrackIndex)
            if (enforceDurationLimit) {
                ensureDurationWithinLimit(
                    durationMs = resolveDurationMs(file, inputFormat),
                    fileName = file.name
                )
            }

            val rangeStartUs = startTimeMs?.coerceAtLeast(0L)?.times(1000L)
            val rangeEndUs = endTimeMs?.coerceAtLeast(0L)?.times(1000L)
            if (rangeStartUs != null) {
                extractor.seekTo(rangeStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

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
            var lastProgressAtMs = System.currentTimeMillis()

            while (!outputDone) {
                var madeProgress = false
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw IllegalStateException("Input buffer unavailable")
                        inputBuffer.clear()

                        val sampleTimeUs = extractor.sampleTime
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        val isEndOfRange =
                            rangeEndUs != null && sampleTimeUs >= 0L && sampleTimeUs >= rangeEndUs
                        if (sampleSize < 0 || isEndOfRange) {
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
                                max(0L, sampleTimeUs),
                                0
                            )
                            extractor.advance()
                        }
                        madeProgress = true
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_DEQUEUE_TIMEOUT_US)) {
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
                        madeProgress = true
                    }

                    else -> if (outputIndex >= 0) {
                        if (bufferInfo.size > 0) {
                            val presentationTimeUs = bufferInfo.presentationTimeUs
                            val shouldSkipBeforeStart =
                                rangeStartUs != null &&
                                    presentationTimeUs >= 0L &&
                                    presentationTimeUs < rangeStartUs
                            val shouldStopAtEnd =
                                rangeEndUs != null &&
                                    presentationTimeUs >= 0L &&
                                    presentationTimeUs >= rangeEndUs

                            if (!shouldSkipBeforeStart && !shouldStopAtEnd) {
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

                            if (shouldStopAtEnd) {
                                outputDone = true
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        madeProgress = true

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }

                if (madeProgress) {
                    lastProgressAtMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastProgressAtMs > MAX_DECODE_STALL_MS) {
                    val rangeDescription = buildString {
                        append(startTimeMs ?: 0L)
                        append("..")
                        append(endTimeMs?.toString() ?: "end")
                        append("ms")
                    }
                    throw IOException(
                        "Audio decode stalled for ${file.name} at $rangeDescription. " +
                            "The media stream did not produce decoder progress for ${MAX_DECODE_STALL_MS}ms."
                    )
                } else {
                    Thread.yield()
                }
            }

            val monoSamples = decodedSamples.toFloatArray()
            val normalizedSamples = if (sampleRate != targetSampleRate) {
                resample(monoSamples, sampleRate, targetSampleRate)
            } else {
                monoSamples
            }
            if (enforceDurationLimit) {
                ensureTargetSampleCountWithinLimit(normalizedSamples.size.toLong(), file.name)
            }
            Log.i(
                TAG,
                "Decoded audio from ${file.name}: range=${startTimeMs ?: 0L}.." +
                    "${endTimeMs?.toString() ?: "end"}ms, samples=${normalizedSamples.size}, " +
                    "sampleRate=$sampleRate->$targetSampleRate, elapsedMs=${System.currentTimeMillis() - decodeStartMs}"
            )
            return normalizedSamples
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor, file: File): Pair<Int, MediaFormat> {
        for (index in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(index)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return index to trackFormat
            }
        }
        throw IllegalArgumentException("No audio track found in ${file.name}")
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
        val effectiveLimit = resolveEffectiveSampleLimit()
        if (sampleCount > effectiveLimit) {
            val effectiveMinutes = max(1L, effectiveLimit / TARGET_SAMPLE_RATE / 60L)
            throw IllegalArgumentException(
                "$fileName is too large for on-device analysis. " +
                    "Split the media into clips under ${effectiveMinutes} minutes on this device."
            )
        }
    }

    private fun resolveEffectiveSampleLimit(): Long {
        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        if (maxHeapBytes <= 0L) {
            return MAX_TARGET_SAMPLE_COUNT
        }

        val usableHeapBytes = (maxHeapBytes - MIN_RUNTIME_HEADROOM_BYTES).coerceAtLeast(0L)
        if (usableHeapBytes == 0L) {
            return min(
                MAX_TARGET_SAMPLE_COUNT,
                TARGET_SAMPLE_RATE * 60L * MIN_DEVICE_SAFE_DURATION_MINUTES
            )
        }

        // Audio decode/resample/transcribe can temporarily hold multiple large float buffers.
        val heapBudgetForAudio = (usableHeapBytes * MAX_AUDIO_BUFFER_HEAP_FRACTION).toLong()
        val runtimeSafeLimit = (heapBudgetForAudio / BYTES_PER_FLOAT).coerceAtLeast(
            TARGET_SAMPLE_RATE * 60L * MIN_DEVICE_SAFE_DURATION_MINUTES
        )
        return min(MAX_TARGET_SAMPLE_COUNT, runtimeSafeLimit)
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
