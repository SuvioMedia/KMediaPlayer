package io.github.kdroidfilter.composemediaplayer.ass

import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleFont
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitlePipelineExtension
import io.github.shusek.kmediaffmpeg.runtime.KMediaAssRuntime
import java.awt.Font
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
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
            "kmediaass-0.17.5-132a1d9ab8838bbd",
            KMediaAssRuntime.current().orElseThrow().runtimeId(),
        )

        val extension: DesktopSubtitlePipelineExtension = AssSubtitleExtension()
        assertTrue(extension.availability.canContribute)
        val font = loadHostTestFont()
        val renderer = assertNotNull(extension.createRenderer())
        renderer.use {
            assertTrue(
                renderer.backendDescription.startsWith("shared libass"),
                "Expected shared libass, got ${renderer.backendDescription}.",
            )
            assertTrue(
                renderer.addFont(
                    DesktopSubtitleFont(
                        name = font.fileName,
                        data = font.data,
                    ),
                ),
            )
            assertTrue(renderer.setTrack(sampleAss(font.family).encodeToByteArray()))
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

        fun sampleAss(fontFamily: String): String =
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,$fontFamily,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,24,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,KMediaPlayer
            """.trimIndent()
    }
}

private data class HostTestFont(
    val family: String,
    val fileName: String,
    val data: ByteArray,
)

private val HOST_FONT_CANDIDATES =
    listOf(
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf",
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        "/System/Library/Fonts/SFNS.ttf",
        "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/segoeui.ttf",
    )

private fun loadHostTestFont(): HostTestFont =
    HOST_FONT_CANDIDATES
        .asSequence()
        .map(Path::of)
        .mapNotNull(::readHostTestFont)
        .firstOrNull()
        ?: error("No supported host font was available for the native libass rendering test.")

private fun readHostTestFont(path: Path): HostTestFont? {
    if (!Files.isRegularFile(path)) return null
    return runCatching {
        val data = Files.readAllBytes(path)
        val family =
            ByteArrayInputStream(data).use { input ->
                Font.createFont(Font.TRUETYPE_FONT, input).family.trim()
            }
        require(family.isNotEmpty() && ',' !in family && '\n' !in family && '\r' !in family)
        HostTestFont(
            family = family,
            fileName = path.fileName.toString(),
            data = data,
        )
    }.getOrNull()
}

private fun isSupportedDesktop(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return "mac" in osName || "darwin" in osName || "windows" in osName || "linux" in osName
}
