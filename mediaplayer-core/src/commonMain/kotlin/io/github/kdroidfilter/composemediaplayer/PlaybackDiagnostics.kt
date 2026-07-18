package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
data class PlaybackDiagnostics(
    val totalVideoFrames: Long? = null,
    val droppedVideoFrames: Long? = null,
    val corruptedVideoFrames: Long? = null,
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

    companion object {
        private const val PERCENT_SCALE = 100f
    }
}
