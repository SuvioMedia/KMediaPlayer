package io.github.kdroidfilter.composemediaplayer

import kotlin.time.Duration

sealed class PlaybackEvent {
    abstract val mediaSessionId: Long
    abstract val sampledAtMs: Long

    data class SourcePreparing(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
        val uri: String,
    ) : PlaybackEvent()

    data class SourceLoaded(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
        val duration: Duration,
    ) : PlaybackEvent()

    data class SourceReleased(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
    ) : PlaybackEvent()

    data class Stalled(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
    ) : PlaybackEvent()

    data class Recovered(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
    ) : PlaybackEvent()

    data class SeekStarted(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
        val target: Duration,
    ) : PlaybackEvent()

    data class SeekCompleted(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
        val position: Duration,
    ) : PlaybackEvent()

    data class TrackChanged(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
        val kind: TrackKind,
        val trackId: String?,
    ) : PlaybackEvent()

    data class PlaybackEnded(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
    ) : PlaybackEvent()

    data class PlaybackRestarted(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
    ) : PlaybackEvent()

    data class Error(
        override val mediaSessionId: Long,
        override val sampledAtMs: Long,
        val error: VideoPlayerError,
    ) : PlaybackEvent()
}

enum class TrackKind {
    AUDIO,
    SUBTITLE,
    HLS_QUALITY,
}
