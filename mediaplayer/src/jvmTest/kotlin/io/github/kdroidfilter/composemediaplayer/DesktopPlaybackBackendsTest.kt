package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendProbeResult
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendRoutingTier
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackRequest
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopPlaybackBackendsTest {
    private val options = VideoPlaybackOptions(extensions = listOf(RoutingTestDesktopBridge))

    @Test
    fun splitBridgeStagesClassifyRemuxAndLegacyInputs() {
        val remux = kMediaBridgeRemuxDesktopPlaybackBackend(playbackOptions = options)
        val transcode = kMediaBridgeTranscodeDesktopPlaybackBackend(playbackOptions = options)

        assertIs<DesktopBackendProbeResult.Supported>(
            remux.probe(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.mkv"))),
        )
        assertIs<DesktopBackendProbeResult.Unsupported>(
            remux.probe(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.wmv"))),
        )
        assertIs<DesktopBackendProbeResult.Unsupported>(
            transcode.probe(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.mkv"))),
        )
        assertIs<DesktopBackendProbeResult.Supported>(
            transcode.probe(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.wmv"))),
        )
        assertEquals(DesktopBackendRoutingTier.KMEDIA_BRIDGE_REMUX, remux.routingTier)
        assertEquals(DesktopBackendRoutingTier.KMEDIA_BRIDGE_TRANSCODE, transcode.routingTier)
    }

    @Test
    fun explicitAdaptersDoNotEnterAutomaticRoute() {
        assertTrue(kMediaBridgeRemuxDesktopPlaybackBackend(playbackOptions = options).automaticSelection)
        assertTrue(kMediaBridgeTranscodeDesktopPlaybackBackend(playbackOptions = options).automaticSelection)
        assertFalse(kMediaBridgeDesktopPlaybackBackend(playbackOptions = options).automaticSelection)
        assertFalse(adaptedPlatformDesktopPlaybackBackend(playbackOptions = options).automaticSelection)
    }

    @Test
    fun platformDirectDoesNotClaimLegacyContainersOnMacOs() {
        val platform = platformDesktopPlaybackBackend(playbackOptions = options)
        val result = platform.probe(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.wmv")))

        if (CurrentPlatform.os == CurrentPlatform.OS.MAC) {
            assertIs<DesktopBackendProbeResult.Unsupported>(result)
        } else {
            assertIs<DesktopBackendProbeResult.Supported>(result)
        }
    }
}

private object RoutingTestDesktopBridge : DesktopPlaybackBridgeExtension {
    override val id: String = "test.desktop-bridge"
    override val desktopCapabilities: DesktopPlaybackBridgeCapabilities =
        DesktopPlaybackBridgeCapabilities(
            canProbe = true,
            canCopyVideo = true,
            canTranscodeVideo = true,
            canTranscodeAudio = true,
        )

    override suspend fun open(request: DesktopPlaybackBridgeRequest): DesktopPlaybackBridgeSession =
        error("Not used by backend policy tests")
}
