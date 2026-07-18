package io.github.kdroidfilter.composemediaplayer.subtitle

import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleFont
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleRenderer
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAssSubtitleSessionTest {
    @Test
    fun configuresFontsAndBlendsThroughDesktopExtension() {
        val renderer = RecordingRenderer()
        val session = DesktopAssSubtitleSession(listOf(FakeExtension(renderer)))
        val font = DesktopSubtitleFont("Fixture.ttf", byteArrayOf(1, 2, 3))

        val description =
            session.configure(
                track = ASS_TRACK,
                content = SAMPLE_ASS,
                fonts = listOf(font),
            )

        assertEquals(renderer.backendDescription, description)
        assertTrue(session.active)
        assertEquals(listOf(font), renderer.fonts)
        assertTrue(renderer.trackData?.decodeToString()?.contains("[Events]") == true)

        val frame = ByteBuffer.allocateDirect(16 * 9 * 4)
        assertEquals(
            DesktopAssBlendResult.Rendered,
            session.blend(frame, 16 * 4, 16, 9, 1_250L),
        )
        assertEquals(1_250L, renderer.lastTimeMs)
    }

    @Test
    fun failedRendererIsClosedAndSessionFallsBack() {
        val renderer = RecordingRenderer(renderResult = false)
        val session = DesktopAssSubtitleSession(listOf(FakeExtension(renderer)))
        session.configure(ASS_TRACK, SAMPLE_ASS)

        val frame = ByteBuffer.allocateDirect(4 * 4 * 4)
        assertEquals(
            DesktopAssBlendResult.Failed,
            session.blend(frame, 4 * 4, 4, 4, 0L),
        )
        assertFalse(session.active)
        assertTrue(renderer.closed)
        assertEquals(
            DesktopAssBlendResult.Inactive,
            session.blend(frame, 4 * 4, 4, 4, 0L),
        )
    }

    @Test
    fun staleSelectionCannotClearNewRendererOwner() {
        val renderer = RecordingRenderer()
        val session = DesktopAssSubtitleSession(listOf(FakeExtension(renderer)))
        session.configure(ASS_TRACK, SAMPLE_ASS, ownerToken = 1L)
        session.configure(ASS_TRACK, SAMPLE_ASS, ownerToken = 2L)

        session.clear(ownerToken = 1L)
        assertTrue(session.active)
        assertFalse(renderer.closed)

        session.clear(ownerToken = 2L)
        assertFalse(session.active)
        assertTrue(renderer.closed)
    }

    @Test
    fun recognizesExplicitAndFileNamedAssTracks() {
        assertTrue(isAssLikeDesktopTrack(ASS_TRACK))
        assertTrue(
            isAssLikeDesktopTrack(
                ASS_TRACK.copy(
                    src = "https://example.test/subtitles/dialogue.ssa?token=x",
                    format = SubtitleFormat.AUTO,
                ),
            ),
        )
        assertFalse(isAssLikeDesktopTrack(ASS_TRACK.copy(src = "captions.vtt", format = SubtitleFormat.WEBVTT)))
    }

    private class FakeExtension(
        private val renderer: DesktopSubtitleRenderer,
    ) : DesktopSubtitlePipelineExtension {
        override val id: String = "fake-ass"
        override val availability: VideoPipelineExtensionAvailability =
            VideoPipelineExtensionAvailability.Available
        override val supportedSubtitleFormats: Set<SubtitleFormat> =
            setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)

        override fun createRenderer(): DesktopSubtitleRenderer = renderer
    }

    private class RecordingRenderer(
        private val renderResult: Boolean = true,
    ) : DesktopSubtitleRenderer {
        override val backendDescription: String = "fixture libass"
        val fonts = mutableListOf<DesktopSubtitleFont>()
        var trackData: ByteArray? = null
        var lastTimeMs: Long? = null
        var closed = false

        override fun addFont(font: DesktopSubtitleFont): Boolean {
            fonts += font
            return true
        }

        override fun setTrack(data: ByteArray): Boolean {
            trackData = data
            return true
        }

        override fun blendBgraFrame(
            pixels: ByteBuffer,
            rowBytes: Int,
            width: Int,
            height: Int,
            timeMs: Long,
        ): Boolean {
            lastTimeMs = timeMs
            return renderResult
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val ASS_TRACK =
            SubtitleTrack(
                label = "Polish",
                language = "pl",
                src = "captions.ass",
                format = SubtitleFormat.ASS,
            )
        val SAMPLE_ASS =
            """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,KMediaPlayer
            """.trimIndent()
    }
}
