package io.github.kdroidfilter.composemediaplayer.ass

import io.github.kdroidfilter.composemediaplayer.DesktopSubtitlePipelineExtension
import io.github.shusek.kmediaffmpeg.runtime.KMediaAssRuntime
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleAssRendererTest {
    @Test
    fun sharedDesktopRendererBlendsAuthoredAssWhenPayloadIsPresent() {
        if (!isSupportedDesktop()) return
        assertTrue(SystemLibAssRuntime.isAvailable, SystemLibAssRuntime.failureDetail)
        assertEquals(
            "kmediaass-0.17.5-36443523f0148567",
            KMediaAssRuntime.current().orElseThrow().runtimeId(),
        )

        val extension: DesktopSubtitlePipelineExtension = AssSubtitleExtension()
        assertTrue(extension.availability.canContribute)
        val renderer = assertNotNull(extension.createRenderer())
        renderer.use {
            assertTrue(
                renderer.backendDescription.startsWith("shared libass"),
                "Expected shared libass, got ${renderer.backendDescription}.",
            )
            assertTrue(renderer.setTrack(SAMPLE_ASS.encodeToByteArray()))
            val width = 640
            val height = 360
            val frame = ByteBuffer.allocateDirect(width * height * BGRA_BYTES)
            assertTrue(
                renderer.blendBgraFrame(
                    pixels = frame,
                    rowBytes = width * BGRA_BYTES,
                    width = width,
                    height = height,
                    timeMs = 1_000L,
                ),
            )

            val bytes = ByteArray(frame.capacity())
            frame.get(bytes)
            assertTrue(
                bytes.indices.any { index ->
                    index % BGRA_BYTES != ALPHA_INDEX && bytes[index] != 0.toByte()
                },
                "Desktop libass returned no visible subtitle pixels.",
            )
        }
    }

    private companion object {
        const val BGRA_BYTES = 4
        const val ALPHA_INDEX = 3
        val SAMPLE_ASS =
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,24,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,KMediaPlayer
            """.trimIndent()
    }
}

private fun isSupportedDesktop(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return "mac" in osName || "darwin" in osName || "windows" in osName || "linux" in osName
}
