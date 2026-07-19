package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MediaChapterTest {
    @Test
    fun exposesMillisecondConvenienceValues() {
        val chapter =
            MediaChapter(
                start = 1_250.milliseconds,
                end = 3_500.milliseconds,
                title = "Opening",
            )

        assertEquals(1_250L, chapter.startMs)
        assertEquals(3_500L, chapter.endMs)
        assertEquals(2_250.milliseconds, chapter.duration)
        assertEquals(2_250L, chapter.durationMs)
    }

    @Test
    fun rejectsInvalidRanges() {
        assertFailsWith<IllegalArgumentException> {
            MediaChapter(start = (-1).milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            MediaChapter(start = 2.seconds, end = 2.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            MediaChapter(start = Duration.INFINITE)
        }
        assertFailsWith<IllegalArgumentException> {
            MediaChapter(start = Duration.ZERO, end = Duration.INFINITE)
        }
    }

    @Test
    fun currentChapterUsesNextStartWhenEndIsMissing() {
        val first = MediaChapter(start = 0.seconds, title = "First")
        val second = MediaChapter(start = 10.seconds, title = "Second")
        val state =
            PreviewableVideoPlayerState(
                hasMedia = true,
                currentTime = 10.seconds,
                duration = 20.seconds,
            )
        val chaptersState =
            object : VideoPlayerState by state {
                override val chapters = listOf(first, second)
                override val currentChapter: MediaChapter?
                    get() = super<VideoPlayerState>.currentChapter
            }

        assertEquals(second, chaptersState.currentChapter)
    }

    @Test
    fun currentChapterPreservesExplicitGaps() {
        val state =
            PreviewableVideoPlayerState(
                hasMedia = true,
                currentTime = 7.seconds,
                duration = 20.seconds,
            )
        val chaptersState =
            object : VideoPlayerState by state {
                override val chapters =
                    listOf(
                        MediaChapter(start = 0.seconds, end = 5.seconds),
                        MediaChapter(start = 10.seconds, end = 15.seconds),
                    )
                override val currentChapter: MediaChapter?
                    get() = super<VideoPlayerState>.currentChapter
            }

        assertNull(chaptersState.currentChapter)
    }

    @Test
    fun overlappingCurrentChapterPrefersLatestMostSpecificRange() {
        val broad = MediaChapter(start = 0.seconds, end = 20.seconds, title = "Part")
        val specific = MediaChapter(start = 5.seconds, end = 10.seconds, title = "Scene")
        val state =
            PreviewableVideoPlayerState(
                hasMedia = true,
                currentTime = 7.seconds,
                duration = 20.seconds,
            )
        val chaptersState =
            object : VideoPlayerState by state {
                override val chapters = listOf(broad, specific)
                override val currentChapter: MediaChapter?
                    get() = super<VideoPlayerState>.currentChapter
            }

        assertEquals(specific, chaptersState.currentChapter)
    }
}
