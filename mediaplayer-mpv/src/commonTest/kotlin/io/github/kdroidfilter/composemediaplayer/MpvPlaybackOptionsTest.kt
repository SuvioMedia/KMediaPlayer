package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MpvPlaybackOptionsTest {
    @Test
    fun defaultsKeepAssStylesAndEmbeddedFonts() {
        val options = MpvPlaybackOptions()

        assertEquals(true, options.preserveAssStyles)
        assertEquals(true, options.useEmbeddedFonts)
        assertEquals(MpvAndroidDecodeMode.MEDIA_CODEC_COPY, options.androidDecodeMode)
        assertEquals(MpvMacRenderer.MOLTENVK, options.macRenderer)
        assertEquals(MpvIosRenderer.MOLTENVK, options.iosRenderer)
        assertEquals(
            MpvPlaybackOptions.DEFAULT_MAX_DESKTOP_RENDER_PIXELS,
            options.maxDesktopRenderPixels,
        )
        assertEquals(MpvRuntimeSource.Bundled, options.runtimeSource)
        assertEquals(null, options.desktopRuntimeDirectory)
    }

    @Test
    fun rejectsUnsafeOrUnboundedConfiguration() {
        assertFailsWith<IllegalArgumentException> {
            MpvPlaybackOptions(subtitleFontsDirectory = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            MpvPlaybackOptions(subtitleFontsDirectory = "/fonts\u0000escape")
        }
        assertFailsWith<IllegalArgumentException> {
            MpvPlaybackOptions(maxDesktopRenderPixels = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MpvPlaybackOptions(desktopRuntimeDirectory = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            MpvPlaybackOptions(desktopRuntimeDirectory = "/runtime\u0000escape")
        }
        assertFailsWith<IllegalArgumentException> {
            MpvPlaybackOptions(
                maxDesktopRenderPixels = MpvPlaybackOptions.MAX_DESKTOP_RENDER_PIXELS + 1,
            )
        }
    }
}
