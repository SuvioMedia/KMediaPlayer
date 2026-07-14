package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration

class RenderableVideoPlayerStateTest {
    @Test
    fun previewStateKeepsTheLegacyVideoPlayerStateContract() {
        val preview = PreviewableVideoPlayerState()
        val legacyState: VideoPlayerState = preview

        assertSame(preview, legacyState)
    }

    @Test
    @Suppress("LongMethod")
    fun previewCommandsRejectUseAfterDisposeWithThePlatformContractMessage() =
        runTest {
            val preview = PreviewableVideoPlayerState()
            preview.dispose()
            preview.dispose()

            val operations =
                listOf<() -> Unit>(
                    preview::play,
                    preview::pause,
                    preview::stop,
                    preview::releaseSource,
                    { preview.seekTo(Duration.ZERO) },
                    { preview.seekStart(0f) },
                    preview::seekFinished,
                    preview::toggleFullscreen,
                    { preview.openUri("https://example.test/video.mp4") },
                    preview::clearError,
                    { preview.selectAudioTrack("missing") },
                    { preview.selectSubtitleTrack("missing") },
                    { preview.addSubtitleTrack(SubtitleTrack("English", "en", "captions.vtt")) },
                    { preview.removeSubtitleTrack("captions.vtt") },
                    preview::clearExternalSubtitleTracks,
                    { preview.disableSubtitles() },
                    { preview.selectHlsQuality(null) },
                    { preview.clearCache() },
                    { preview.subtitleOffset = Duration.ZERO },
                )

            operations.forEach { operation ->
                val error = assertFailsWith<IllegalStateException>(block = operation)
                assertEquals("VideoPlayerState has been disposed", error.message)
            }

            val pipError = assertFailsWith<IllegalStateException> { preview.enterPip() }
            assertEquals("VideoPlayerState has been disposed", pipError.message)
        }

    @Test
    @Suppress("MagicNumber")
    fun previewPrimaryConstructorSettersRemainMutableAfterDisposeForBinaryCompatibility() {
        val preview = PreviewableVideoPlayerState()
        preview.dispose()

        preview.volume = 0.5f

        assertEquals(0.5f, preview.volume)
    }
}
