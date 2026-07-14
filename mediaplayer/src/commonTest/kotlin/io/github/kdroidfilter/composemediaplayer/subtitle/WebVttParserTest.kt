@file:Suppress("MagicNumber")

package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebVttParserTest {
    @Test
    fun parsesCueSettingsAndOptionalHours() {
        val subtitles =
            WebVttParser.parse(
                """
                WEBVTT

                00:01.250 --> 00:03.500 align:start position:10%
                First cue

                01:02:03.004 --> 01:02:04.500 line:90% size:80%
                Second cue
                """.trimIndent(),
            )

        assertEquals(2, subtitles.cues.size)
        assertEquals(1_250, subtitles.cues[0].startTime)
        assertEquals(3_500, subtitles.cues[0].endTime)
        assertEquals("First cue", subtitles.cues[0].text)
        assertEquals(3_723_004, subtitles.cues[1].startTime)
    }

    @Test
    fun parsesBomHeaderMetadataAndCueIdentifier() {
        val content =
            """
            WEBVTT Example captions
            Kind: captions
            Language: en

            introduction
            00:00:00.000 --> 00:00:02.000
            Hello
            world
            """.trimIndent()
        val subtitles =
            WebVttParser.parse("\uFEFF$content")

        assertEquals(1, subtitles.cues.size)
        assertEquals("Hello\nworld", subtitles.cues.single().text)
    }

    @Test
    fun skipsNoteStyleAndRegionBlocks() {
        val subtitles =
            WebVttParser.parse(
                """
                WEBVTT

                NOTE this --> is not a cue
                Internal annotation

                STYLE
                ::cue { color: lime; }

                REGION
                id:fred
                width:40%

                cue-1
                00:00.000 --> 00:01.000 line:0
                Visible
                """.trimIndent(),
            )

        assertEquals(listOf("Visible"), subtitles.cues.map { it.text })
    }

    @Test
    fun ignoresMalformedBlocksWithoutDroppingFollowingCue() {
        val subtitles =
            WebVttParser.parse(
                """
                WEBVTT

                bad-cue
                00:70.000 --> 00:71.000
                Invalid timestamp

                00:02.000 --> 00:01.000
                Invalid interval

                00:03.000 --> 00:04.000
                Valid cue
                """.trimIndent(),
            )

        assertEquals(1, subtitles.cues.size)
        assertEquals("Valid cue", subtitles.cues.single().text)
    }

    @Test
    fun rejectsInvalidHeader() {
        assertTrue(WebVttParser.parse("WEBVTT-invalid\n\n00:00.000 --> 00:01.000\nText").cues.isEmpty())
    }
}
