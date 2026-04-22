package com.orien.shadowing.data.local.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import com.orien.shadowing.data.local.moonshine.AudioUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
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

    private data class DevicePlaybackSupport(
        val videoTrackSupported: Boolean,
        val audioTrackSupported: Boolean
    ) {
        val allTracksSupported: Boolean get() = videoTrackSupported && audioTrackSupported
    }

    private data class PendingAudioBuffer(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val sampleRate: Int,
        val channelCount: Int,
        val endOfStream: Boolean,
        var offset: Int = 0
    )

    private const val TAG = "PlaybackAssetPreparer"
    const val COMPAT_VIDEO_FILE_NAME = "compat_video.mp4"
    const val FALLBACK_AUDIO_FILE_NAME = "fallback_audio.wav"
    private const val TEMP_COMPAT_VIDEO_TRACK_FILE_NAME = "compat_video_track.mp4"
    private const val TEMP_COMPAT_AUDIO_TRACK_FILE_NAME = "compat_audio_track.m4a"

    private const val OUTPUT_VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val OUTPUT_AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val DEFAULT_FRAME_RATE = 24
    private const val DEFAULT_AUDIO_BUFFER_SIZE = 256 * 1024
    private const val MIN_VIDEO_BITRATE = 750_000
    private const val MAX_VIDEO_BITRATE = 5_000_000
    private const val MIN_AUDIO_BITRATE = 64_000
    private const val MAX_AUDIO_BITRATE = 256_000
    private const val I_FRAME_INTERVAL_SECONDS = 2
    private const val MAX_VIDEO_TRANSCODE_STALL_MS = 30_000L

    fun prepare(materialDir: File, sourceMediaFile: File, existingFallbackAudio: File? = null): PreparedAssets {
        val trackInfo = inspectTracks(sourceMediaFile)
        val durationMs = AudioUtils.resolveMediaDurationMs(sourceMediaFile.absolutePath)
        Log.i(
            TAG,
            "Preparing playback assets for ${sourceMediaFile.name}: " +
                "hasVideo=${trackInfo?.hasVideoTrack == true}, hasAudio=${trackInfo?.hasAudioTrack == true}, " +
                "durationMs=${durationMs ?: -1L}"
        )
        val existingFallbackAudioFile = existingFallbackAudio?.takeIf(File::exists)

        if (trackInfo?.hasVideoTrack != true) {
            if (trackInfo?.hasAudioTrack == true && existingFallbackAudioFile == null) {
                Log.i(
                    TAG,
                    "Skipping eager fallback audio for ${sourceMediaFile.name}: " +
                        "audio-only sources do not need a pre-generated fallback track."
                )
            }
            return PreparedAssets(
                primaryMediaFile = sourceMediaFile,
                fallbackAudioFile = existingFallbackAudioFile,
                usedCompatVideo = false
            )
        }

        val videoTrackInfo = checkNotNull(trackInfo)
        val playbackSupport = resolveDevicePlaybackSupport(videoTrackInfo)
        Log.i(
            TAG,
            "Direct playback support for ${sourceMediaFile.name}: " +
                "video=${playbackSupport.videoTrackSupported}, " +
                "audio=${playbackSupport.audioTrackSupported}, " +
                "videoMime=${videoTrackInfo.videoMime ?: "none"}, " +
                "audioMime=${videoTrackInfo.audioMime ?: "none"}"
        )

        val fallbackAudioFile = existingFallbackAudioFile
            ?: when {
                playbackSupport.allTracksSupported -> {
                    Log.i(
                        TAG,
                        "Skipping eager fallback audio for ${sourceMediaFile.name}: " +
                            "source media can play directly on this device."
                    )
                    null
                }

                !videoTrackInfo.hasAudioTrack -> null

                durationMs != null &&
                    durationMs > MediaTranscodeLimits.IMPORT_EAGER_FALLBACK_AUDIO_LIMIT_MS -> {
                    Log.i(
                        TAG,
                        "Skipping eager fallback audio for ${sourceMediaFile.name}: " +
                            "duration ${durationMs}ms exceeds import-time limit " +
                            "of ${MediaTranscodeLimits.IMPORT_EAGER_FALLBACK_AUDIO_LIMIT_MS}ms."
                    )
                    null
                }

                else -> buildFallbackAudio(materialDir, sourceMediaFile)
            }

        if (playbackSupport.allTracksSupported) {
            Log.i(
                TAG,
                "Skipping compat transcode for ${sourceMediaFile.name}: " +
                    "source video can play directly on this device."
            )
            return PreparedAssets(
                primaryMediaFile = sourceMediaFile,
                fallbackAudioFile = fallbackAudioFile,
                usedCompatVideo = false
            )
        }

        val needsVideoTranscode = !playbackSupport.videoTrackSupported
        val needsAudioTranscode = videoTrackInfo.hasAudioTrack && !playbackSupport.audioTrackSupported

        if (
            needsVideoTranscode &&
            (
                durationMs == null ||
                    durationMs > MediaTranscodeLimits.IMPORT_COMPAT_VIDEO_TRANSCODE_LIMIT_MS
                )
        ) {
            Log.i(
                TAG,
                "Skipping compat transcode for ${sourceMediaFile.name}: " +
                    "duration ${durationMs ?: -1L}ms exceeds safe on-device limit " +
                    "of ${MediaTranscodeLimits.IMPORT_COMPAT_VIDEO_TRANSCODE_LIMIT_MS}ms."
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
                created = buildCompatibleMp4(
                    materialDir = materialDir,
                    sourceMediaFile = sourceMediaFile,
                    outputFile = compatVideoFile,
                    trackInfo = videoTrackInfo,
                    needsVideoTranscode = needsVideoTranscode,
                    needsAudioTranscode = needsAudioTranscode
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

    private fun resolveDevicePlaybackSupport(trackInfo: TrackInfo): DevicePlaybackSupport {
        return DevicePlaybackSupport(
            videoTrackSupported = isTrackPlaybackSupported(trackInfo.videoFormat, trackInfo.videoMime),
            audioTrackSupported =
                !trackInfo.hasAudioTrack || isTrackPlaybackSupported(trackInfo.audioFormat, trackInfo.audioMime)
        )
    }

    private fun isTrackPlaybackSupported(format: MediaFormat?, mime: String?): Boolean {
        if (format == null || mime.isNullOrBlank()) {
            return false
        }

        return runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .findDecoderForFormat(format) != null
        }.getOrDefault(false)
    }

    private fun buildCompatibleMp4(
        materialDir: File,
        sourceMediaFile: File,
        outputFile: File,
        trackInfo: TrackInfo,
        needsVideoTranscode: Boolean,
        needsAudioTranscode: Boolean
    ): Boolean {
        val tempVideoFile = File(materialDir, TEMP_COMPAT_VIDEO_TRACK_FILE_NAME)
        val tempAudioFile = File(materialDir, TEMP_COMPAT_AUDIO_TRACK_FILE_NAME)

        return try {
            val videoSourceFile = if (needsVideoTranscode) {
                if (!transcodeVideoTrackToCompatibleMp4(sourceMediaFile, tempVideoFile, trackInfo)) {
                    return false
                }
                tempVideoFile
            } else {
                sourceMediaFile
            }

            val audioSourceFile = when {
                !trackInfo.hasAudioTrack -> null
                needsAudioTranscode -> {
                    if (!transcodeAudioTrackToCompatibleM4a(sourceMediaFile, tempAudioFile, trackInfo)) {
                        return false
                    }
                    tempAudioFile
                }

                else -> sourceMediaFile
            }

            muxTracksToCompatibleMp4(
                outputFile = outputFile,
                videoSourceFile = videoSourceFile,
                videoTrackIndex = if (needsVideoTranscode) 0 else trackInfo.videoTrackIndex,
                audioSourceFile = audioSourceFile,
                audioTrackIndex = when {
                    audioSourceFile == null -> -1
                    needsAudioTranscode -> 0
                    else -> trackInfo.audioTrackIndex
                },
                orientationDegrees = resolveRotationDegrees(sourceMediaFile)
            )
        } finally {
            if (tempVideoFile.absolutePath != outputFile.absolutePath) {
                tempVideoFile.delete()
            }
            tempAudioFile.delete()
        }
    }

    private fun transcodeVideoTrackToCompatibleMp4(
        inputFile: File,
        outputFile: File,
        trackInfo: TrackInfo
    ): Boolean {
        val inputVideoFormat = trackInfo.videoFormat ?: return false
        val inputVideoMime = trackInfo.videoMime ?: return false
        if (!isTrackPlaybackSupported(inputVideoFormat, inputVideoMime)) {
            return false
        }
        if (!isEncoderAvailable(OUTPUT_VIDEO_MIME)) {
            return false
        }

        val sourceWidth = runCatching { inputVideoFormat.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull()
            ?: return false
        val sourceHeight = runCatching { inputVideoFormat.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
            ?: return false
        val width = sourceWidth and 1.inv()
        val height = sourceHeight and 1.inv()
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
        var encoderInputSurfaceRef: Surface? = null
        var muxerRef: MediaMuxer? = null

        return try {
            videoExtractor.setDataSource(inputFile.absolutePath)
            videoExtractor.selectTrack(trackInfo.videoTrackIndex)

            val encoderFormat = MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, resolveOutputBitrate(inputVideoFormat, width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, resolveFrameRate(inputVideoFormat, inputFile))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
            }
            val encoder = MediaCodec.createEncoderByType(OUTPUT_VIDEO_MIME)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoderInputSurfaceRef = encoderInputSurface
            encoder.start()
            encoderRef = encoder

            val decoder = MediaCodec.createDecoderByType(inputVideoMime).apply {
                configure(
                    videoExtractor.getTrackFormat(trackInfo.videoTrackIndex),
                    encoderInputSurface,
                    null,
                    0
                )
                start()
            }
            decoderRef = decoder

            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxerRef = muxer
            resolveRotationDegrees(inputFile)?.takeIf { it != 0 }?.let(muxer::setOrientationHint)

            var muxerStarted = false
            var outputVideoTrack = -1

            var extractorInputDone = false
            var decoderOutputDone = false
            var encoderDone = false
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

                while (!decoderOutputDone) {
                    when (val decoderStatus = decoder.dequeueOutputBuffer(decoderBufferInfo, DEQUEUE_TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> madeProgress = true
                        else -> if (decoderStatus >= 0) {
                            val endOfStream =
                                (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val shouldRender = decoderBufferInfo.size > 0
                            decoder.releaseOutputBuffer(decoderStatus, shouldRender)
                            if (endOfStream) {
                                decoderOutputDone = true
                                encoder.signalEndOfInputStream()
                            }
                            madeProgress = true
                        }
                    }
                }

                while (drainVideoEncoder(
                        encoder = encoder,
                        muxer = muxer,
                        encoderBufferInfo = encoderBufferInfo,
                        onMuxerStart = { outputFormat ->
                            outputVideoTrack = muxer.addTrack(outputFormat)
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

            true
        } catch (error: Throwable) {
            Log.w(TAG, "Compatible video generation failed for ${inputFile.name}.", error)
            false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { decoderRef?.stop() }
            runCatching { decoderRef?.release() }
            runCatching { encoderInputSurfaceRef?.release() }
            runCatching { encoderRef?.stop() }
            runCatching { encoderRef?.release() }
            runCatching { muxerRef?.stop() }
            runCatching { muxerRef?.release() }
            if (!outputFile.exists() || outputFile.length() == 0L) {
                outputFile.delete()
            }
        }
    }

    private fun transcodeAudioTrackToCompatibleM4a(
        inputFile: File,
        outputFile: File,
        trackInfo: TrackInfo
    ): Boolean {
        val inputAudioFormat = trackInfo.audioFormat ?: return false
        val inputAudioMime = trackInfo.audioMime ?: return false
        if (!isTrackPlaybackSupported(inputAudioFormat, inputAudioMime) || !isEncoderAvailable(OUTPUT_AUDIO_MIME)) {
            return false
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val extractor = MediaExtractor()
        var decoderRef: MediaCodec? = null
        var encoderRef: MediaCodec? = null
        var muxerRef: MediaMuxer? = null

        return try {
            extractor.setDataSource(inputFile.absolutePath)
            extractor.selectTrack(trackInfo.audioTrackIndex)

            val decoder = MediaCodec.createDecoderByType(inputAudioMime).apply {
                configure(inputAudioFormat, null, null, 0)
                start()
            }
            decoderRef = decoder

            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxerRef = muxer

            var encoder: MediaCodec? = null
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var pcmSampleRate = runCatching {
                inputAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }.getOrNull() ?: 44_100
            var pcmChannelCount = runCatching {
                inputAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }.getOrNull() ?: 2
            var pendingAudioBuffer: PendingAudioBuffer? = null
            var extractorInputDone = false
            var decoderDone = false
            var encoderDone = false
            var muxerStarted = false
            var outputAudioTrack = -1
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
                    val sampleSize = extractor.readSampleData(decoderInputBuffer, 0)
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
                            max(0L, extractor.sampleTime),
                            extractor.sampleFlags
                        )
                        extractor.advance()
                    }
                    madeProgress = true
                }

                if (!decoderDone && pendingAudioBuffer == null) {
                    when (val decoderStatus = decoder.dequeueOutputBuffer(decoderBufferInfo, DEQUEUE_TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val decoderOutputFormat = decoder.outputFormat
                            pcmSampleRate = runCatching {
                                decoderOutputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }.getOrNull() ?: pcmSampleRate
                            pcmChannelCount = runCatching {
                                decoderOutputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }.getOrNull() ?: pcmChannelCount
                            pcmEncoding = runCatching {
                                decoderOutputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }.getOrNull() ?: AudioFormat.ENCODING_PCM_16BIT
                            if (encoder == null) {
                                encoder = createAudioEncoder(
                                    inputAudioFormat = inputAudioFormat,
                                    sampleRate = pcmSampleRate,
                                    channelCount = pcmChannelCount
                                )
                                encoderRef = encoder
                            }
                            madeProgress = true
                        }

                        else -> if (decoderStatus >= 0) {
                            val endOfStream =
                                (decoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            val pcmBytes = if (decoderBufferInfo.size > 0) {
                                val outputBuffer = decoder.getOutputBuffer(decoderStatus) ?: return false
                                extractPcmBytes(
                                    outputBuffer = outputBuffer,
                                    bufferInfo = decoderBufferInfo,
                                    pcmEncoding = pcmEncoding
                                )
                            } else {
                                ByteArray(0)
                            }
                            decoder.releaseOutputBuffer(decoderStatus, false)
                            pendingAudioBuffer = PendingAudioBuffer(
                                data = pcmBytes,
                                presentationTimeUs = max(0L, decoderBufferInfo.presentationTimeUs),
                                sampleRate = pcmSampleRate,
                                channelCount = pcmChannelCount,
                                endOfStream = endOfStream
                            )
                            if (endOfStream) {
                                decoderDone = true
                            }
                            madeProgress = true
                        }
                    }
                }

                val liveEncoder = encoder
                val pendingBuffer = pendingAudioBuffer
                if (liveEncoder != null && pendingBuffer != null) {
                    val encoderInputIndex = liveEncoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (encoderInputIndex >= 0) {
                        val encoderInputBuffer = liveEncoder.getInputBuffer(encoderInputIndex) ?: return false
                        encoderInputBuffer.clear()
                        val bytesPerFrame = pendingBuffer.channelCount.coerceAtLeast(1) * 2
                        val bytesRemaining = pendingBuffer.data.size - pendingBuffer.offset
                        val chunkSize = when {
                            bytesRemaining <= 0 -> 0
                            else -> {
                                val maxChunkSize = minOf(bytesRemaining, encoderInputBuffer.capacity())
                                if (bytesPerFrame <= 0) {
                                    maxChunkSize
                                } else {
                                    val aligned = maxChunkSize - (maxChunkSize % bytesPerFrame)
                                    if (aligned > 0) aligned else minOf(bytesRemaining, maxChunkSize)
                                }
                            }
                        }
                        if (chunkSize > 0) {
                            encoderInputBuffer.put(
                                pendingBuffer.data,
                                pendingBuffer.offset,
                                chunkSize
                            )
                        }
                        val framesQueuedBeforeChunk = if (bytesPerFrame > 0) {
                            pendingBuffer.offset / bytesPerFrame
                        } else {
                            0
                        }
                        val presentationTimeUs =
                            pendingBuffer.presentationTimeUs +
                                (framesQueuedBeforeChunk * 1_000_000L) /
                                pendingBuffer.sampleRate.coerceAtLeast(1)
                        pendingBuffer.offset += chunkSize
                        val finishedBuffer = pendingBuffer.offset >= pendingBuffer.data.size
                        liveEncoder.queueInputBuffer(
                            encoderInputIndex,
                            0,
                            chunkSize,
                            presentationTimeUs,
                            if (finishedBuffer && pendingBuffer.endOfStream) {
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            } else {
                                0
                            }
                        )
                        if (finishedBuffer) {
                            pendingAudioBuffer = null
                        }
                        madeProgress = true
                    }
                }

                if (liveEncoder != null) {
                    while (drainAudioEncoder(
                            encoder = liveEncoder,
                            muxer = muxer,
                            encoderBufferInfo = encoderBufferInfo,
                            onMuxerStart = { outputFormat ->
                                outputAudioTrack = muxer.addTrack(outputFormat)
                                muxer.start()
                                muxerStarted = true
                            },
                            audioTrackIndexProvider = { outputAudioTrack },
                            muxerStartedProvider = { muxerStarted },
                            onEncoderDone = { encoderDone = true }
                        )
                    ) {
                        madeProgress = true
                    }
                }

                if (madeProgress) {
                    lastProgressAtMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastProgressAtMs > MAX_VIDEO_TRANSCODE_STALL_MS) {
                    throw IllegalStateException(
                        "Compat audio transcode stalled for ${inputFile.name}. " +
                            "No decoder/encoder progress was observed for ${MAX_VIDEO_TRANSCODE_STALL_MS}ms."
                    )
                } else {
                    Thread.yield()
                }
            }

            muxerStarted && outputAudioTrack >= 0
        } catch (error: Throwable) {
            Log.w(TAG, "Compatible audio generation failed for ${inputFile.name}.", error)
            false
        } finally {
            runCatching { extractor.release() }
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
                if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    encoder.releaseOutputBuffer(encoderStatus, false)
                    return true
                }
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

    private fun drainAudioEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        encoderBufferInfo: MediaCodec.BufferInfo,
        onMuxerStart: (MediaFormat) -> Unit,
        audioTrackIndexProvider: () -> Int,
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
                if ((encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    encoder.releaseOutputBuffer(encoderStatus, false)
                    return true
                }
                if (encoderBufferInfo.size > 0) {
                    if (!muxerStartedProvider()) {
                        return false
                    }
                    val outputAudioTrack = audioTrackIndexProvider()
                    if (outputAudioTrack < 0) {
                        return false
                    }
                    val encodedData = encoder.getOutputBuffer(encoderStatus) ?: return false
                    encodedData.position(encoderBufferInfo.offset)
                    encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                    muxer.writeSampleData(outputAudioTrack, encodedData, encoderBufferInfo)
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

    private fun createAudioEncoder(
        inputAudioFormat: MediaFormat,
        sampleRate: Int,
        channelCount: Int
    ): MediaCodec {
        require(sampleRate > 0) { "Invalid audio sample rate." }
        require(channelCount > 0) { "Invalid audio channel count." }

        val encoderFormat = MediaFormat.createAudioFormat(
            OUTPUT_AUDIO_MIME,
            sampleRate,
            channelCount
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(
                MediaFormat.KEY_BIT_RATE,
                resolveOutputAudioBitrate(inputAudioFormat, sampleRate, channelCount)
            )
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, DEFAULT_AUDIO_BUFFER_SIZE)
        }

        return MediaCodec.createEncoderByType(OUTPUT_AUDIO_MIME).apply {
            configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun extractPcmBytes(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        pcmEncoding: Int
    ): ByteArray {
        val slice = outputBuffer.duplicate()
        slice.position(bufferInfo.offset)
        slice.limit(bufferInfo.offset + bufferInfo.size)

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT, 0 -> ByteArray(bufferInfo.size).also(slice::get)
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floatBuffer = slice.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val pcmBytes = ByteArray(floatBuffer.remaining() * 2)
                var byteIndex = 0
                while (floatBuffer.hasRemaining()) {
                    val pcmSample = (floatBuffer.get().coerceIn(-1f, 1f) * 32767f).toInt()
                    pcmBytes[byteIndex++] = (pcmSample and 0xFF).toByte()
                    pcmBytes[byteIndex++] = ((pcmSample shr 8) and 0xFF).toByte()
                }
                pcmBytes
            }

            else -> throw IllegalStateException("Unsupported PCM encoding for AAC transcode: $pcmEncoding")
        }
    }

    private fun muxTracksToCompatibleMp4(
        outputFile: File,
        videoSourceFile: File,
        videoTrackIndex: Int,
        audioSourceFile: File?,
        audioTrackIndex: Int,
        orientationDegrees: Int?
    ): Boolean {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var muxerRef: MediaMuxer? = null
        return try {
            videoExtractor.setDataSource(videoSourceFile.absolutePath)
            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)

            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxerRef = muxer
            orientationDegrees?.takeIf { it != 0 }?.let(muxer::setOrientationHint)

            val outputVideoTrack = muxer.addTrack(videoFormat)
            val outputAudioTrack = if (audioSourceFile != null && audioTrackIndex >= 0) {
                val extractor = MediaExtractor()
                extractor.setDataSource(audioSourceFile.absolutePath)
                extractor.selectTrack(audioTrackIndex)
                audioExtractor = extractor
                muxer.addTrack(extractor.getTrackFormat(audioTrackIndex))
            } else {
                -1
            }
            muxer.start()

            copySelectedTrack(
                extractor = videoExtractor,
                trackIndex = videoTrackIndex,
                muxer = muxer,
                outputTrackIndex = outputVideoTrack
            )
            if (audioExtractor != null && outputAudioTrack >= 0) {
                copySelectedTrack(
                    extractor = audioExtractor,
                    trackIndex = audioTrackIndex,
                    muxer = muxer,
                    outputTrackIndex = outputAudioTrack
                )
            }
            true
        } catch (error: Throwable) {
            Log.w(TAG, "Compatible MP4 mux failed for ${outputFile.name}.", error)
            false
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
            runCatching { muxerRef?.stop() }
            runCatching { muxerRef?.release() }
            if (!outputFile.exists() || outputFile.length() == 0L) {
                outputFile.delete()
            }
        }
    }

    private fun copySelectedTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        muxer: MediaMuxer,
        outputTrackIndex: Int
    ) {
        val trackFormat = extractor.getTrackFormat(trackIndex)
        val bufferSize = runCatching {
            trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
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
    }

    private fun resolveOutputAudioBitrate(
        inputAudioFormat: MediaFormat,
        sampleRate: Int,
        channelCount: Int
    ): Int {
        val sourceBitrate = runCatching {
            inputAudioFormat.getInteger(MediaFormat.KEY_BIT_RATE)
        }.getOrNull()
        if (sourceBitrate != null && sourceBitrate > 0) {
            return sourceBitrate.coerceIn(MIN_AUDIO_BITRATE, MAX_AUDIO_BITRATE)
        }

        val estimated = when {
            channelCount <= 1 -> 96_000
            channelCount == 2 -> 128_000
            else -> 192_000
        }
        val sampleRateBias = if (sampleRate >= 48_000) 32_000 else 0
        return (estimated + sampleRateBias).coerceIn(MIN_AUDIO_BITRATE, MAX_AUDIO_BITRATE)
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
