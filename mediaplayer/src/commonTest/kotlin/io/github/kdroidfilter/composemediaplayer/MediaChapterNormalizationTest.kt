package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class MediaChapterNormalizationTest {
    @Test
    fun normalizesSortsDeduplicatesAndInfersEnds() {
        val chapters =
            normalizeMediaChapters(
                rows =
                    listOf(
                        RawMediaChapter(startMs = 10_000, title = "Second"),
                        RawMediaChapter(startMs = 0, title = "First"),
                        RawMediaChapter(startMs = 0, title = "First"),
                        RawMediaChapter(startMs = -1, title = "Invalid"),
                    ),
                mediaDuration = 20.seconds,
            )

        assertEquals(listOf("First", "Second"), chapters.map(MediaChapter::title))
        assertEquals(listOf(10_000L, 20_000L), chapters.map(MediaChapter::endMs))
    }

    @Test
    fun clipsExplicitEndAndDropsMalformedRows() {
        val chapters =
            normalizeMediaChapters(
                rows =
                    listOf(
                        RawMediaChapter(startMs = 1_000, endMs = 50_000, title = "Clipped"),
                        RawMediaChapter(startMs = 5_000, endMs = 4_000, title = "Malformed"),
                        RawMediaChapter(startMs = 20_000, title = "Outside"),
                    ),
                mediaDuration = 10.seconds,
            )

        assertEquals(1, chapters.size)
        assertEquals(10_000L, chapters.single().endMs)
    }

    @Test
    fun choosesBestLanguageWithStableFallbacks() {
        val labels =
            listOf(
                MediaChapterLabel("English", "en"),
                MediaChapterLabel("Polski", "pl-PL"),
                MediaChapterLabel("Neutral", "und"),
            )

        assertEquals("Polski", selectPreferredChapterLabel(labels, listOf("pl"))?.text)
        assertEquals("Neutral", selectPreferredChapterLabel(labels, listOf("de"))?.text)
    }
}
