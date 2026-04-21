package com.orien.shadowing.data.local

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.orien.shadowing.data.local.moonshine.AudioUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExoPlayer-backed audio playback helper.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class FallbackAudioAsset(
        val path: String,
        val coversOnlyRequestedSegment: Boolean
    )

    private var player: ExoPlayer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
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
    private var loopSegment: Boolean = false
    private var currentMediaUri: Uri? = null
    private var preparedFallbackAudioUri: Uri? = null
    private var retriedAudioOnlyForCurrentMedia: Boolean = false
    private var retriedTranscodedAudioForCurrentMedia: Boolean = false
    private var transcodeRecoveryJob: Job? = null
    private val fallbackAudioCache = mutableMapOf<String, String>()

    companion object {
        private const val MAX_ON_DEMAND_FULL_FALLBACK_DURATION_MS = 120_000L
    }

    fun init() {
        if (player != null) {
            return
        }

        player = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    _isPlaying.value = isPlayingNow
                    if (isPlayingNow) {
                        startPositionUpdates()
                    } else {
                        stopPositionUpdates()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        _durationMs.value = duration.coerceAtLeast(0L)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        _isPlaying.value = false
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _isPlaying.value = false
                    if (tryRecoverWithAudioOnly(this@apply)) {
                        return
                    }
                    if (tryRecoverWithTranscodedAudio(this@apply)) {
                        return
                    }

                    val fallbackMessage = error.message
                        ?.takeUnless { it.equals("Source error", ignoreCase = true) }
                        ?: "Unable to play this media on this device."
                    _playbackMessages.tryEmit(
                        fallbackMessage
                    )
                }
            })
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
        val exoPlayer = player ?: return
        val uri = if (filePath.startsWith("content://") || filePath.startsWith("file://")) {
            android.net.Uri.parse(filePath)
        } else {
            android.net.Uri.fromFile(java.io.File(filePath))
        }

        segmentStartMs = startTimeMs ?: 0L
        segmentEndMs = endTimeMs
        loopSegment = loop && endTimeMs != null
        currentMediaUri = uri
        preparedFallbackAudioUri = fallbackAudioPath?.takeIf { it.isNotBlank() }?.let { path ->
            if (path.startsWith("content://") || path.startsWith("file://")) {
                Uri.parse(path)
            } else {
                Uri.fromFile(File(path))
            }
        }
        retriedAudioOnlyForCurrentMedia = false
        retriedTranscodedAudioForCurrentMedia = false
        transcodeRecoveryJob?.cancel()
        transcodeRecoveryJob = null

        exoPlayer.repeatMode = if (endTimeMs == null && loop) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
        applyVideoTrackDisabled(exoPlayer, disabled = false)

        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.seekTo(segmentStartMs)
        exoPlayer.playWhenReady = true
    }

    fun playClip(filePath: String, loop: Boolean = false) {
        playSegment(filePath, null, null, loop)
    }

    fun getPlayer(): ExoPlayer {
        init()
        return checkNotNull(player)
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun stop() {
        player?.stop()
        clearSegmentState()
        _isPlaying.value = false
        _currentPositionMs.value = 0L
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
    }

    fun release() {
        stopPositionUpdates()
        clearSegmentState()
        player?.release()
        player = null
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateRunnable = object : Runnable {
            override fun run() {
                val exoPlayer = player ?: return
                val currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
                _currentPositionMs.value = currentPosition

                val endMs = segmentEndMs
                if (endMs != null && currentPosition >= endMs) {
                    if (loopSegment) {
                        exoPlayer.seekTo(segmentStartMs)
                        exoPlayer.playWhenReady = true
                    } else {
                        exoPlayer.pause()
                        exoPlayer.seekTo(segmentStartMs)
                        clearSegmentState()
                        return
                    }
                }

                if (exoPlayer.isPlaying) {
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

    private fun clearSegmentState() {
        segmentStartMs = 0L
        segmentEndMs = null
        loopSegment = false
        currentMediaUri = null
        preparedFallbackAudioUri = null
        retriedAudioOnlyForCurrentMedia = false
        retriedTranscodedAudioForCurrentMedia = false
        transcodeRecoveryJob?.cancel()
        transcodeRecoveryJob = null
        player?.repeatMode = Player.REPEAT_MODE_OFF
    }

    private fun tryRecoverWithAudioOnly(exoPlayer: ExoPlayer): Boolean {
        if (retriedAudioOnlyForCurrentMedia) {
            return false
        }
        val mediaUri = currentMediaUri ?: return false

        return runCatching {
            retriedAudioOnlyForCurrentMedia = true
            applyVideoTrackDisabled(exoPlayer, disabled = true)
            exoPlayer.setMediaItem(MediaItem.fromUri(mediaUri))
            exoPlayer.prepare()
            exoPlayer.seekTo(segmentStartMs)
            exoPlayer.playWhenReady = true
            true
        }.getOrDefault(false)
    }

    private fun tryRecoverWithTranscodedAudio(exoPlayer: ExoPlayer): Boolean {
        if (retriedTranscodedAudioForCurrentMedia) {
            return false
        }

        retriedTranscodedAudioForCurrentMedia = true
        transcodeRecoveryJob = playbackScope.launch {
            val preparedFallbackUri = preparedFallbackAudioUri
                ?.takeIf { uri -> uriToLocalPath(uri)?.let(::File)?.exists() == true }
            var fallbackBuildErrorMessage: String? = null
            val fallbackAudioAsset = preparedFallbackUri?.let(::uriToLocalPath)?.let { path ->
                FallbackAudioAsset(
                    path = path,
                    coversOnlyRequestedSegment = false
                )
            } ?: runCatching {
                val mediaUri = currentMediaUri ?: return@runCatching null
                val sourcePath = uriToLocalPath(mediaUri) ?: return@runCatching null
                getOrCreateFallbackAudioAsset(
                    sourcePath = sourcePath,
                    requestedStartTimeMs = segmentStartMs.takeIf { it > 0L || segmentEndMs != null },
                    requestedEndTimeMs = segmentEndMs
                )
            }.onFailure { error ->
                fallbackBuildErrorMessage = error.message
            }.getOrNull()

            mainHandler.post {
                val livePlayer = player
                if (livePlayer == null || livePlayer !== exoPlayer || fallbackAudioAsset == null) {
                    _playbackMessages.tryEmit(
                        fallbackBuildErrorMessage ?: "Unable to play this media on this device."
                    )
                    return@post
                }

                runCatching {
                    applyVideoTrackDisabled(livePlayer, disabled = true)
                    val useSegmentFallback = fallbackAudioAsset.coversOnlyRequestedSegment
                    livePlayer.repeatMode = if (useSegmentFallback && loopSegment) {
                        Player.REPEAT_MODE_ONE
                    } else if (!useSegmentFallback && segmentEndMs == null && loopSegment) {
                        Player.REPEAT_MODE_ONE
                    } else {
                        Player.REPEAT_MODE_OFF
                    }
                    livePlayer.setMediaItem(
                        MediaItem.fromUri(Uri.fromFile(File(fallbackAudioAsset.path)))
                    )
                    livePlayer.prepare()
                    if (useSegmentFallback) {
                        segmentStartMs = 0L
                        segmentEndMs = null
                        livePlayer.seekTo(0L)
                    } else {
                        livePlayer.seekTo(segmentStartMs)
                    }
                    livePlayer.playWhenReady = true
                    _playbackMessages.tryEmit(
                        if (preparedFallbackUri != null) {
                            "Video playback is incompatible on this device. Switched to pre-generated audio playback."
                        } else if (useSegmentFallback) {
                            "Video playback is incompatible on this device. Switched to extracted segment audio playback."
                        } else {
                            "Source format is incompatible. Switched to transcoded audio playback."
                        }
                    )
                }.onFailure {
                    _playbackMessages.tryEmit("Unable to play this media on this device.")
                }
            }
        }
        return true
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
                    path = cachedPath,
                    coversOnlyRequestedSegment = hasRequestedSegment
                )
            }
        }

        if (!hasRequestedSegment) {
            val durationMs = AudioUtils.resolveMediaDurationMs(sourcePath)
            if (durationMs != null && durationMs > MAX_ON_DEMAND_FULL_FALLBACK_DURATION_MS) {
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
            path = outputFile.absolutePath,
            coversOnlyRequestedSegment = hasRequestedSegment
        )
    }

    private fun applyVideoTrackDisabled(exoPlayer: ExoPlayer, disabled: Boolean) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }
}
