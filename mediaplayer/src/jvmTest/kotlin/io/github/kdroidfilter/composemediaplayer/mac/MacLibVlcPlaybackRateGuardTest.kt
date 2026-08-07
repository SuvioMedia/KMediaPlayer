package io.github.kdroidfilter.composemediaplayer.mac

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacLibVlcPlaybackRateGuardTest {
    @Test
    fun `normal playback never triggers recovery`() {
        val guard = MacLibVlcPlaybackRateGuard()

        assertFalse(guard.shouldRecover(sample(speed = 1.0f, position = 0.0, decoded = 0, dropped = 0)))
        assertFalse(guard.shouldRecover(sample(speed = 1.0f, position = 1.0, decoded = 60, dropped = 60)))
    }

    @Test
    fun `accelerated playback with useful frames stays enabled`() {
        val guard = MacLibVlcPlaybackRateGuard()

        assertFalse(guard.shouldRecover(sample(position = 0.0, decoded = 0, dropped = 0)))
        assertFalse(guard.shouldRecover(sample(position = 0.5, decoded = 30, dropped = 2)))
        assertFalse(guard.shouldRecover(sample(position = 1.0, decoded = 60, dropped = 4)))
    }

    @Test
    fun `8k playback recovers as soon as the native renderer drops a frame batch`() {
        val guard = MacLibVlcPlaybackRateGuard()

        assertFalse(guard.shouldRecover(sample(position = 0.0, decoded = 0, dropped = 0)))
        assertTrue(guard.shouldRecover(sample(position = 0.5, decoded = 12, dropped = 12)))
    }

    @Test
    fun `advancing clock without decoded frames eventually recovers`() {
        val guard = MacLibVlcPlaybackRateGuard()

        assertFalse(guard.shouldRecover(sample(position = 0.0, decoded = 10, dropped = 0)))
        assertFalse(guard.shouldRecover(sample(position = 0.5, decoded = 10, dropped = 0)))
        assertFalse(guard.shouldRecover(sample(position = 1.0, decoded = 10, dropped = 0)))
        assertTrue(guard.shouldRecover(sample(position = 1.5, decoded = 10, dropped = 0)))
    }

    @Test
    fun `healthy sample resets accumulated overload`() {
        val guard =
            MacLibVlcPlaybackRateGuard(
                highResolutionDroppedFrameBudget = 10,
            )

        assertFalse(guard.shouldRecover(sample(position = 0.0, decoded = 0, dropped = 0)))
        assertFalse(guard.shouldRecover(sample(position = 0.5, decoded = 5, dropped = 5)))
        assertFalse(guard.shouldRecover(sample(position = 1.0, decoded = 15, dropped = 5)))
        assertFalse(guard.shouldRecover(sample(position = 1.5, decoded = 20, dropped = 10)))
    }

    private fun sample(
        speed: Float = 2.0f,
        position: Double,
        decoded: Long,
        dropped: Long,
    ): MacLibVlcPlaybackRateSample =
        MacLibVlcPlaybackRateSample(
            playbackSpeed = speed,
            positionSeconds = position,
            decodedFrames = decoded,
            droppedFrames = dropped,
            videoWidth = 8_000,
            videoHeight = 4_000,
        )
}
