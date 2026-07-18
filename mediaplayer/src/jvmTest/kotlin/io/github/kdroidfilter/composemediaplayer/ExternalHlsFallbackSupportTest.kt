package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExternalHlsFallbackSupportTest {
    @Test
    fun autoPrefersConfiguredBridgeRouteForContainerPlayback() {
        withFakeVlc { vlcPath ->
            withBackendProperties(vlcPath = vlcPath, configuredBackend = "auto") {
                assertEquals(
                    ExternalHlsFallbackBackend.KMEDIA_BRIDGE,
                    ExternalHlsFallbackSupport.selectBackend(requiresSubtitleRendering = false),
                )
            }
        }
    }

    @Test
    fun autoUsesVlcForSubtitlesWhenConfiguredBridgeCannotBurnThem() {
        assertEquals(
            ExternalHlsFallbackBackend.VLC,
            selectAutomaticHlsFallbackBackend(
                requiresSubtitleRendering = true,
                canKMediaBridgeBurnSubtitles = false,
                isVlcAvailable = true,
            ),
        )
    }

    @Test
    fun autoUsesConfiguredBridgeForSubtitlesWhenItCanBurnThem() {
        assertEquals(
            ExternalHlsFallbackBackend.KMEDIA_BRIDGE,
            selectAutomaticHlsFallbackBackend(
                requiresSubtitleRendering = true,
                canKMediaBridgeBurnSubtitles = true,
                isVlcAvailable = true,
            ),
        )
    }

    @Test
    fun configuredMediaBridgeBackendOverridesVlc() {
        withFakeVlc { vlcPath ->
            withBackendProperties(vlcPath = vlcPath, configuredBackend = "ffmpeg") {
                assertEquals(
                    ExternalHlsFallbackBackend.KMEDIA_BRIDGE,
                    ExternalHlsFallbackSupport.selectBackend(requiresSubtitleRendering = true),
                )
            }
        }
    }

    @Test
    fun unmanagedVlcIsNeverSelectedForHdr() {
        withFakeVlc { vlcPath ->
            withBackendProperties(vlcPath = vlcPath, configuredBackend = "auto") {
                assertEquals(
                    ExternalHlsFallbackBackend.KMEDIA_BRIDGE,
                    ExternalHlsFallbackSupport.selectBackendForColor(
                        inputColorInfo =
                            VideoColorInfo(
                                dynamicRange = VideoDynamicRange.HLG,
                                bitDepth = 10,
                                transfer = VideoColorTransfer.HLG,
                            ),
                        requiresSubtitleRendering = false,
                    ),
                )
            }
        }
    }

    @Test
    fun autoUsesVlcForRemoteConfirmedSdrBecauseTheBridgeRouteIsLocalOnly() {
        withFakeVlc { vlcPath ->
            withBackendProperties(vlcPath = vlcPath, configuredBackend = "auto") {
                assertEquals(
                    ExternalHlsFallbackBackend.VLC,
                    ExternalHlsFallbackSupport.selectBackendForInput(
                        uri = "https://media.invalid/movie.mkv",
                        inputColorInfo = VideoColorInfo(dynamicRange = VideoDynamicRange.SDR, bitDepth = 8),
                        requiresSubtitleRendering = false,
                    ),
                )
            }
        }
    }

    @Test
    fun startRejectsInvalidTimeBeforeOpeningAnyRuntime() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking {
                ExternalHlsFallbackSupport.start(
                    uri = "file:///does-not-matter.mkv",
                    requestHeaders = emptyMap(),
                    selectedAudioStreamIndex = null,
                    selectedSubtitleStreamIndex = null,
                    startTimeSeconds = Double.NaN,
                )
            }
        }
    }

    private fun withFakeVlc(block: (Path) -> Unit) {
        val vlcPath = Files.createTempFile("vlc", null)
        Files.writeString(vlcPath, "#!/bin/sh\nexit 0\n")
        vlcPath.toFile().setExecutable(true)
        try {
            block(vlcPath)
        } finally {
            Files.deleteIfExists(vlcPath)
        }
    }

    private fun withBackendProperties(
        vlcPath: Path,
        configuredBackend: String,
        block: () -> Unit,
    ) {
        val values =
            mapOf(
                "composemediaplayer.vlc" to vlcPath.toString(),
                "composemediaplayer.hlsFallbackBackend" to configuredBackend,
                "composemediaplayer.macos.hlsFallbackBackend" to null,
                "composemediaplayer.windows.hlsFallbackBackend" to null,
                "composemediaplayer.linux.hlsFallbackBackend" to null,
            )
        val previous = values.keys.associateWith(System::getProperty)
        try {
            values.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
            block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }
}
