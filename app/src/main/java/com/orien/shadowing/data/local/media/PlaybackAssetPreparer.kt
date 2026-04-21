package com.orien.shadowing.data.local.media

import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.orien.shadowing.data.local.moonshine.AudioUtils
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

object PlaybackAssetPreparer {
    data class PreparedAssets(
        val primaryMediaFile: File,
        val fallbackAudioFile: File?,
        val usedCompatVideo: Boolean
    )

    private data class TrackInfo(
        val videoTrackIndex: Int,
        val videoFormat: MediaFormat?,
        val videoMime: String?,
        val audioTrackIndex: Int,
        val audioFormat: MediaFormat?,
        val audioMime: String?
    ) {
        val hasVideoTrack: Boolean get() = videoTrackIndex >= 0 && videoFormat != null
        val hasAudioTrack: Boolean get() = audioTrackIndex >= 0 && audioFormat != null
    }

    private data class PendingDecoderFrame(
        val bufferIndex: Int,
        val info: MediaCodec.BufferInfo
    )

    private const val TAG = "PlaybackAssetPreparer"
    const val COMPAT_VIDEO_FILE_NAME = "compat_video.mp4"
    const val FALLBACK_AUDIO_FILE_NAME = "fallback_audio.wav"

    private const val OUTPUT_VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val OUTPUT_AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val DEFAULT_FRAME_RATE = 24
    private const val DEFAULT_AUDIO_BUFFER_SIZE = 256 * 1024
    private const val MIN_VIDEO_BITRATE = 750_000
    private const val MAX_VIDEO_BITRATE = 5_000_000
    private const val I_FRAME_INTERVAL_SECONDS = 2
    // The current on-device compat transcode path copies YUV planes in Kotlin and is only
    // practical for short clips. Longer videos should fall back to audio-only compatibility.
    private const val MAX_COMPAT_VIDEO_TRANSCODE_DURATION_MS = 20_000L
    private const val MAX_VIDEO_TRANSCODE_STALL_MS = 15_000L
    // Long videos already pay the ASR decode cost during import. Building a second full-length
    // fallback WAV during import makes the workflow disproportionately slow, so keep it eager
    // only for short clips and let playback fall back lazily for longer sources.
    private const val MAX_EAGER_FALLBACK_AUDIO_DURATION_MS = 120_000L
    private val COMPATIBLE_MP4_BRANDS = setOf("isom", "iso2", "avc1", "mp41", "mp42")
    private val COMPATIBLE_MP4_EXTENSIONS = setOf("mp4", "m4v")

