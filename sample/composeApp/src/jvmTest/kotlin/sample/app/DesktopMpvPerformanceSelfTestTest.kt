package sample.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopMpvPerformanceSelfTestTest {
    @Test
    fun parsesOpenGlPresentationCounters() {
        assertEquals(
            NativePresentationCounters(fresh = 123L, repeated = 4L),
            parseNativePresentationCounters("nativeMac new=123 repeats=4 swaps=127"),
        )
    }

    @Test
    fun parsesMacVkPresentationCounter() {
        assertEquals(
            NativePresentationCounters(fresh = 456L, repeated = 0L),
            parseNativePresentationCounters(" macvkPresents=456"),
        )
    }

    @Test
    fun rejectsUnavailablePresentationTelemetry() {
        assertNull(parseNativePresentationCounters(" macvkPresents=unavailable"))
    }
}
