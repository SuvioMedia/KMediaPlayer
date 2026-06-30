package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class BufferedRangeTest {
    @Test
    fun exposesRangeDuration() {
        val range = BufferedRange(start = 1.seconds, end = 5.seconds)

        assertEquals(4.seconds, range.duration)
    }

    @Test
    fun rejectsNegativeStart() {
        assertFailsWith<IllegalArgumentException> {
            BufferedRange(start = (-1).seconds, end = 1.seconds)
        }
    }

    @Test
    fun rejectsEndBeforeStart() {
        assertFailsWith<IllegalArgumentException> {
            BufferedRange(start = 5.seconds, end = 1.seconds)
        }
    }
}
