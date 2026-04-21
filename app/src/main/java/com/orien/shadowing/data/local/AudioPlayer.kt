package com.orien.shadowing.data.local

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExoPlayer-backed audio playback helper.
 */
@Singleton
class AudioPlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var player: ExoPlayer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var positionUpdateRunnable: Runnable? = null
    private var segmentStartMs: Long = 0L
    private var segmentEndMs: Long? = null
    private var loopSegment: Boolean = false

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
            })
        }
    }

    fun playSegment(
        filePath: String,
        startTimeMs: Long?,
        endTimeMs: Long?,
        loop: Boolean = false
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

        exoPlayer.repeatMode = if (endTimeMs == null && loop) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }

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
        player?.repeatMode = Player.REPEAT_MODE_OFF
    }
}
