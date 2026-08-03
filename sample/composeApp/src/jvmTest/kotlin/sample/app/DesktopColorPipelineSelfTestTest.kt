package sample.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopColorPipelineSelfTestTest {
    @Test
    fun `uses the source cadence for ordinary cinema content`() {
        val thresholds = desktopFrameRateThresholds(24.0)

        assertEquals(24.0, thresholds.sourceFrameRate)
        assertEquals(22.56, thresholds.minimumAverageFps, 0.001)
        assertEquals(20.4, thresholds.minimumWindowFps, 0.001)
    }

    @Test
    fun `preserves strict sixty frame thresholds and caps faster sources`() {
        val ntscSixty = desktopFrameRateThresholds(60_000.0 / 1_001.0)
        val sixty = desktopFrameRateThresholds(60.0)
        val oneTwenty = desktopFrameRateThresholds(120.0)

        assertEquals(59.0, ntscSixty.minimumAverageFps)
        assertEquals(55.0, ntscSixty.minimumWindowFps)
        assertEquals(59.0, sixty.minimumAverageFps)
        assertEquals(55.0, sixty.minimumWindowFps)
        assertEquals(sixty.minimumAverageFps, oneTwenty.minimumAverageFps)
        assertEquals(sixty.minimumWindowFps, oneTwenty.minimumWindowFps)
    }

    @Test
    fun `keeps strict defaults when source cadence is unavailable`() {
        val thresholds = desktopFrameRateThresholds(null)

        assertNull(thresholds.sourceFrameRate)
        assertEquals(59.0, thresholds.minimumAverageFps)
        assertEquals(55.0, thresholds.minimumWindowFps)
    }

    @Test
    fun `counts deliberately dropped frames toward decode cadence`() {
        val performance = desktopFramePerformance(
            renderedFrames = 180L,
            droppedFrames = 25L,
            durationSeconds = 8.1397,
        )

        assertEquals(22.1138, performance.renderedAverageFps, 0.001)
        assertEquals(25.1852, performance.processedAverageFps, 0.001)
        assertEquals(25.0 / 205.0, performance.droppedFrameRatio, 0.0001)
        assertTrue(performance.processedAverageFps >= desktopFrameRateThresholds(25.03085).minimumAverageFps)
    }
}