    fun prepare(materialDir: File, sourceMediaFile: File, existingFallbackAudio: File? = null): PreparedAssets {
        val trackInfo = inspectTracks(sourceMediaFile)
        val durationMs = AudioUtils.resolveMediaDurationMs(sourceMediaFile.absolutePath)
        Log.i(
            TAG,
            "Preparing playback assets for ${sourceMediaFile.name}: " +
                "hasVideo=${trackInfo?.hasVideoTrack == true}, hasAudio=${trackInfo?.hasAudioTrack == true}, " +
                "durationMs=${durationMs ?: -1L}"
        )
        val fallbackAudioFile = existingFallbackAudio?.takeIf(File::exists)
            ?: when {
                trackInfo?.hasVideoTrack != true -> {
                    if (trackInfo?.hasAudioTrack == true) {
                        Log.i(
                            TAG,
                            "Skipping eager fallback audio for ${sourceMediaFile.name}: " +
                                "audio-only sources do not need a pre-generated fallback track."
                        )
                    }
                    null
                }

                trackInfo.hasAudioTrack != true -> null

                durationMs != null && durationMs > MAX_EAGER_FALLBACK_AUDIO_DURATION_MS -> {
                    Log.i(
                        TAG,
                        "Skipping eager fallback audio for ${sourceMediaFile.name}: " +
                            "duration ${durationMs}ms exceeds import-time limit " +
                            "of ${MAX_EAGER_FALLBACK_AUDIO_DURATION_MS}ms."
                    )
                    null
                }

                else -> buildFallbackAudio(materialDir, sourceMediaFile)
            }

        if (trackInfo?.hasVideoTrack != true) {
            return PreparedAssets(
                primaryMediaFile = sourceMediaFile,
                fallbackAudioFile = fallbackAudioFile,
                usedCompatVideo = false
            )
        }

        if (isAlreadyCompatibleVideo(sourceMediaFile, trackInfo)) {
            Log.i(TAG, "Skipping compat transcode for ${sourceMediaFile.name}: source video is already compatible.")
            return PreparedAssets(
                primaryMediaFile = sourceMediaFile,
                fallbackAudioFile = fallbackAudioFile,
                usedCompatVideo = false
            )
        }

        if (
            durationMs == null ||
            durationMs > MAX_COMPAT_VIDEO_TRANSCODE_DURATION_MS
        ) {
            Log.i(
                TAG,
                "Skipping compat transcode for ${sourceMediaFile.name}: " +
                    "duration ${durationMs ?: -1L}ms exceeds safe on-device limit " +
                    "of ${MAX_COMPAT_VIDEO_TRANSCODE_DURATION_MS}ms."
            )
            return PreparedAssets(
                primaryMediaFile = sourceMediaFile,
                fallbackAudioFile = fallbackAudioFile,
                usedCompatVideo = false
            )
        }

        val compatVideoFile = File(materialDir, COMPAT_VIDEO_FILE_NAME)
        val compatCreated = runCatching {
            var created = false
            val elapsedMs = measureTimeMillis {
                created = transcodeVideoToCompatibleMp4(
                    inputFile = sourceMediaFile,
                    outputFile = compatVideoFile,
                    trackInfo = trackInfo
                )
            }
            Log.i(
                TAG,
                "Compat transcode finished for ${sourceMediaFile.name}: success=$created, elapsedMs=$elapsedMs"
            )
            created
        }.getOrElse { error ->
            Log.w(TAG, "Unable to build a compatible MP4 for ${sourceMediaFile.name}.", error)
            false
        }

        if (!compatCreated) {
            compatVideoFile.delete()
            return PreparedAssets(
                primaryMediaFile = sourceMediaFile,
                fallbackAudioFile = fallbackAudioFile,
                usedCompatVideo = false
            )
        }

        if (
            sourceMediaFile.absolutePath != compatVideoFile.absolutePath &&
            sourceMediaFile.exists() &&
            sourceMediaFile.name != COMPAT_VIDEO_FILE_NAME
        ) {
            sourceMediaFile.delete()
        }

        return PreparedAssets(
            primaryMediaFile = compatVideoFile,
            fallbackAudioFile = fallbackAudioFile,
            usedCompatVideo = true
        )
    }

    private fun buildFallbackAudio(materialDir: File, sourceMediaFile: File): File? {
        val fallbackAudioFile = File(materialDir, FALLBACK_AUDIO_FILE_NAME)
        if (fallbackAudioFile.exists()) {
            return fallbackAudioFile
        }

        return runCatching {
            val elapsedMs = measureTimeMillis {
                val pcmFloat = AudioUtils.readAudioAsFloat(
                    filePath = sourceMediaFile.absolutePath,
                    targetSampleRate = AudioUtils.TARGET_SAMPLE_RATE
                )
                AudioUtils.writeMono16BitWav(
                    output = fallbackAudioFile,
                    samples = pcmFloat,
                    sampleRate = AudioUtils.TARGET_SAMPLE_RATE
                )
            }
            Log.i(
                TAG,
                "Built fallback audio for ${sourceMediaFile.name}: output=${fallbackAudioFile.name}, elapsedMs=$elapsedMs"
            )
            fallbackAudioFile
        }.getOrElse { error ->
            Log.w(TAG, "Unable to build fallback audio for ${sourceMediaFile.name}.", error)
            fallbackAudioFile.delete()
            null
        }
    }

