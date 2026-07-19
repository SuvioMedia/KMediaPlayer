package io.github.kdroidfilter.composemediaplayer

import android.net.Uri
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidHlsChaptersTest {
    @Test
    fun `parses Apple session-data chapter URI relative to master playlist`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-SESSION-DATA:DATA-ID="com.apple.hls.chapters",LANGUAGE="en",URI="../meta/chapters.json"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000
            media.m3u8
            """.trimIndent()

        val uri =
            parseHlsChapterJsonUri(
                masterPlaylistUri = Uri.parse("https://cdn.example.test/hls/master.m3u8"),
                manifestText = playlist,
                variableDefinitions = emptyMap(),
            )

        assertEquals(Uri.parse("https://cdn.example.test/meta/chapters.json"), uri)
    }

    @Test
    fun `ignores unrelated HLS session data`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-SESSION-DATA:DATA-ID="example.metadata",URI="metadata.json"
            """.trimIndent()

        assertNull(
            parseHlsChapterJsonUri(
                masterPlaylistUri = Uri.parse("https://example.test/master.m3u8"),
                manifestText = playlist,
                variableDefinitions = emptyMap(),
            ),
        )
    }

    @Test
    fun `selects localized titles and keeps absent duration open`() {
        val rows =
            parseAndroidHlsChapterJson(
                json =
                    """
                    [
                      {
                        "start-time": 2.5,
                        "duration": 7.5,
                        "titles": [
                          {"language": "en", "title": "Introduction"},
                          {"language": "pl", "title": "Wprowadzenie"}
                        ]
                      },
                      {
                        "start-time": 15,
                        "titles": [{"language": "und", "title": "Finale"}]
                      }
                    ]
                    """.trimIndent(),
                preferredLanguages = listOf("pl-PL"),
            )

        assertEquals(2_500, rows[0].startMs)
        assertEquals(10_000, rows[0].endMs)
        assertEquals("Wprowadzenie", rows[0].title)
        assertEquals("pl", rows[0].language)
        assertEquals(15_000, rows[1].startMs)
        assertNull(rows[1].endMs)
    }

    @Test
    fun `rejects invalid Apple chapter timestamps`() {
        val rows =
            parseAndroidHlsChapterJson(
                json =
                    """
                    [
                      {"start-time": -1, "titles": [{"title": "negative"}]},
                      {"start-time": "3", "titles": [{"title": "wrong type"}]},
                      {"start-time": 4, "duration": -2, "titles": [{"title": "valid open end"}]}
                    ]
                    """.trimIndent(),
                preferredLanguages = emptyList(),
            )

        assertEquals(1, rows.size)
        assertEquals(4_000, rows.single().startMs)
        assertNull(rows.single().endMs)
    }
}
