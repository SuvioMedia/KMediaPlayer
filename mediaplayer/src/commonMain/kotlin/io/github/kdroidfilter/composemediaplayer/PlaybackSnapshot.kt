package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import kotlin.time.Duration

@Stable
data class BufferedRange(
    val start: Duration,
    val end: Duration,
) {
    val duration: Duration
        get() = end - start
}

enum class PlaybackLoadingState {
    IDLE,
    LOADING,
    BUFFERING,
    SEEKING,
}

@Stable
data class PlaybackSnapshot(
    val mediaSessionId: Long = 0L,
    val position: Duration,
    val duration: Duration,
    val isPlaying: Boolean,
    val isLoading: Boolean,
    val isBuffering: Boolean,
    val isSeeking: Boolean,
    val playbackSpeed: Float,
    val sampledAtMs: Long,
    val bufferedRanges: List<BufferedRange> = emptyList(),
    val bufferedPercent: Float = 0f,
    val loadingState: PlaybackLoadingState = PlaybackLoadingState.IDLE,
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
) {
    val positionMs: Long
        get() = position.inWholeMilliseconds

    val durationMs: Long
        get() = duration.inWholeMilliseconds
}
