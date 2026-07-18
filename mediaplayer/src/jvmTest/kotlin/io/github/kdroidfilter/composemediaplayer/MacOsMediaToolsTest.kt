package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacOsMediaToolsTest {
    @Test
    fun queryReturnsConsistentToolStatus() {
        val tools = JvmMediaTools.query()

        listOf(
            tools.vlc,
            tools.libVlc,
            tools.kMediaBridge,
            tools.kMediaBridgeProbe,
            tools.kMediaBridgeSubtitleBurnIn,
            tools.kMediaBridgeHdrToSdrToneMapping,
            tools.libass,
        ).forEach { status ->
            if (status.available) {
                assertTrue(status.path?.isNotBlank() == true || status.detail?.isNotBlank() == true)
            } else if (status.path == null) {
                assertTrue(status.detail?.isNotBlank() == true)
            }
        }

        if (tools.kMediaBridgeSubtitleBurnIn.available || tools.kMediaBridgeHdrToSdrToneMapping.available) {
            assertTrue(tools.kMediaBridge.available)
        }
        if (tools.libVlc.available) {
            assertTrue(tools.libVlc.detail?.isNotBlank() == true)
        } else {
            assertFalse(tools.libVlc.available)
        }
    }

    @Test
    fun queryReportsOnlyAnExplicitAvailableBridge() {
        val tools =
            JvmMediaTools.query(
                listOf(
                    FakeDesktopBridge(
                        availability =
                            VideoPipelineExtensionAvailability.unavailable(
                                "Test runtime missing.",
                            ),
                    ),
                    FakeDesktopBridge(
                        id = "available-test-bridge",
                        availability = VideoPipelineExtensionAvailability.Available,
                        desktopCapabilities =
                            DesktopPlaybackBridgeCapabilities(
                                canProbe = true,
                                canCopyVideo = true,
                                canToneMapToSdr = true,
                                canBurnSubtitles = true,
                            ),
                    ),
                ),
            )

        assertTrue(tools.kMediaBridge.available)
        assertTrue(tools.kMediaBridgeProbe.available)
        assertTrue(tools.kMediaBridgeSubtitleBurnIn.available)
        assertTrue(tools.kMediaBridgeHdrToSdrToneMapping.available)
    }
}

private class FakeDesktopBridge(
    override val id: String = "unavailable-test-bridge",
    override val availability: VideoPipelineExtensionAvailability,
    override val desktopCapabilities: DesktopPlaybackBridgeCapabilities =
        DesktopPlaybackBridgeCapabilities(),
) : DesktopPlaybackBridgeExtension {
    override suspend fun open(request: DesktopPlaybackBridgeRequest): DesktopPlaybackBridgeSession =
        error("The availability query must not open a bridge session.")
}
