package io.github.kdroidfilter.composemediaplayer.ass

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleAssNativeSessionTest {
    @Test
    fun bundledRendererCreatesStyledRgbaFrame() {
        assertTrue(AppleAssNativeSession.isRuntimeAvailable)
        val session = AppleAssNativeSession.create(SAMPLE_ASS.encodeToByteArray())
        try {
            val frame =
                assertNotNull(
                    session.render(
                        width = 640,
                        height = 360,
                        timeMs = 1_000L,
                    ),
                )
            assertTrue(frame.width > 0)
            assertTrue(frame.height > 0)
            assertTrue(frame.x >= 0)
            assertTrue(frame.y >= 0)
        } finally {
            session.close()
        }
    }

    private companion object {
        val SAMPLE_ASS =
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,38,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,24,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,KMediaPlayer — مرحبا
            """.trimIndent()
    }
}
