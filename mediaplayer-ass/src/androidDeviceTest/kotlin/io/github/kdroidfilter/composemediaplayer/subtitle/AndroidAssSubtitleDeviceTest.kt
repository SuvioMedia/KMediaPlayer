package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAssSubtitleDeviceTest {
    @Test
    fun bundledLibassRendersARealRgbaFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("The shared libass 0.17.5 backend must load", AndroidAssNativeBridge.isAvailable)

        AndroidAssNativeSession
            .external(context, ASS_CONTENT.encodeToByteArray())
            .use { session ->
                session.configure(
                    storageWidth = 1280,
                    storageHeight = 720,
                    frameWidth = 1280,
                    frameHeight = 720,
                )
                val frame = session.renderFrame(positionMs = 500L, force = true)
                assertTrue("Expected libass to return subtitle pixels", frame is AndroidAssRenderFrame.Pixels)
                frame as AndroidAssRenderFrame.Pixels
                assertTrue(frame.width > 0)
                assertTrue(frame.height > 0)
                assertTrue(frame.data.remaining() >= frame.stride * frame.height)
            }
    }

    private companion object {
        val ASS_CONTENT =
            """
            [Script Info]
            Title: Compose Media Player ASS sample
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,44

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:01:00.00,Default,,0,0,0,,ASS subtitles rendered on Android.
            Dialogue: 0,0:00:00.00,0:01:00.00,Default,,0,0,0,,Second active line.
            """.trimIndent()
    }
}
