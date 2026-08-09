@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import dev.nucleusframework.window.WindowDynamicRangeMode
import dev.nucleusframework.window.tao.MacTextureViewProducerInfo
import dev.nucleusframework.window.tao.TextureViewHostCapabilities
import dev.nucleusframework.window.tao.TextureViewHostDynamicRange
import dev.nucleusframework.window.tao.TextureViewHostPixelFormat
import dev.nucleusframework.window.tao.TextureViewHostPresentationState
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MpvMacTextureIntegrationTest {
    @Test
    fun rendersIosurfaceAndConfirmsOnlyAfterHostPresent() {
        if (!isMacArm64()) return
        val library = configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        val media = configuredFile(TEXTURE_MEDIA_PROPERTY) ?: return
        val player = createExplicitMpvPlayer(library)

        try {
            player.onMacTextureHostCapabilitiesChanged(macHost(presentedFrames = 0))
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not start before IOSurface rendering.") {
                player.hasMedia && player.isPlaying && player.currentTime >= 250.milliseconds
            }
            await("MPV did not produce an IOSurface texture frame.") {
                player.renderMacTextureFrame(RENDER_WIDTH, RENDER_HEIGHT)
            }
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), "TextureView")
            assertEquals(VideoSurfaceKind.TEXTURE_VIEW, player.colorPipelineStatus.value.surface)
            assertEquals(ColorPipelineVerification.NONE, player.colorPipelineStatus.value.verification)

            player.onMacTextureHostCapabilitiesChanged(macHost(presentedFrames = 1))

            assertEquals(
                ColorPipelineVerification.RENDERER_CONFIGURED,
                player.colorPipelineStatus.value.verification,
            )
            assertEquals(null, player.error)
        } finally {
            player.dispose()
        }
    }

    @Test
    fun disposingIosurfaceProducerAllowsPlatformBackendHandoff() {
        if (!isMacArm64()) return
        val library = configuredFile(MPV_LIBRARY_PROPERTY) ?: return
        val media = configuredFile(TEXTURE_MEDIA_PROPERTY) ?: return
        val mpvPlayer = createExplicitMpvPlayer(library)
        var platformPlayer: VideoPlayerState? = null

        try {
            mpvPlayer.onMacTextureHostCapabilitiesChanged(macHost(presentedFrames = 0))
            mpvPlayer.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not start before backend handoff.") {
                mpvPlayer.hasMedia && mpvPlayer.currentTime >= 250.milliseconds
            }
            assertTrue(mpvPlayer.renderMacTextureFrame(RENDER_WIDTH, RENDER_HEIGHT))

            // Closing the stream releases all outstanding IOSurface leases before renderer teardown.
            mpvPlayer.dispose()
            platformPlayer =
                createVideoPlayerState(
                    playbackOptions =
                        VideoPlaybackOptions(desktopVideoBackend = DesktopVideoBackend.PLATFORM),
                )
            platformPlayer.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("AVFoundation did not start after the MPV texture producer was disposed.") {
                platformPlayer.hasMedia && platformPlayer.currentTime >= 250.milliseconds
            }
            assertEquals(null, platformPlayer.error)
        } finally {
            platformPlayer?.dispose()
            mpvPlayer.dispose()
        }
    }

    private fun macHost(presentedFrames: Long): TextureViewHostCapabilities =
        TextureViewHostCapabilities(
            requestedMode = WindowDynamicRangeMode.EXTENDED_IF_AVAILABLE,
            actualDynamicRange = TextureViewHostDynamicRange.HDR,
            presentationState = TextureViewHostPresentationState.PRESENTED,
            sdrWhiteLevelNits = 100f,
            maximumLuminanceNits = 1_000f,
            headroom = 10f,
            generation = HOST_GENERATION,
            presentedFrameCount = presentedFrames,
            outputPixelFormat = TextureViewHostPixelFormat.RGBA16_FLOAT_SCRGB,
            producerInfo = MacTextureViewProducerInfo(device = 1L, commandQueue = 1L),
        )

    private fun createExplicitMpvPlayer(library: Path): MpvVideoPlayerState =
        assertIs(
            createMpvVideoPlayerState(
                MpvPlaybackOptions(runtimeSource = MpvRuntimeSource.ExplicitPath(library.toString())),
            ),
        )

    private fun configuredFile(property: String): Path? =
        System
            .getProperty(property)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Files::isRegularFile)

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue(condition(), message)
    }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) && architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val MPV_LIBRARY_PROPERTY = "composemediaplayer.mpvLibraryPath"
        const val TEXTURE_MEDIA_PROPERTY = "composemediaplayer.nativeSurfaceTestMedia"
        const val HOST_GENERATION = 7L
        const val RENDER_WIDTH = 320
        const val RENDER_HEIGHT = 180
        const val POLL_INTERVAL_MILLIS = 25L
        const val TEST_TIMEOUT_NANOS = 15_000_000_000L
    }
}
