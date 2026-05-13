package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals

class AssParserTest {
    @Test
    fun parsesDialogueTimingAndPlainText() {
        val assContent =
            """
            [Script Info]
            Title: Demo

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:07.82,0:00:09.36,Default,,0,0,0,,That's mean, Kacchan.
            Dialogue: 0,0:00:47.23,0:00:51.28,Default,,0,0,0,,{\i1}This was learned\Nabout society.
            """.trimIndent()

        val subtitles = AssParser.parse(assContent)

        assertEquals(2, subtitles.cues.size)
        assertEquals(7_820, subtitles.cues[0].startTime)
        assertEquals(9_360, subtitles.cues[0].endTime)
        assertEquals("That's mean, Kacchan.", subtitles.cues[0].text)
        assertEquals("This was learned\nabout society.", subtitles.cues[1].text)
    }

    @Test
    fun keepsCommasInsideTextField() {
        val assContent =
            """
            [Events]
            Format: Start, End, Text
            Dialogue: 0:00:01.00,0:00:02.00,Hello, with comma
            """.trimIndent()

        val subtitles = AssParser.parse(assContent)

        assertEquals(1, subtitles.cues.size)
        assertEquals("Hello, with comma", subtitles.cues[0].text)
    }
}
