package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebMediaChapterRowsTest {
    @Test
    fun `decodes WebVTT chapter cue rows`() {
        val rows =
            """
            1250|5750|Wst%C4%99p%20%7C%20cz%C4%99%C5%9B%C4%87%201|pl-PL|0
            8000||Fina%C5%82||1
            """.trimIndent()

        val chapters = parseWebMediaChapterRows(rows)

        assertEquals(2, chapters.size)
        assertEquals(1_250L, chapters[0].startMs)
        assertEquals(5_750L, chapters[0].endMs)
        assertEquals("Wstęp | część 1", chapters[0].title)
        assertEquals("pl-PL", chapters[0].language)
        assertFalse(chapters[0].isHidden)
        assertEquals(8_000L, chapters[1].startMs)
        assertNull(chapters[1].endMs)
        assertNull(chapters[1].language)
        assertTrue(chapters[1].isHidden)
    }

    @Test
    fun `ignores incomplete and invalid WebVTT chapter cue rows`() {
        val chapters =
            parseWebMediaChapterRows(
                """
                1000|2000|valid||0
                not-a-time|3000|invalid||0
                4000|5000|missing-column
                """.trimIndent(),
            )

        assertEquals(listOf(1_000L), chapters.map(RawMediaChapter::startMs))
    }
}
