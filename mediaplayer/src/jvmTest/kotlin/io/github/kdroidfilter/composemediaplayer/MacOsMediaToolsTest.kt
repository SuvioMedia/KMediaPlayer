package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacOsMediaToolsTest {
    @Test
    fun queryReturnsConsistentToolStatus() {
        val tools = MacOsMediaTools.query()

        listOf(
            tools.vlc,
            tools.libVlc,
            tools.ffmpeg,
            tools.ffmpegWithSubtitlesFilter,
            tools.ffprobe,
            tools.libass,
        ).forEach { status ->
            if (status.available) {
                assertTrue(status.path?.isNotBlank() == true)
            } else if (status.path == null) {
                assertTrue(status.detail?.isNotBlank() == true)
            }
        }

        if (tools.ffmpegWithSubtitlesFilter.available) {
            assertTrue(tools.ffmpeg.available)
        }
        if (tools.libVlc.available) {
            assertTrue(tools.libVlc.detail?.isNotBlank() == true)
        } else {
            assertFalse(tools.libVlc.available)
        }
    }
}
