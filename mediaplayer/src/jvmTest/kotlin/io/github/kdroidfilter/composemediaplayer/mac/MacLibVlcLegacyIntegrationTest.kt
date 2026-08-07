package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoProjectionDisplayMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.composemediaplayer.VideoStereoLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MacLibVlcLegacyIntegrationTest {
    @Test
    fun playsConfiguredAviWmvAndWmaProInATaoHostedNativeView() {
        if (!isMacArm64()) return
        val inputs = configuredMedia()
        if (inputs.isEmpty()) return

        assertTrue(inputs.any { it.extension() == "avi" }, "The configured fixtures contain no AVI file.")
        assertTrue(inputs.any { it.extension() in ASF_EXTENSIONS }, "The configured fixtures contain no WMV/ASF file.")
        inputs.forEach(::verifyNativeLibVlcPlayback)
    }

    @Test
    fun changesConfiguredLibVlcFromNativeViewToControlledFisheyeProjection() {
        if (!isMacArm64()) return
        val media =
            System
                .getProperty(VR_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf(Files::isRegularFile)
                ?: return
        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.LIBVLC_NATIVE,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                    ),
            )
        var nativeView = 0L
        try {
            player.onResized(width = 1280, height = 720)
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("libVLC did not start the configured VR fixture.") {
                player.hasMedia && player.isPlaying && !player.isLoading
            }
            nativeView = player.createNativeVideoView(CONTENT_SCALE_FIT)
            assertTrue(nativeView != 0L, "Flat libVLC playback did not start in its native view.")

            player.projection =
                VideoProjectionSettings(
                    projectionType = VideoProjectionType.Fisheye190,
                    stereoLayout = VideoStereoLayout.SideBySide,
                    displayMode = VideoProjectionDisplayMode.MonoscopicLeft,
                )

            await("libVLC did not reopen through the controlled fisheye renderer.") {
                player.renderingInfo.backend
                    .orEmpty()
                    .contains("controlled projection") &&
                    !player.shouldUseLibVlcNativeSurface() &&
                    player.currentFrameState.value != null
            }
            val frame = assertNotNull(player.currentFrameState.value)
            assertTrue(frame.width <= 1280, "The controlled libVLC frame exceeded the requested viewport width.")
            assertTrue(frame.height <= 720, "The controlled libVLC frame exceeded the requested viewport height.")
        } finally {
            if (nativeView != 0L) runCatching { player.disposeNativeVideoView(nativeView) }
            player.dispose()
        }
    }

    private fun verifyNativeLibVlcPlayback(media: Path) {
        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.LIBVLC_NATIVE,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                    ),
            )
        var nativeView = 0L
        try {
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("libVLC did not start ${media.fileName}.") {
                player.hasMedia &&
                    player.isPlaying &&
                    !player.isLoading &&
                    player.currentTime >= 250.milliseconds
            }
            assertEquals(null, player.error, "libVLC reported a playback error for ${media.fileName}.")
            assertContains(player.renderingInfo.backend.orEmpty(), "libVLC")
            assertNotNull(player.currentAudioTrack, "libVLC did not select an audio track for ${media.fileName}.")
            assertTrue(player.availableAudioTracks.isNotEmpty(), "libVLC found no audio track in ${media.fileName}.")

            nativeView = player.createNativeVideoView(CONTENT_SCALE_FIT)
            assertTrue(nativeView != 0L, "The native libVLC NSView was not created for ${media.fileName}.")
            val timeBeforeObservation = player.currentTime
            await("libVLC stalled after native-view creation for ${media.fileName}.") {
                player.currentTime >= timeBeforeObservation + 250.milliseconds
            }
        } finally {
            if (nativeView != 0L) runCatching { player.disposeNativeVideoView(nativeView) }
            player.dispose()
        }
    }

    private fun configuredMedia(): List<Path> {
        val legacy =
            System
                .getProperty(LEGACY_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.legacyMediaFiles()
                .orEmpty()
        val wmaPro =
            System
                .getProperty(WMAPRO_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf(Files::isRegularFile)
        return (legacy + listOfNotNull(wmaPro)).distinct()
    }

    private fun Path.legacyMediaFiles(): List<Path> =
        if (Files.isDirectory(this)) {
            Files.list(this).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { it.extension() in LEGACY_EXTENSIONS }
                    .sorted()
                    .toList()
            }
        } else {
            listOf(this).filter { Files.isRegularFile(it) && it.extension() in LEGACY_EXTENSIONS }
        }

    private fun Path.extension(): String = fileName.toString().substringAfterLast('.', "").lowercase()

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
        const val LEGACY_MEDIA_PROPERTY = "composemediaplayer.test.legacyMedia"
        const val WMAPRO_MEDIA_PROPERTY = "composemediaplayer.test.wmaProMedia"
        const val VR_MEDIA_PROPERTY = "composemediaplayer.test.vrMedia"
        const val CONTENT_SCALE_FIT = 0
        const val POLL_INTERVAL_MILLIS = 50L
        const val TEST_TIMEOUT_NANOS = 15_000_000_000L
        val ASF_EXTENSIONS = setOf("wmv", "asf")
        val LEGACY_EXTENSIONS = ASF_EXTENSIONS + "avi"
    }
}