    private fun inspectTracks(file: File): TrackInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            var videoMime: String? = null
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var audioMime: String? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME)
                when {
                    videoTrackIndex < 0 && mime?.startsWith("video/") == true -> {
                        videoTrackIndex = index
                        videoFormat = format
                        videoMime = mime
                    }

                    audioTrackIndex < 0 && mime?.startsWith("audio/") == true -> {
                        audioTrackIndex = index
                        audioFormat = format
                        audioMime = mime
                    }
                }
            }

            TrackInfo(
                videoTrackIndex = videoTrackIndex,
                videoFormat = videoFormat,
                videoMime = videoMime,
                audioTrackIndex = audioTrackIndex,
                audioFormat = audioFormat,
                audioMime = audioMime
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun isAlreadyCompatibleVideo(file: File, trackInfo: TrackInfo): Boolean {
        val extension = file.extension.lowercase()
        if (extension !in COMPATIBLE_MP4_EXTENSIONS) {
            return false
        }
        if (!trackInfo.videoMime.equals(OUTPUT_VIDEO_MIME, ignoreCase = true)) {
            return false
        }
        if (
            trackInfo.audioMime != null &&
            !trackInfo.audioMime.equals(OUTPUT_AUDIO_MIME, ignoreCase = true)
        ) {
            return false
        }

        val majorBrand = readMajorBrand(file)
        return majorBrand == null || majorBrand in COMPATIBLE_MP4_BRANDS
    }

    private fun readMajorBrand(file: File): String? {
        return try {
            val header = ByteArray(16)
            file.inputStream().use { input ->
                if (input.read(header) < header.size) {
                    return null
                }
                if (
                    header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte()
                ) {
                    return String(header, 8, 4, Charsets.US_ASCII).trim()
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun transcodeVideoToCompatibleMp4(
        inputFile: File,
        outputFile: File,
        trackInfo: TrackInfo
    ): Boolean {
        val inputVideoFormat = trackInfo.videoFormat ?: return false
        val inputVideoMime = trackInfo.videoMime ?: return false
        if (!isDecoderAvailable(inputVideoMime)) {
            return false
        }
        if (!isEncoderAvailable(OUTPUT_VIDEO_MIME)) {
            return false
        }
        if (trackInfo.audioMime != null && !trackInfo.audioMime.equals(OUTPUT_AUDIO_MIME, ignoreCase = true)) {
            return false
        }

        val width = runCatching { inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull()
            ?: return false
        val height = runCatching { inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
            ?: return false
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) {
            return false
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val videoExtractor = MediaExtractor()
        var decoderRef: MediaCodec? = null
        var encoderRef: MediaCodec? = null
        var muxerRef: MediaMuxer? = null

        return try {
            videoExtractor.setDataSource(inputFile.absolutePath)
            videoExtractor.selectTrack(trackInfo.videoTrackIndex)

            val decoderInputFormat = videoExtractor.getTrackFormat(trackInfo.videoTrackIndex).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
            }
            val decoder = MediaCodec.createDecoderByType(inputVideoMime).apply {
                configure(decoderInputFormat, null, null, 0)
                start()
            }
            decoderRef = decoder

            val encoderFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, resolveOutputBitrate(inputVideoFormat, width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, resolveFrameRate(inputVideoFormat, inputFile))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            val encoder = MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME).apply {
                configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            encoderRef = encoder

            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxerRef = muxer
            resolveRotationDegrees(inputFile)?.takeIf { it != 0 }?.let(muxer::setOrientationHint)

            var muxerStarted = false
            var outputVideoTrack = -1
            var outputAudioTrack = -1

            var extractorInputDone = false
            var decoderDone = false
            var encoderDone = false
            var pendingDecoderFrame: PendingDecoderFrame? = null
            var lastProgressAtMs = System.currentTimeMillis()

            val decoderBufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()

            while (!encoderDone) {
                var madeProgress = false

                while (!extractorInputDone) {
                    val decoderInputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (decoderInputIndex < 0) {
                        break
                    }

                    val decoderInputBuffer = decoder.getInputBuffer(decoderInputIndex) ?: break
                    decoderInputBuffer.clear()
                    val sampleSize = videoExtractor.readSampleData(decoderInputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            decoderInputIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        extractorInputDone = true
                    } else {
                        decoder.queueInputBuffer(
                            decoderInputIndex,
                            0,
                            sampleSize,
                            max(0L, videoExtractor.sampleTime),
                            videoExtractor.sampleFlags
                        )
                        videoExtractor.advance()
                    }
                    madeProgress = true
                }

                while (drainVideoEncoder(
                        encoder = encoder,
                        muxer = muxer,
                        encoderBufferInfo = encoderBufferInfo,
                        onMuxerStart = { outputFormat ->
                            outputVideoTrack = muxer.addTrack(outputFormat)
                            if (trackInfo.audioFormat != null) {
                                outputAudioTrack = muxer.addTrack(trackInfo.audioFormat)
                            }
                            muxer.start()
                            muxerStarted = true
                        },
                        videoTrackIndexProvider = { outputVideoTrack },
                        muxerStartedProvider = { muxerStarted },
                        onEncoderDone = { encoderDone = true }
                    )
                ) {
                    madeProgress = true
                }

                if (!decoderDone && pendingDecoderFrame == null) {
                    when (val decoderStatus = decoder.dequeueOutputBuffer(decoderBufferInfo, DEQUEUE_TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> madeProgress = true
                        else -> if (decoderStatus >= 0) {
                            val copiedInfo = MediaCodec.BufferInfo().apply {
                                set(
                                    decoderBufferInfo.offset,
                                    decoderBufferInfo.size,
                                    decoderBufferInfo.presentationTimeUs,
                                    decoderBufferInfo.flags
                                )
                            }
                            pendingDecoderFrame = PendingDecoderFrame(
                                bufferIndex = decoderStatus,
                                info = copiedInfo
                            )
                            madeProgress = true
                        }
                    }
                }

                val pendingFrame = pendingDecoderFrame
                if (pendingFrame != null) {
                    val encoderInputIndex = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (encoderInputIndex >= 0) {
                        val flags = pendingFrame.info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        if (pendingFrame.info.size > 0) {
                            val decodedImage = decoder.getOutputImage(pendingFrame.bufferIndex)
                            val encoderInputImage = encoder.getInputImage(encoderInputIndex)
                            if (decodedImage == null || encoderInputImage == null) {
                                decodedImage?.close()
                                encoderInputImage?.close()
                                return false
                            }
                            val encodedSize = copyImage(decodedImage, encoderInputImage)
                            decodedImage.close()
                            encoderInputImage.close()
                            encoder.queueInputBuffer(
                                encoderInputIndex,
                                0,
                                encodedSize,
                                max(0L, pendingFrame.info.presentationTimeUs),
                                flags
                            )
                        } else if ((pendingFrame.info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoder.queueInputBuffer(
                                encoderInputIndex,
                                0,
                                0,
                                max(0L, pendingFrame.info.presentationTimeUs),
                                flags
                            )
                        } else {
                            encoder.queueInputBuffer(encoderInputIndex, 0, 0, 0L, 0)
                        }

                        decoder.releaseOutputBuffer(pendingFrame.bufferIndex, false)
                        pendingDecoderFrame = null
                        if ((pendingFrame.info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true
                        }
                        madeProgress = true
                    }
                }

                while (drainVideoEncoder(
                        encoder = encoder,
                        muxer = muxer,
                        encoderBufferInfo = encoderBufferInfo,
                        onMuxerStart = { outputFormat ->
                            outputVideoTrack = muxer.addTrack(outputFormat)
                            if (trackInfo.audioFormat != null) {
                                outputAudioTrack = muxer.addTrack(trackInfo.audioFormat)
                            }
                            muxer.start()
                            muxerStarted = true
                        },
                        videoTrackIndexProvider = { outputVideoTrack },
                        muxerStartedProvider = { muxerStarted },
                        onEncoderDone = { encoderDone = true }
                    )
                ) {
                    madeProgress = true
                }

                if (madeProgress) {
                    lastProgressAtMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastProgressAtMs > MAX_VIDEO_TRANSCODE_STALL_MS) {
                    throw IllegalStateException(
                        "Compat video transcode stalled for ${inputFile.name}. " +
                            "No decoder/encoder progress was observed for ${MAX_VIDEO_TRANSCODE_STALL_MS}ms."
                    )
                } else {
                    Thread.yield()
                }
            }

            if (!muxerStarted || outputVideoTrack < 0) {
                return false
            }

            if (trackInfo.audioTrackIndex >= 0 && outputAudioTrack >= 0) {
                copyAudioTrack(
                    inputFile = inputFile,
                    audioTrackIndex = trackInfo.audioTrackIndex,
                    muxer = muxer,
                    outputTrackIndex = outputAudioTrack
                )
            }

            true
        } catch (error: Throwable) {
            Log.w(TAG, "Compatible video generation failed for ${inputFile.name}.", error)
            false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { decoderRef?.stop() }
            runCatching { decoderRef?.release() }
            runCatching { encoderRef?.stop() }
            runCatching { encoderRef?.release() }
            runCatching { muxerRef?.stop() }
            runCatching { muxerRef?.release() }
            if (!outputFile.exists() || outputFile.length() == 0L) {
                outputFile.delete()
            }
        }
    }

    private fun drainVideoEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        encoderBufferInfo: MediaCodec.BufferInfo,
        onMuxerStart: (MediaFormat) -> Unit,
        videoTrackIndexProvider: () -> Int,
        muxerStartedProvider: () -> Boolean,
        onEncoderDone: () -> Unit
    ): Boolean {
        return when (val encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, DEQUEUE_TIMEOUT_US)) {
            MediaCodec.INFO_TRY_AGAIN_LATER -> false
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                onMuxerStart(encoder.outputFormat)
                true
            }

            else -> if (encoderStatus >= 0) {
                val endOfStream = (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                if (encoderBufferInfo.size > 0) {
                    if (!muxerStartedProvider()) {
                        return false
                    }
                    val outputVideoTrack = videoTrackIndexProvider()
                    if (outputVideoTrack < 0) {
                        return false
                    }
                    val encodedData = encoder.getOutputBuffer(encoderStatus) ?: return false
                    encodedData.position(encoderBufferInfo.offset)
                    encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                    muxer.writeSampleData(outputVideoTrack, encodedData, encoderBufferInfo)
                }
                encoder.releaseOutputBuffer(encoderStatus, false)
                if (endOfStream) {
                    onEncoderDone()
                }
                true
            } else {
                false
            }
        }
    }

    private fun copyAudioTrack(
        inputFile: File,
        audioTrackIndex: Int,
        muxer: MediaMuxer,
        outputTrackIndex: Int
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            extractor.selectTrack(audioTrackIndex)
            val audioFormat = extractor.getTrackFormat(audioTrackIndex)
            val bufferSize = runCatching {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            }.getOrDefault(DEFAULT_AUDIO_BUFFER_SIZE).coerceAtLeast(DEFAULT_AUDIO_BUFFER_SIZE)
            val sampleBuffer = ByteBuffer.allocateDirect(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                sampleBuffer.clear()
                val sampleSize = extractor.readSampleData(sampleBuffer, 0)
                if (sampleSize < 0) {
                    break
                }
                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = max(0L, extractor.sampleTime)
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrackIndex, sampleBuffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun copyImage(source: Image, target: Image): Int {
        val sourceCrop = source.cropRect ?: Rect(0, 0, source.width, source.height)
        val targetCrop = target.cropRect ?: Rect(0, 0, target.width, target.height)
        val imageWidth = min(sourceCrop.width(), targetCrop.width())
        val imageHeight = min(sourceCrop.height(), targetCrop.height())
        require(imageWidth > 0 && imageHeight > 0) { "Invalid video frame size." }

        for (planeIndex in 0 until 3) {
            val sourcePlane = source.planes[planeIndex]
            val targetPlane = target.planes[planeIndex]
            val sourceBuffer = sourcePlane.buffer
            val targetBuffer = targetPlane.buffer
            val sourceRowStride = sourcePlane.rowStride
            val sourcePixelStride = sourcePlane.pixelStride
            val targetRowStride = targetPlane.rowStride
            val targetPixelStride = targetPlane.pixelStride
            val sourceBase = sourceBuffer.position()
            val targetBase = targetBuffer.position()

            val planeWidth = if (planeIndex == 0) {
                imageWidth
            } else {
                (imageWidth + 1) / 2
            }
            val planeHeight = if (planeIndex == 0) {
                imageHeight
            } else {
                (imageHeight + 1) / 2
            }

            val sourceOffsetX = if (planeIndex == 0) sourceCrop.left else sourceCrop.left / 2
            val sourceOffsetY = if (planeIndex == 0) sourceCrop.top else sourceCrop.top / 2
            val targetOffsetX = if (planeIndex == 0) targetCrop.left else targetCrop.left / 2
            val targetOffsetY = if (planeIndex == 0) targetCrop.top else targetCrop.top / 2

            for (row in 0 until planeHeight) {
                for (col in 0 until planeWidth) {
                    val sourceIndex =
                        sourceBase + (sourceOffsetY + row) * sourceRowStride +
                            (sourceOffsetX + col) * sourcePixelStride
                    val targetIndex =
                        targetBase + (targetOffsetY + row) * targetRowStride +
                            (targetOffsetX + col) * targetPixelStride
                    targetBuffer.put(targetIndex, sourceBuffer.get(sourceIndex))
                }
            }
        }

        return imageWidth * imageHeight * 3 / 2
    }

    private fun resolveFrameRate(inputFormat: MediaFormat, inputFile: File): Int {
        val frameRate = runCatching {
            inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
        }.getOrNull()
        if (frameRate != null && frameRate > 0) {
            return frameRate
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()
                ?.toInt()
                ?.takeIf { it > 0 }
                ?: DEFAULT_FRAME_RATE
        } catch (_: Throwable) {
            DEFAULT_FRAME_RATE
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun resolveOutputBitrate(inputFormat: MediaFormat, width: Int, height: Int): Int {
        val sourceBitrate = runCatching {
            inputFormat.getInteger(MediaFormat.KEY_BIT_RATE)
        }.getOrNull()
        if (sourceBitrate != null && sourceBitrate > 0) {
            return sourceBitrate.coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
        }

        val estimated = width * height * 4
        return estimated.coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
    }

    private fun resolveRotationDegrees(inputFile: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(inputFile.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun isDecoderAvailable(mime: String): Boolean = isCodecAvailable(mime, encoder = false)

    private fun isEncoderAvailable(mime: String): Boolean = isCodecAvailable(mime, encoder = true)

    private fun isCodecAvailable(mime: String, encoder: Boolean): Boolean {
        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .any { codecInfo ->
                    codecInfo.isEncoder == encoder &&
                        codecInfo.supportedTypes.any { supportedType ->
                            supportedType.equals(mime, ignoreCase = true)
                        }
                }
        }.getOrDefault(false)
    }
}
