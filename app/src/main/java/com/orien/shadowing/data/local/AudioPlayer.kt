package com.orien.shadowing.data.local

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.orien.shadowing.data.local.media.MediaTranscodeLimits
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

enum class VideoPlaybackEngine {
    EXO_PLAYER,
    MEDIA_PLAYER
}

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
    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerPrepared = false
    private var mediaPlayerSurfaceHolder: SurfaceHolder? = null
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
    private var activeVideoPlaybackEngine: VideoPlaybackEngine = VideoPlaybackEngine.EXO_PLAYER
    private var currentPlaybackSpeed: Float = 1.0f

    fun init() {
        if (player != null) {
            return
        }

        player = ExoPlayer.Builder(context).build().apply {
            playbackParameters = PlaybackParameters(currentPlaybackSpeed)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    if (activeVideoPlaybackEngine != VideoPlaybackEngine.EXO_PLAYER) {
                        return
                    }
                    _isPlaying.value = isPlayingNow
                    if (isPlayingNow) {
                        startPositionUpdates()
                    } else {
                        stopPositionUpdates()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (activeVideoPlaybackEngine != VideoPlaybackEngine.EXO_PLAYER) {
                        return
                    }
                    if (playbackState == Player.STATE_READY) {
                        _durationMs.value = duration.coerceAtLeast(0L)
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        _isPlaying.value = false
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (activeVideoPlaybackEngine != VideoPlaybackEngine.EXO_PLAYER) {
                        return
                    }
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
        fallbackAudioPath: String? = null,
        isVideo: Boolean = false,
        videoPlaybackEngine: VideoPlaybackEngine = VideoPlaybackEngine.EXO_PLAYER
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

        if (isVideo && videoPlaybackEngine == VideoPlaybackEngine.MEDIA_PLAYER) {
            activeVideoPlaybackEngine = VideoPlaybackEngine.MEDIA_PLAYER
            stopExoPlayback()
            playWithMediaPlayer(uri)
            return
        }

        activeVideoPlaybackEngine = VideoPlaybackEngine.EXO_PLAYER
        resetMediaPlayer()
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

    fun bindMediaPlayerSurface(holder: SurfaceHolder) {
        mediaPlayerSurfaceHolder = holder
        mediaPlayer?.setDisplay(holder)
    }

    fun unbindMediaPlayerSurface(holder: SurfaceHolder) {
        if (mediaPlayerSurfaceHolder === holder) {
            mediaPlayerSurfaceHolder = null
            mediaPlayer?.setDisplay(null)
        }
    }

    fun pause() {
        when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> {
                val liveMediaPlayer = mediaPlayer
                if (mediaPlayerPrepared && liveMediaPlayer?.isPlaying == true) {
                    liveMediaPlayer.pause()
                    _isPlaying.value = false
                    stopPositionUpdates()
                }
            }
            VideoPlaybackEngine.EXO_PLAYER -> player?.pause()
        }
    }

    fun resume() {
        when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> {
                val liveMediaPlayer = mediaPlayer
                if (mediaPlayerPrepared && liveMediaPlayer != null) {
                    liveMediaPlayer.start()
                    _isPlaying.value = true
                    startPositionUpdates()
                }
            }
            VideoPlaybackEngine.EXO_PLAYER -> player?.play()
        }
    }

    fun stop() {
        when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> resetMediaPlayer()
            VideoPlaybackEngine.EXO_PLAYER -> player?.stop()
        }
        clearSegmentState()
        _isPlaying.value = false
        _currentPositionMs.value = 0L
        _durationMs.value = 0L
    }

    fun seekTo(positionMs: Long) {
        when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> {
                if (mediaPlayerPrepared) {
                    mediaPlayer?.seekTo(positionMs.toInt())
                }
            }
            VideoPlaybackEngine.EXO_PLAYER -> player?.seekTo(positionMs)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        currentPlaybackSpeed = speed
        player?.playbackParameters = PlaybackParameters(speed)
        applyMediaPlayerPlaybackSpeed(mediaPlayer)
    }

    fun release() {
        stopPositionUpdates()
        clearSegmentState()
        player?.release()
        player = null
        releaseMediaPlayer()
    }

    private fun startPositionUpdates() {
        stopPositionUpdates()
        positionUpdateRunnable = object : Runnable {
            override fun run() {
                val currentPosition = getCurrentPlaybackPositionMs() ?: return
                _currentPositionMs.value = currentPosition

                val endMs = segmentEndMs
                if (endMs != null && currentPosition >= endMs) {
                    if (loopSegment) {
                        restartSegmentPlayback()
                    } else {
                        pauseActivePlayback()
                        seekActivePlayback(segmentStartMs)
                        stopPositionUpdates()
                        _isPlaying.value = false
                        clearSegmentState()
                        return
                    }
                }

                if (isPlaybackRunning()) {
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

    private fun getCurrentPlaybackPositionMs(): Long? {
        return when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> {
                if (mediaPlayerPrepared) {
                    mediaPlayer?.currentPosition?.toLong()?.coerceAtLeast(0L)
                } else {
                    null
                }
            }
            VideoPlaybackEngine.EXO_PLAYER -> player?.currentPosition?.coerceAtLeast(0L)
        }
    }

    private fun isPlaybackRunning(): Boolean {
        return when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> mediaPlayerPrepared && mediaPlayer?.isPlaying == true
            VideoPlaybackEngine.EXO_PLAYER -> player?.isPlaying == true
        }
    }

    private fun restartSegmentPlayback() {
        seekActivePlayback(segmentStartMs)
        when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> {
                if (mediaPlayerPrepared) {
                    mediaPlayer?.start()
                    _isPlaying.value = true
                }
            }
            VideoPlaybackEngine.EXO_PLAYER -> player?.playWhenReady = true
        }
    }

    private fun seekActivePlayback(positionMs: Long) {
        when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> {
                if (mediaPlayerPrepared) {
                    mediaPlayer?.seekTo(positionMs.toInt())
                }
            }
            VideoPlaybackEngine.EXO_PLAYER -> player?.seekTo(positionMs)
        }
    }

    private fun pauseActivePlayback() {
        when (activeVideoPlaybackEngine) {
            VideoPlaybackEngine.MEDIA_PLAYER -> {
                if (mediaPlayerPrepared && mediaPlayer?.isPlaying == true) {
                    mediaPlayer?.pause()
                }
            }
            VideoPlaybackEngine.EXO_PLAYER -> player?.pause()
        }
    }

    private fun stopExoPlayback() {
        player?.apply {
            stop()
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    private fun resetMediaPlayer() {
        stopPositionUpdates()
        runCatching { mediaPlayer?.reset() }
        mediaPlayerPrepared = false
    }

    private fun releaseMediaPlayer() {
        stopPositionUpdates()
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        mediaPlayerPrepared = false
        mediaPlayerSurfaceHolder = null
    }

    private fun playWithMediaPlayer(uri: Uri) {
        val liveMediaPlayer = mediaPlayer ?: MediaPlayer().apply {
            setOnPreparedListener { preparedPlayer ->
                if (activeVideoPlaybackEngine != VideoPlaybackEngine.MEDIA_PLAYER) {
                    return@setOnPreparedListener
                }
                mediaPlayerPrepared = true
                _durationMs.value = preparedPlayer.duration.coerceAtLeast(0).toLong()
                applyMediaPlayerPlaybackSpeed(preparedPlayer)
                preparedPlayer.isLooping = segmentEndMs == null && loopSegment
                if (segmentStartMs > 0L) {
                    preparedPlayer.seekTo(segmentStartMs.toInt())
                }
                preparedPlayer.start()
                _isPlaying.value = true
                startPositionUpdates()
            }
            setOnCompletionListener {
                if (activeVideoPlaybackEngine != VideoPlaybackEngine.MEDIA_PLAYER) {
                    return@setOnCompletionListener
                }
                _isPlaying.value = false
                stopPositionUpdates()
            }
            setOnErrorListener { _, _, _ ->
                if (activeVideoPlaybackEngine != VideoPlaybackEngine.MEDIA_PLAYER) {
                    return@setOnErrorListener true
                }
                _isPlaying.value = false
                stopPositionUpdates()
                _playbackMessages.tryEmit("Unable to play this video with MediaPlayer.")
                true
            }
        }.also { createdPlayer ->
            mediaPlayer = createdPlayer
        }

        stopPositionUpdates()
        mediaPlayerPrepared = false
        runCatching {
            liveMediaPlayer.reset()
            mediaPlayerSurfaceHolder?.let(liveMediaPlayer::setDisplay)
            liveMediaPlayer.setDataSource(context, uri)
            liveMediaPlayer.prepareAsync()
        }.onFailure { error ->
            _isPlaying.value = false
            _playbackMessages.tryEmit(
                error.message ?: "Unable to start MediaPlayer playback for this video."
            )
        }
    }

    private fun applyMediaPlayerPlaybackSpeed(liveMediaPlayer: MediaPlayer?) {
        if (!mediaPlayerPrepared || liveMediaPlayer == null) {
            return
        }
        runCatching {
            val playbackParams = liveMediaPlayer.playbackParams ?: android.media.PlaybackParams()
            liveMediaPlayer.playbackParams = playbackParams.setSpeed(currentPlaybackSpeed)
        }
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
