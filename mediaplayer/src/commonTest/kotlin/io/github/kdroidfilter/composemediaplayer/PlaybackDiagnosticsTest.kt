package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackDiagnosticsTest {
    @Test
    fun prefersMeasuredRenderedFrames() {
        val diagnostics =
            PlaybackDiagnostics(
                totalVideoFrames = 120,
                renderedVideoFrames = 117,
                droppedVideoFrames = 4,
            )

        assertEquals(117, diagnostics.effectiveVideoFrames)
    }

    @Test
    fun derivesEffectiveFramesWhenMeasuredCountIsUnavailable() {
        val diagnostics =
            PlaybackDiagnostics(
                totalVideoFrames = 120,
                droppedVideoFrames = 4,
            )

        assertEquals(116, diagnostics.effectiveVideoFrames)
        assertEquals(100f / 30f, diagnostics.droppedFramePercent!!, absoluteTolerance = 0.0001f)
    }
}
