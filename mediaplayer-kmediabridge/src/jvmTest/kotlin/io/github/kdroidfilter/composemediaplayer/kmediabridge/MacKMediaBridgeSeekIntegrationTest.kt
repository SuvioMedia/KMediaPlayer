package io.github.kdroidfilter.composemediaplayer.kmediabridge

import io.github.kdroidfilter.composemediaplayer.DesktopMediaSourcePolicy
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.JvmMediaTools
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.mac.MacVideoPlayerState
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MacKMediaBridgeSeekIntegrationTest {
    @Test
    fun vlcHlsUsesKMediaBridgeForTimestampStableMacSeek() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val media = configuredWmv() ?: return
        val extension = configuredTestExtension()
        if (!JvmMediaTools.query(listOf(extension)).vlc.available) return
        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                        desktopMediaSourcePolicy = DesktopMediaSourcePolicy.VLC_HLS,
                        extensions = listOf(extension),
                    ),
            )

        try {
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("WMV did not start through VLC HLS and AVFoundation.") {
                player.error != null || (player.hasMedia && player.isPlaying && !player.isLoading)
            }
            assertNull(player.error)
            assertContains(player.renderingInfo.backend.orEmpty(), "VLC HLS")
            assertTrue(player.availableAudioTracks.isNotEmpty(), "The adapted WMV audio track was not exposed.")

            val durationMs = player.duration.inWholeMilliseconds
            assertTrue(durationMs >= MINIMUM_FIXTURE_DURATION_MS, "The WMV seek fixture is too short.")
            val seekTargetMs =
                (durationMs * FORWARD_SEEK_NUMERATOR / FORWARD_SEEK_DENOMINATOR)
                    .coerceIn(MINIMUM_FORWARD_TARGET_MS, durationMs - END_GUARD_MS)
            player.seekTo(seekTargetMs.milliseconds)

            await("VLC HLS seek did not restart through the timestamp-stable media bridge.") {
                player.error != null ||
                    (
                        !player.isLoading &&
                            abs(player.currentTime.inWholeMilliseconds - seekTargetMs) <= PLAYING_SEEK_TOLERANCE_MS
                    )
            }
            assertNull(player.error)
            assertContains(player.renderingInfo.backend.orEmpty(), "KMediaBridge")
            assertTrue(player.isPlaying, "VLC HLS playback must remain playing after the normalized seek.")
        } finally {
            player.dispose()
        }
    }

    @Test
    fun seeksWmvByRestartingTheBridgeWithoutReplacingAvFoundation() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val media = configuredWmv() ?: return
        val extension = configuredTestExtension()
        val player =
            MacVideoPlayerState(
                playbackOptions =
                    VideoPlaybackOptions(
                        desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_NATIVE,
                        desktopMediaSourcePolicy = DesktopMediaSourcePolicy.KMEDIA_BRIDGE,
                        extensions = listOf(extension),
                    ),
            )

        try {
            player.openUri(media.toUri().toString(), InitialPlayerState.PLAY)
            await("WMV did not start through KMediaBridge and AVFoundation.") {
                player.error != null || (player.hasMedia && player.isPlaying && !player.isLoading)
            }
            assertNull(player.error)
            assertContains(player.renderingInfo.backend.orEmpty(), "media bridge", ignoreCase = true)
            await("WMV playback did not advance before seek.") {
                player.currentTime >= 500.milliseconds
            }

            val durationMs = player.duration.inWholeMilliseconds
            assertTrue(durationMs >= MINIMUM_FIXTURE_DURATION_MS, "The WMV seek fixture is too short.")
            val forwardTargetMs =
                (durationMs * FORWARD_SEEK_NUMERATOR / FORWARD_SEEK_DENOMINATOR)
                    .coerceIn(MINIMUM_FORWARD_TARGET_MS, durationMs - END_GUARD_MS)

            player.seekTo(forwardTargetMs.milliseconds)
            await("Forward WMV seek did not restart the bridge at the requested source position.") {
                !player.isLoading &&
                    abs(player.currentTime.inWholeMilliseconds - forwardTargetMs) <= PLAYING_SEEK_TOLERANCE_MS
            }
            assertTrue(player.isPlaying, "A playing WMV must remain playing after bridge seek.")

            player.pause()
            await("WMV did not pause before the backward seek.") { !player.isPlaying }
            player.seekTo(PAUSED_BACKWARD_TARGET_MS.milliseconds)
            await("Paused backward WMV seek did not publish the restarted timeline position.") {
                !player.isLoading &&
                    abs(player.currentTime.inWholeMilliseconds - PAUSED_BACKWARD_TARGET_MS) <=
                    PAUSED_SEEK_TOLERANCE_MS
            }
            assertFalse(player.isPlaying, "A paused WMV must remain paused after bridge seek.")
            assertNull(player.error)
        } finally {
            player.dispose()
        }
    }

    private fun configuredWmv(): Path? {
        val configured =
            System
                .getProperty(LEGACY_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: return null
        if (Files.isRegularFile(configured)) {
            return configured.takeIf { it.extension() in ASF_EXTENSIONS }
        }
        if (!Files.isDirectory(configured)) return null
        return Files.list(configured).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.extension() in ASF_EXTENSIONS }
                .sorted()
                .findFirst()
                .orElse(null)
        }
    }

    private fun configuredTestExtension(): KMediaBridgeDesktopExtension =
        System
            .getProperty(KMEDIA_BRIDGE_RUNTIME_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.let(KMediaBridgeDesktopRuntimeSelection::fromExternalDirectory)
            ?.let(::KMediaBridgeDesktopExtension)
            ?: KMediaBridgeDesktopExtension()

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(condition(), message)
    }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        val architecture = System.getProperty("os.arch", "").lowercase()
        return (os.contains("mac") || os.contains("darwin")) && architecture in setOf("aarch64", "arm64")
    }

    private fun Path.extension(): String = fileName.toString().substringAfterLast('.', "").lowercase()

    private companion object {
        const val LEGACY_MEDIA_PROPERTY = "composemediaplayer.test.legacyMedia"
        const val KMEDIA_BRIDGE_RUNTIME_PROPERTY = "composemediaplayer.test.kMediaBridgeRuntimeDirectory"
        const val MINIMUM_FIXTURE_DURATION_MS = 8_000L
        const val MINIMUM_FORWARD_TARGET_MS = 4_000L
        const val END_GUARD_MS = 2_000L
        const val FORWARD_SEEK_NUMERATOR = 2L
        const val FORWARD_SEEK_DENOMINATOR = 3L
        const val PAUSED_BACKWARD_TARGET_MS = 2_000L
        const val PLAYING_SEEK_TOLERANCE_MS = 2_500L
        const val PAUSED_SEEK_TOLERANCE_MS = 1_000L
        const val POLL_INTERVAL_MS = 50L
        const val TEST_TIMEOUT_NANOS = 30_000_000_000L
        val ASF_EXTENSIONS = setOf("wmv", "asf")
    }
}
