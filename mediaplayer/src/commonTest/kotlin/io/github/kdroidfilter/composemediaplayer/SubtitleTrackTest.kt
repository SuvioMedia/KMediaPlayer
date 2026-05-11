package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubtitleTrackTest {
    @Test
    fun testSubtitleTrackCreation() {
        val track =
            SubtitleTrack(
                label = "English",
                language = "en",
                src = "subtitles/en.vtt",
            )

        assertEquals("English", track.label)
        assertEquals("en", track.language)
        assertEquals("subtitles/en.vtt", track.src)
        assertEquals(SubtitleFormat.AUTO, track.format)
        assertEquals(SubtitleFormat.WEBVTT, track.resolvedFormat())
        assertEquals("subtitles/en.vtt", track.id)
    }

    @Test
    fun testSubtitleTrackEquality() {
        val track1 =
            SubtitleTrack(
                label = "English",
                language = "en",
                src = "subtitles/en.vtt",
            )

        val track2 =
            SubtitleTrack(
                label = "English",
                language = "en",
                src = "subtitles/en.vtt",
            )

        val track3 =
            SubtitleTrack(
                label = "French",
                language = "fr",
                src = "subtitles/fr.vtt",
            )

        assertEquals(track1, track2, "Identical subtitle tracks should be equal")
        assertNotEquals(track1, track3, "Different subtitle tracks should not be equal")
    }

    @Test
    fun testSubtitleTrackCopy() {
        val original =
            SubtitleTrack(
                label = "English",
                language = "en",
                src = "subtitles/en.vtt",
            )

        val copy = original.copy(label = "English (US)")

        assertEquals("English (US)", copy.label)
        assertEquals(original.language, copy.language)
        assertEquals(original.src, copy.src)

        // Original should remain unchanged
        assertEquals("English", original.label)
    }

    @Test
    fun testSubtitleTrackToString() {
        val track =
            SubtitleTrack(
                label = "English",
                language = "en",
                src = "subtitles/en.vtt",
            )

        val toString = track.toString()

        // Verify that toString contains all the properties
        assertTrue(toString.contains("English"))
        assertTrue(toString.contains("en"))
        assertTrue(toString.contains("subtitles/en.vtt"))
    }

    @Test
    fun testSubtitleFormatDetection() {
        assertEquals(
            SubtitleFormat.ASS,
            SubtitleFormat.fromSource(src = "blob:local", label = "episode.ass"),
        )
        assertEquals(
            SubtitleFormat.SSA,
            SubtitleFormat.fromSource(src = "https://example.com/subtitle.ssa?token=1"),
        )
        assertEquals(
            SubtitleFormat.SRT,
            SubtitleFormat.fromContent("1\n00:00:01,000 --> 00:00:02,000\nHello"),
        )
        assertEquals(
            SubtitleFormat.ASS,
            SubtitleFormat.fromContent("[Script Info]\nTitle: Demo"),
        )
    }

    @Test
    fun testEmbeddedSubtitleTrackCreation() {
        val track =
            SubtitleTrack(
                label = "English CC",
                language = "en",
                src = "",
                id = "web:text:0",
                isEmbedded = true,
                kind = "captions",
            )

        assertEquals("web:text:0", track.id)
        assertTrue(track.isEmbedded)
        assertEquals("captions", track.kind)
    }

    @Test
    fun testAudioTrackCreation() {
        val track =
            AudioTrack(
                id = "web:audio:0",
                label = "English",
                language = "en",
                channels = 2,
                sampleRate = 48000,
            )

        assertEquals("web:audio:0", track.id)
        assertEquals("English", track.label)
        assertEquals("en", track.language)
        assertEquals(2, track.channels)
        assertEquals(48000, track.sampleRate)
    }
}
