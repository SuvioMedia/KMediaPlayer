package io.github.kdroidfilter.composemediaplayer.kmediabridge

import io.github.kdroidfilter.composemediaplayer.DesktopMediaSourcePolicy
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeExtension
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeRequest
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeSession
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
    fun seeksWmvByRestartingTheBridgeWithoutDetachingTheNativeSurface() {
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
            val supersedingTargetMs =
                (durationMs / 2L).coerceIn(MINIMUM_FORWARD_TARGET_MS, durationMs - END_GUARD_MS)

            player.seekTo(forwardTargetMs.milliseconds)
            await("The first WMV bridge replacement did not start preparing.") { player.isLoading }
            Thread.sleep(FROZEN_SEEK_OBSERVATION_MS)
            if (player.isLoading) {
                assertTrue(
                    abs(player.currentTime.inWholeMilliseconds - forwardTargetMs) <=
                        FROZEN_SEEK_POSITION_TOLERANCE_MS,
                    "The active WMV kept advancing while its seek replacement was loading.",
                )
            }
            Thread.sleep(SUPERSEDING_SEEK_DELAY_MS)
            player.seekTo(supersedingTargetMs.milliseconds)
            await("The latest WMV seek did not supersede the bridge candidate already being prepared.") {
                !player.isLoading &&
                    abs(player.currentTime.inWholeMilliseconds - supersedingTargetMs) <= PLAYING_SEEK_TOLERANCE_MS
            }
            assertTrue(player.isPlaying, "A playing WMV must remain playing after bridge seek.")

            val stabilityWindowMs =
                minOf(
                    PLAYING_STABILITY_WINDOW_MS,
                    durationMs - supersedingTargetMs - END_GUARD_MS,
                ).coerceAtLeast(0L)
            if (stabilityWindowMs >= MINIMUM_STABILITY_WINDOW_MS) {
                Thread.sleep(stabilityWindowMs)
                val advanceMs = player.currentTime.inWholeMilliseconds - supersedingTargetMs
                val minimumAdvanceMs = stabilityWindowMs - PLAYING_STABILITY_TOLERANCE_MS
                val maximumAdvanceMs = stabilityWindowMs + PLAYING_STABILITY_TOLERANCE_MS
                assertTrue(
                    advanceMs in minimumAdvanceMs..maximumAdvanceMs,
                    "Playing WMV seek left its retained HLS window: " +
                        "targetMs=$supersedingTargetMs, currentMs=${player.currentTime.inWholeMilliseconds}, " +
                        "elapsedMs=$stabilityWindowMs.",
                )
            }

            player.pause()
            await("WMV did not pause before the backward seek.") { !player.isPlaying }
            player.seekTo(PAUSED_BACKWARD_TARGET_MS.milliseconds)
            await(
                message = "Paused backward WMV seek did not publish the restarted timeline position.",
                diagnostics = {
                    "loading=${player.isLoading}, seeking=${player.isSeeking}, " +
                        "currentMs=${player.currentTime.inWholeMilliseconds}, error=${player.error}"
                },
            ) {
                player.error != null ||
                    (
                        !player.isLoading &&
                            abs(player.currentTime.inWholeMilliseconds - PAUSED_BACKWARD_TARGET_MS) <=
                            PAUSED_SEEK_TOLERANCE_MS
                    )
            }
            assertNull(player.error)
            assertFalse(player.isPlaying, "A paused WMV must remain paused after bridge seek.")
        } finally {
            player.dispose()
        }
    }

    @Test
    fun failedNativeCandidateKeepsTheActiveWmvBridgePlaying() {
        if (!isMacArm64() || GraphicsEnvironment.isHeadless()) return
        val media = configuredWmv() ?: return
        val extension = InvalidSeekPlaylistBridgeExtension(configuredTestExtension())
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
            await("WMV did not start before the rollback test.") {
                player.error != null ||
                    (player.hasMedia && player.isPlaying && !player.isLoading && player.currentTime >= 500.milliseconds)
            }
            assertNull(player.error)
            val positionBeforeFailure = player.currentTime.inWholeMilliseconds
            val durationMs = player.duration.inWholeMilliseconds
            val failedTargetMs =
                (durationMs * FORWARD_SEEK_NUMERATOR / FORWARD_SEEK_DENOMINATOR)
                    .coerceIn(MINIMUM_FORWARD_TARGET_MS, durationMs - END_GUARD_MS)

            player.seekTo(failedTargetMs.milliseconds)
            await("The intentionally invalid replacement playlist did not fail.") {
                player.error != null && !player.isLoading
            }

            assertTrue(player.hasMedia, "A failed candidate must not clear the active media.")
            assertTrue(player.isPlaying, "A failed candidate must not pause the active AVPlayer.")
            val positionAfterFailure = player.currentTime.inWholeMilliseconds
            assertTrue(
                positionAfterFailure < failedTargetMs - PLAYING_SEEK_TOLERANCE_MS,
                "Rollback must restore the active timeline instead of publishing the failed target.",
            )
            await("The old WMV bridge stopped advancing after candidate rollback.") {
                player.currentTime.inWholeMilliseconds >=
                    maxOf(positionBeforeFailure, positionAfterFailure) + ROLLBACK_ADVANCE_MS
            }
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

    private fun configuredTestExtension(): KMediaBridgeDesktopExtension {
        val extension =
            System
                .getProperty(KMEDIA_BRIDGE_RUNTIME_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let(KMediaBridgeDesktopRuntimeSelection::fromExternalDirectory)
                ?.let(::KMediaBridgeDesktopExtension)
                ?: KMediaBridgeDesktopExtension()
        assertTrue(
            extension.availability.canContribute,
            extension.availability.detail ?: "The configured KMediaBridge runtime is unavailable.",
        )
        return extension
    }

    /** Returns a valid initial bridge but an AVFoundation-unopenable candidate for timestamped restarts. */
    private class InvalidSeekPlaylistBridgeExtension(
        private val delegate: DesktopPlaybackBridgeExtension,
    ) : DesktopPlaybackBridgeExtension {
        override val id
            get() = delegate.id

        override val availability
            get() = delegate.availability

        override val desktopCapabilities
            get() = delegate.desktopCapabilities

        override suspend fun open(request: DesktopPlaybackBridgeRequest): DesktopPlaybackBridgeSession {
            val session = delegate.open(request)
            if (request.startPositionMs == 0L) return session
            return object : DesktopPlaybackBridgeSession {
                override val source =
                    session.source.copy(
                        playlistUrl =
                            session.source.playlistUrl.substringBeforeLast('/') +
                                "/compose-media-player-missing-replacement.m3u8",
                    )

                override fun close() = session.close()
            }
        }
    }

    private fun await(
        message: String,
        diagnostics: () -> String = { "" },
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(condition(), listOf(message, diagnostics()).filter(String::isNotBlank).joinToString(" "))
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
        const val FROZEN_SEEK_OBSERVATION_MS = 400L
        const val FROZEN_SEEK_POSITION_TOLERANCE_MS = 100L
        const val SUPERSEDING_SEEK_DELAY_MS = 100L
        const val ROLLBACK_ADVANCE_MS = 250L
        const val PLAYING_STABILITY_WINDOW_MS = 25_000L
        const val MINIMUM_STABILITY_WINDOW_MS = 5_000L
        const val PLAYING_STABILITY_TOLERANCE_MS = 5_000L
        const val PLAYING_SEEK_TOLERANCE_MS = 2_500L
        const val PAUSED_SEEK_TOLERANCE_MS = 1_000L
        const val POLL_INTERVAL_MS = 50L
        const val TEST_TIMEOUT_NANOS = 30_000_000_000L
        val ASF_EXTENSIONS = setOf("wmv", "asf")
    }
}
