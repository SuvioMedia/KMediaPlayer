package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
data class PlaybackDiagnostics(
    val totalVideoFrames: Long? = null,
    val renderedVideoFrames: Long? = null,
    val droppedVideoFrames: Long? = null,
    val corruptedVideoFrames: Long? = null,
    /** Largest sampled absolute difference between video PTS and the active audio clock, or null when unavailable. */
    val maximumAvSyncOffsetMs: Float? = null,
    /** Audio already scheduled on the Web Audio timeline, in milliseconds. */
    val audioBufferAheadMs: Float? = null,
    /** Number of times decoded audio arrived after the scheduled timeline had run dry. */
    val audioUnderruns: Long? = null,
    /** Number of small decoder/container timestamp gaps normalized into a continuous PCM timeline. */
    val audioTimestampCorrections: Long? = null,
    val readyState: Int? = null,
    val networkState: Int? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val bitrate: Int? = null,
    val currentHlsQuality: HlsQualityVariant? = null,
    val bufferedRanges: List<BufferedRange> = emptyList(),
    val notes: String? = null,
) {
    val droppedFramePercent: Float?
        get() {
            val total = totalVideoFrames ?: return null
            val dropped = droppedVideoFrames ?: return null
            if (total <= 0L) return null
            return dropped.toFloat() / total.toFloat() * PERCENT_SCALE
        }

    val effectiveVideoFrames: Long?
        get() =
            renderedVideoFrames ?: totalVideoFrames?.let { total ->
                (total - (droppedVideoFrames ?: 0L)).coerceAtLeast(0L)
            }

    companion object {
        private const val PERCENT_SCALE = 100f
    }
}
