package io.github.kdroidfilter.composemediaplayer

import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.ChapterTocFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidMediaChaptersTest {
    @Test
    fun `maps Media3 generic Chapter metadata and period offset`() {
        val frame =
            ChapterFrame(
                "chapter-1",
                1_000,
                5_000,
                -1L,
                -1L,
                arrayOf<Id3Frame>(
                    TextInformationFrame("TIT2", null, listOf("Opening")),
                ),
            )

        val chapter = Metadata(frame).media3ChapterRows(periodOffsetMs = 250).single()

        assertEquals(1_250, chapter.startMs)
        assertEquals(5_250, chapter.endMs)
        assertEquals("Opening", chapter.title)
        assertNull(chapter.language)
        assertFalse(chapter.isHidden)
    }

    @Test
    fun `keeps an unknown ID3 chapter end open`() {
        val frame =
            object : Chapter {
                override fun getStartTimeMs(): Long = 3_000L

                override fun getEndTimeMs(): Long = -1L

                override fun isHidden(): Boolean = false

                override fun getTitle(): Label? = null
            }

        val chapter = Metadata(frame).media3ChapterRows().single()

        assertEquals(3_000, chapter.startMs)
        assertNull(chapter.endMs)
    }

    @Test
    fun `flattens an ID3 CTOC hierarchy in its declared order`() {
        val second = chapterFrame(id = "second", endMs = 3_000, title = "Second")
        val first = chapterFrame(id = "first", endMs = 2_000, title = "First")
        val table =
            ChapterTocFrame(
                "root",
                true,
                true,
                arrayOf("first", "second"),
                emptyArray(),
            )

        val chapters = Metadata(second, first, table).media3ChapterRows()

        assertEquals(listOf("First", "Second"), chapters.map(RawMediaChapter::title))
    }

    private fun chapterFrame(
        id: String,
        endMs: Int,
        title: String,
    ): ChapterFrame =
        ChapterFrame(
            id,
            1_000,
            endMs,
            -1L,
            -1L,
            arrayOf<Id3Frame>(
                TextInformationFrame("TIT2", null, listOf(title)),
            ),
        )
}
