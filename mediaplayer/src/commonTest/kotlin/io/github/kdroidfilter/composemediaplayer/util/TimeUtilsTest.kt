package io.github.kdroidfilter.composemediaplayer.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class TimeUtilsTest {
    @Test
    fun testFormatTimeWithSeconds() {
        assertEquals("00:00.000", formatTime(0.seconds))
        assertEquals("00:01.000", formatTime(1.seconds))
        assertEquals("00:59.000", formatTime(59.seconds))
        assertEquals("01:00.000", formatTime(60.seconds))
        assertEquals("01:01.000", formatTime(61.seconds))
        assertEquals("59:59.000", formatTime(3599.seconds))
        assertEquals("01:00:00.000", formatTime(3600.seconds))
        assertEquals("01:00:01.000", formatTime(3601.seconds))
        assertEquals("01:01:01.000", formatTime(3661.seconds))
        assertEquals("99:59:59.000", formatTime(359999.seconds))
    }

    @Test
    fun testFormatTimeWithMilliseconds() {
        assertEquals("00:00.001", formatTime(1.milliseconds))
        assertEquals("00:01.234", formatTime(1234.milliseconds))
        assertEquals("01:01.234", formatTime(61_234.milliseconds))
        assertEquals("01:00:01.234", formatTime(3_601_234.milliseconds))
    }

    @Test
    fun testFormatTimeWithNanoseconds() {
        assertEquals("00:00.000", formatTime(0.nanoseconds))
        assertEquals("00:01.000", formatTime(1_000_000_000.nanoseconds))
        assertEquals("00:01.234", formatTime(1_234_567_890.nanoseconds))
        assertEquals("00:59.000", formatTime(59_000_000_000.nanoseconds))
        assertEquals("01:00.000", formatTime(60_000_000_000.nanoseconds))
        assertEquals("01:01.000", formatTime(61_000_000_000.nanoseconds))
        assertEquals("59:59.000", formatTime(3599_000_000_000.nanoseconds))
        assertEquals("01:00:00.000", formatTime(3600_000_000_000.nanoseconds))
        assertEquals("01:00:01.000", formatTime(3601_000_000_000.nanoseconds))
        assertEquals("01:01:01.000", formatTime(3661_000_000_000.nanoseconds))
    }

    @Test
    fun testExplicitDurationConversions() {
        assertEquals(1.seconds, 1.0.secondsAsDuration())
        assertEquals(1.seconds, 1f.secondsAsDuration())
        assertEquals(1.seconds, 1000L.millisecondsAsDuration())
        assertEquals(1.seconds, 1_000_000_000L.nanosecondsAsDuration())
        assertEquals(1.seconds, 10_000_000L.hundredNanosecondsAsDuration())
        assertEquals(10_000_000L, 1.seconds.inWhole100NanosecondTicks())
    }
}
