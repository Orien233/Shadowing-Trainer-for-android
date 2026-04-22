package com.orien.shadowing.data.local

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import com.orien.shadowing.data.local.media.MediaTranscodeLimits
import com.orien.shadowing.data.local.moonshine.AudioUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MediaPlayer-backed media playback helper.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class FallbackAudioAsset(
        val uri: Uri,
        val coversOnlyRequestedSegment: Boolean,
        val preGenerated: Boolean
    )

    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerPrepared = false
    private var videoSurfaceHolder: SurfaceHolder? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val playbackMessages: SharedFlow<String> = _playbackMessages.asSharedFlow()

    private var positionUpdateRunnable: Runnable? = null
    private var segmentStartMs: Long = 0L
    private var segmentEndMs: Long? = null
    private var loopPlayback: Boolean = false
    private var sourceMediaUri: Uri? = null
    private var currentPlaybackUri: Uri? = null
    private var preparedFallbackAudioUri: Uri? = null
    private var retriedFallbackAudioForCurrentMedia: Boolean = false
    private var fallbackRecoveryJob: Job? = null
    private val fallbackAudioCache = mutableMapOf<String, String>()
    private var currentPlaybackSpeed: Float = 1.0f
    private var playbackToken: Long = 0L
    private var activePlaybackToken: Long = 0L

    fun init() {
        if (mediaPlayer != null) {
            return
        }

        mediaPlayer = MediaPlayer().apply {
            setOnPreparedListener { preparedPlayer ->
                if (activePlaybackToken != playbackToken) {
                    return@setOnPreparedListener
                }

                mediaPlayerPrepared = true
                _durationMs.value = preparedPlayer.duration.coerceAtLeast(0).toLong()
                applyMediaPlayerPlaybackSpeed(preparedPlayer)
                preparedPlayer.isLooping = segmentEndMs == null && loopPlayback
                if (segmentStartMs > 0L) {
                    preparedPlayer.seekTo(segmentStartMs.toInt())
                }
                preparedPlayer.start()
                _currentPositionMs.value = segmentStartMs.coerceAtLeast(0L)
                _isPlaying.value = true
                startPositionUpdates()
            }

            setOnCompletionListener {
                if (activePlaybackToken != playbackToken) {
                    return@setOnCompletionListener
                }

                _isPlaying.value = false
                _currentPositionMs.value = _durationMs.value
                stopPositionUpdates()
            }

            setOnErrorListener { _, _, _ ->
                if (activePlaybackToken != playbackToken) {
                    return@setOnErrorListener true
                }

                mediaPlayerPrepared = false
                _isPlaying.value = false
                stopPositionUpdates()
                handlePlaybackFailure()
            }
        }
    }

    fun playSegment(
        filePath: String,
        startTimeMs: Long?,
        endTimeMs: Long?,
        loop: Boolean = false,
        fallbackAudioPath: String? = null
    ) {
        init()
        val uri = toMediaUri(filePath)
        val token = beginNewPlaybackSession()

        segmentStartMs = startTimeMs ?: 0L
        segmentEndMs = endTimeMs
        loopPlayback = loop
        sourceMediaUri = uri
        currentPlaybackUri = uri
        preparedFallbackAudioUri = fallbackAudioPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::toMediaUri)
        retriedFallbackAudioForCurrentMedia = false
        fallbackRecoveryJob?.cancel()
        fallbackRecoveryJob = null

        startPlayback(uri, token)
    }

    fun playClip(filePath: String, loop: Boolean = false) {
        playSegment(filePath, null, null, loop)
    }

    fun bindVideoSurface(holder: SurfaceHolder) {
        videoSurfaceHolder = holder
        mediaPlayer?.setDisplay(holder)
    }

    fun unbindVideoSurface(holder: SurfaceHolder) {
        if (videoSurfaceHolder === holder) {
            videoSurfaceHolder = null
            mediaPlayer?.setDisplay(null)
        }
    }

    fun pause() {
        val liveMediaPlayer = mediaPlayer
        if (mediaPlayerPrepared && liveMediaPlayer?.isPlaying == true) {
            liveMediaPlayer.pause()
            _isPlaying.value = false
            stopPositionUpdates()
        }
    }

    fun resume() {
        val liveMediaPlayer = mediaPlayer
        if (mediaPlayerPrepared && liveMediaPlayer != null) {
            liveMediaPlayer.start()
            _isPlaying.value = true
            startPositionUpdates()
        }
    }

    fun stop() {
        invalidatePlaybackToken()
        resetMediaPlayer()
        clearPlaybackState()
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    fun seekTo(positionMs: Long) {
        if (mediaPlayerPrepared) {
            mediaPlayer?.seekTo(positionMs.coerceAtLeast(0L).toInt())
            _currentPositionMs.value = positionMs.coerceAtLeast(0L)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        applyMediaPlayerPlaybackSpeed(mediaPlayer)
    }

    fun release() {
        invalidatePlaybackToken()
        stopPositionUpdates()
        clearPlaybackState()
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        mediaPlayerPrepared = false
        videoSurfaceHolder = null
    }

    private fun beginNewPlaybackSession(): Long {
        playbackToken += 1
        activePlaybackToken = playbackToken
        return playbackToken
    }

    private fun invalidatePlaybackToken() {
        playbackToken += 1
    }

    private fun startPlayback(uri: Uri, token: Long) {
        init()
        val liveMediaPlayer = mediaPlayer ?: return
        activePlaybackToken = token
        currentPlaybackUri = uri
        stopPositionUpdates()
        mediaPlayerPrepared = false
        _isPlaying.value = false
        _currentPositionMs.value = segmentStartMs.coerceAtLeast(0L)
        _durationMs.value = 0L

        runCatching {
            liveMediaPlayer.reset()
            liveMediaPlayer.setDisplay(videoSurfaceHolder)
            liveMediaPlayer.setDataSource(context, uri)
            liveMediaPlayer.prepareAsync()
        }.onFailure { error ->
            mediaPlayerPrepared = false
            handlePlaybackFailure(
                error.message ?: "Unable to start playback on this device."
            )
        }
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateRunnable = object : Runnable {
            override fun run() {
                val liveMediaPlayer = mediaPlayer
                val currentPosition = getCurrentPlaybackPositionMs() ?: return
                _currentPositionMs.value = currentPosition

                val endMs = segmentEndMs
                if (endMs != null && currentPosition >= endMs) {
                    if (loopPlayback && mediaPlayerPrepared && liveMediaPlayer != null) {
                        liveMediaPlayer.seekTo(segmentStartMs.toInt())
                        liveMediaPlayer.start()
                        _currentPositionMs.value = segmentStartMs.coerceAtLeast(0L)
                    } else {
                        pause()
                        seekTo(segmentStartMs)
                        _isPlaying.value = false
                        stopPositionUpdates()
                        return
                    }
                }

                if (mediaPlayerPrepared && liveMediaPlayer?.isPlaying == true) {
                    mainHandler.postDelayed(this, 100L)
                }
            }
        }
        mainHandler.post(positionUpdateRunnable!!)
    }

    private fun stopPositionUpdates() {
        positionUpdateRunnable?.let(mainHandler::removeCallbacks)
        positionUpdateRunnable = null
    }

    private fun clearPlaybackState() {
        segmentStartMs = 0L
        segmentEndMs = null
        loopPlayback = false
        sourceMediaUri = null
        currentPlaybackUri = null
        preparedFallbackAudioUri = null
        retriedFallbackAudioForCurrentMedia = false
        fallbackRecoveryJob?.cancel()
        fallbackRecoveryJob = null
    }

    private fun getCurrentPlaybackPositionMs(): Long? {
        return if (mediaPlayerPrepared) {
            mediaPlayer?.currentPosition?.toLong()?.coerceAtLeast(0L)
        } else {
            null
        }
    }

    private fun resetMediaPlayer() {
        stopPositionUpdates()
        runCatching { mediaPlayer?.reset() }
        mediaPlayerPrepared = false
    }

    private fun handlePlaybackFailure(
        defaultMessage: String = "Unable to play this media on this device."
    ): Boolean {
        if (tryRecoverWithFallbackAudio()) {
            return true
        }
        _playbackMessages.tryEmit(defaultMessage)
        return true
    }

    private fun tryRecoverWithFallbackAudio(): Boolean {
        if (retriedFallbackAudioForCurrentMedia) {
            return false
        }

        val sourceUri = sourceMediaUri ?: return false
        retriedFallbackAudioForCurrentMedia = true
        fallbackRecoveryJob?.cancel()
        val recoveryToken = playbackToken

        fallbackRecoveryJob = playbackScope.launch {
            val preparedFallbackAsset = preparedFallbackAudioUri
                ?.takeIf { uri ->
                    val path = uriToLocalPath(uri)
                    path == null || File(path).exists()
                }
                ?.let { uri ->
                    FallbackAudioAsset(
                        uri = uri,
                        coversOnlyRequestedSegment = false,
                        preGenerated = true
                    )
                }

            var fallbackBuildErrorMessage: String? = null
            val fallbackAudioAsset = preparedFallbackAsset ?: runCatching {
                val sourcePath = uriToLocalPath(sourceUri) ?: return@runCatching null
                getOrCreateFallbackAudioAsset(
                    sourcePath = sourcePath,
                    requestedStartTimeMs = segmentStartMs.takeIf {
                        it > 0L || segmentEndMs != null
                    },
                    requestedEndTimeMs = segmentEndMs
                )
            }.onFailure { error ->
                fallbackBuildErrorMessage = error.message
            }.getOrNull()

            if (!isActive || recoveryToken != playbackToken) {
                return@launch
            }

            mainHandler.post {
                if (recoveryToken != playbackToken) {
                    return@post
                }

                if (fallbackAudioAsset == null) {
                    _playbackMessages.tryEmit(
                        fallbackBuildErrorMessage ?: "Unable to play this media on this device."
                    )
                    return@post
                }

                if (fallbackAudioAsset.coversOnlyRequestedSegment) {
                    segmentStartMs = 0L
                    segmentEndMs = null
                }

                startPlayback(fallbackAudioAsset.uri, recoveryToken)
                _playbackMessages.tryEmit(
                    when {
                        fallbackAudioAsset.preGenerated ->
                            "Video playback is incompatible on this device. Switched to pre-generated audio playback."
                        fallbackAudioAsset.coversOnlyRequestedSegment ->
                            "Video playback is incompatible on this device. Switched to extracted segment audio playback."
                        else ->
                            "Source format is incompatible. Switched to transcoded audio playback."
                    }
                )
            }
        }
        return true
    }

    private fun toMediaUri(path: String): Uri {
        return if (path.startsWith("content://") || path.startsWith("file://")) {
            Uri.parse(path)
        } else {
            Uri.fromFile(File(path))
        }
    }

    private fun uriToLocalPath(uri: Uri): String? {
        return when (uri.scheme) {
            null -> uri.toString().takeIf { it.isNotBlank() }
            "file" -> uri.path
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun getOrCreateFallbackAudioAsset(
        sourcePath: String,
        requestedStartTimeMs: Long?,
        requestedEndTimeMs: Long?
    ): FallbackAudioAsset {
        val sourceFile = File(sourcePath)
        val sourceKey =
            "${sourceFile.absolutePath}|${sourceFile.length()}|${sourceFile.lastModified()}"
        val hasRequestedSegment =
            requestedStartTimeMs != null &&
                requestedEndTimeMs != null &&
                requestedEndTimeMs > requestedStartTimeMs
        val cacheKey = if (hasRequestedSegment) {
            "$sourceKey|segment|$requestedStartTimeMs|$requestedEndTimeMs"
        } else {
            "$sourceKey|full"
        }

        fallbackAudioCache[cacheKey]?.let { cachedPath ->
            if (File(cachedPath).exists()) {
                return FallbackAudioAsset(
                    uri = Uri.fromFile(File(cachedPath)),
                    coversOnlyRequestedSegment = hasRequestedSegment,
                    preGenerated = false
                )
            }
        }

        if (!hasRequestedSegment) {
            val durationMs = AudioUtils.resolveMediaDurationMs(sourcePath)
            if (durationMs != null &&
                durationMs > MediaTranscodeLimits.PLAYBACK_ON_DEMAND_FULL_FALLBACK_LIMIT_MS
            ) {
                throw IllegalStateException(
                    "This media cannot be played directly on this device and is too long " +
                        "to build a full fallback audio track on demand."
                )
            }
        }

        val fallbackDir = File(context.cacheDir, "playback_fallback_audio").apply { mkdirs() }
        val outputFile = File(fallbackDir, "fallback_${cacheKey.hashCode()}.wav")

        if (!outputFile.exists()) {
            val pcmFloat = if (hasRequestedSegment) {
                AudioUtils.decodeMediaSegmentAsFloat(
                    filePath = sourcePath,
                    startTimeMs = requestedStartTimeMs,
                    endTimeMs = requestedEndTimeMs,
                    targetSampleRate = AudioUtils.TARGET_SAMPLE_RATE
                )
            } else {
                AudioUtils.readAudioAsFloat(
                    filePath = sourcePath,
                    targetSampleRate = AudioUtils.TARGET_SAMPLE_RATE
                )
            }
            AudioUtils.writeMono16BitWav(
                output = outputFile,
                samples = pcmFloat,
                sampleRate = AudioUtils.TARGET_SAMPLE_RATE
            )
        }

        fallbackAudioCache[cacheKey] = outputFile.absolutePath
        return FallbackAudioAsset(
            uri = Uri.fromFile(outputFile),
            coversOnlyRequestedSegment = hasRequestedSegment,
            preGenerated = false
        )
    }

    private fun applyMediaPlayerPlaybackSpeed(liveMediaPlayer: MediaPlayer?) {
        if (!mediaPlayerPrepared || liveMediaPlayer == null) {
            return
        }

        runCatching {
            val playbackParams =
                liveMediaPlayer.playbackParams ?: android.media.PlaybackParams()
            liveMediaPlayer.playbackParams = playbackParams.setSpeed(currentPlaybackSpeed)
        }
    }
}
