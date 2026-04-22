package com.orien.shadowing.data.local.media

/**
 * Shared duration limits for playback-compatibility conversion paths.
 *
 * Keep these values in one place so import/playback behavior can be tuned
 * without hunting through multiple media classes.
 */
object MediaTranscodeLimits {
    const val IMPORT_COMPAT_VIDEO_TRANSCODE_LIMIT_MINUTES = 10L
    const val IMPORT_COMPAT_VIDEO_TRANSCODE_LIMIT_MS =
        IMPORT_COMPAT_VIDEO_TRANSCODE_LIMIT_MINUTES * 60_000L

    const val IMPORT_EAGER_FALLBACK_AUDIO_LIMIT_MINUTES = 2L
    const val IMPORT_EAGER_FALLBACK_AUDIO_LIMIT_MS =
        IMPORT_EAGER_FALLBACK_AUDIO_LIMIT_MINUTES * 60_000L

    const val PLAYBACK_ON_DEMAND_FULL_FALLBACK_LIMIT_MINUTES = 2L
    const val PLAYBACK_ON_DEMAND_FULL_FALLBACK_LIMIT_MS =
        PLAYBACK_ON_DEMAND_FULL_FALLBACK_LIMIT_MINUTES * 60_000L
}
