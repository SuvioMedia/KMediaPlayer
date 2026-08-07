package io.github.kdroidfilter.composemediaplayer.mac

/** A compact sample of libVLC's decoder clock and frame-loss counters. */
internal data class MacLibVlcPlaybackRateSample(
    val playbackSpeed: Float,
    val positionSeconds: Double,
    val decodedFrames: Long?,
    val droppedFrames: Long?,
    val videoWidth: Int?,
    val videoHeight: Int?,
)

/**
 * Detects an accelerated native libVLC renderer that is advancing its media clock while dropping
 * effectively every decoded frame. On macOS that state can also leave a large Cocoa/Metal vout
 * queue in flight; for an 8K source, waiting for generic buffering detection risks exhausting
 * unified memory before the player recovers.
 */
internal class MacLibVlcPlaybackRateGuard(
    private val highResolutionPixelThreshold: Long = 24_000_000L,
    private val highResolutionDroppedFrameBudget: Long = 4L,
    private val defaultDroppedFrameBudget: Long = 24L,
    private val clockOnlySampleBudget: Int = 3,
) {
    private var previous: MacLibVlcPlaybackRateSample? = null
    private var consecutiveClockOnlySamples: Int = 0
    private var accumulatedOverloadDrops: Long = 0L

    @Synchronized
    fun reset() {
        previous = null
        consecutiveClockOnlySamples = 0
        accumulatedOverloadDrops = 0L
    }

    @Synchronized
    fun shouldRecover(sample: MacLibVlcPlaybackRateSample): Boolean {
        if (sample.playbackSpeed <= 1.0f || !sample.positionSeconds.isFinite()) {
            reset()
            return false
        }

        val earlier = previous
        previous = sample
        if (earlier == null) return false

        val positionDelta = sample.positionSeconds - earlier.positionSeconds
        if (positionDelta < MINIMUM_CLOCK_ADVANCE_SECONDS) {
            consecutiveClockOnlySamples = 0
            accumulatedOverloadDrops = 0L
            return false
        }

        val decodedDelta = monotonicDelta(sample.decodedFrames, earlier.decodedFrames)
        val droppedDelta = monotonicDelta(sample.droppedFrames, earlier.droppedFrames)
        if (decodedDelta == null || droppedDelta == null) {
            consecutiveClockOnlySamples++
            return consecutiveClockOnlySamples >= clockOnlySampleBudget
        }

        if (decodedDelta == 0L) {
            consecutiveClockOnlySamples++
        } else {
            consecutiveClockOnlySamples = 0
        }
        if (consecutiveClockOnlySamples >= clockOnlySampleBudget) return true

        val overloadDropRatio =
            decodedDelta > 0L &&
                droppedDelta > 0L &&
                droppedDelta.toDouble() / decodedDelta.toDouble() >= OVERLOAD_DROP_RATIO
        accumulatedOverloadDrops =
            if (overloadDropRatio) {
                (accumulatedOverloadDrops + droppedDelta).coerceAtMost(defaultDroppedFrameBudget)
            } else {
                0L
            }

        val pixels =
            sample.videoWidth
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(sample.videoHeight?.takeIf { it > 0 }?.toLong() ?: 0L)
                ?: 0L
        val droppedFrameBudget =
            if (pixels >= highResolutionPixelThreshold) {
                highResolutionDroppedFrameBudget
            } else {
                defaultDroppedFrameBudget
            }
        return accumulatedOverloadDrops >= droppedFrameBudget
    }

    private fun monotonicDelta(
        current: Long?,
        earlier: Long?,
    ): Long? {
        if (current == null || earlier == null || current < earlier) return null
        return current - earlier
    }

    private companion object {
        const val MINIMUM_CLOCK_ADVANCE_SECONDS = 0.1
        const val OVERLOAD_DROP_RATIO = 0.75
    }
}
