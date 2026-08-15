package io.github.kdroidfilter.composemediaplayer.kmediabridge

import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeRequest
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeSegmentContainer
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeSession
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KMediaBridgeDesktopExtensionIntegrationTest {
    @Test
    fun desktopMatrixRejectsIntelMacOs() {
        assertTrue(isKMediaBridgeDesktopPlatformSupported("Mac OS X", "arm64"))
        assertFalse(isKMediaBridgeDesktopPlatformSupported("Mac OS X", "x86_64"))
        assertTrue(isKMediaBridgeDesktopPlatformSupported("Windows 11", "x86_64"))
        assertTrue(isKMediaBridgeDesktopPlatformSupported("Linux", "aarch64"))
    }

    @Test
    fun remuxesMkvThroughKMediaBridgeWithoutAnExecutable() =
        runBlocking {
            val input = Files.createTempFile("kmediaplayer-bridge-test-", ".mkv")
            val extension = configuredTestExtension()
            var fallback: DesktopPlaybackBridgeSession? = null
            try {
                val encoded =
                    KMediaBridgeDesktopExtensionIntegrationTest::class.java.classLoader
                        .getResourceAsStream("kmediabridge-test.mkv.b64")!!
                        .bufferedReader()
                        .readText()
                Files.write(input, Base64.getMimeDecoder().decode(encoded))

                fallback =
                    extension.open(
                        DesktopPlaybackBridgeRequest(
                            uri = input.toUri().toString(),
                            allowHdrCmafPassthrough = true,
                        ),
                    )
                val source = fallback.source

                assertTrue(source.videoCopiedWithoutReencoding)
                assertFalse(source.toneMappedHdrToSdr)
                assertTrue(
                    URI
                        .create(source.playlistUrl)
                        .toURL()
                        .readText()
                        .contains("#EXT-X-MAP"),
                )
            } finally {
                fallback?.close()
                input.deleteIfExists()
            }
        }

    @Test
    fun burnsSelectedMkvSubtitleThroughTheSelectedRuntime() =
        runBlocking {
            val extension = configuredTestExtension()
            if (!extension.desktopCapabilities.canBurnSubtitles) return@runBlocking

            val input = Files.createTempFile("kmediaplayer-bridge-subtitle-test-", ".mkv")
            var fallback: DesktopPlaybackBridgeSession? = null
            try {
                val encoded =
                    KMediaBridgeDesktopExtensionIntegrationTest::class.java.classLoader
                        .getResourceAsStream("kmediabridge-subtitle-test.mkv.b64")!!
                        .bufferedReader()
                        .readText()
                Files.write(input, Base64.getMimeDecoder().decode(encoded))

                fallback =
                    extension.open(
                        DesktopPlaybackBridgeRequest(
                            uri = input.toUri().toString(),
                            selectedSubtitleStreamIndex = 1,
                        ),
                    )
                val source = fallback.source

                assertFalse(source.videoCopiedWithoutReencoding)
                assertFalse(source.toneMappedHdrToSdr)
                assertEquals(1, source.selectedSubtitleStreamIndex)
                assertEquals(VideoDynamicRange.SDR, source.outputColorInfo.dynamicRange)
                assertEquals(8, source.outputColorInfo.bitDepth)
                assertEquals(VideoColorPrimaries.BT709, source.outputColorInfo.primaries)
                assertEquals(VideoColorTransfer.SDR, source.outputColorInfo.transfer)
                assertEquals(VideoColorMatrix.BT709, source.outputColorInfo.matrix)
                assertEquals(VideoColorRange.LIMITED, source.outputColorInfo.range)
                assertTrue(
                    URI
                        .create(source.playlistUrl)
                        .toURL()
                        .readText()
                        .contains("#EXT-X-MAP"),
                )
            } finally {
                fallback?.close()
                input.deleteIfExists()
            }
        }

    @Test
    fun forceSdrToneMapsConfiguredHdrMediaThroughTheSelectedRuntime() =
        runBlocking {
            val configuredPath =
                System.getProperty(HDR_TEST_MEDIA_PROPERTY)?.takeIf(String::isNotBlank) ?: return@runBlocking
            val input =
                java.nio.file.Path
                    .of(configuredPath)
            require(Files.isRegularFile(input)) { "The configured KMediaPlayer HDR fixture does not exist." }
            val extension = configuredTestExtension()
            var fallback: DesktopPlaybackBridgeSession? = null
            try {
                fallback =
                    extension.open(
                        DesktopPlaybackBridgeRequest(
                            uri = input.toUri().toString(),
                            forceSdrOutput = true,
                        ),
                    )
                val source = fallback.source

                assertFalse(source.videoCopiedWithoutReencoding)
                assertTrue(source.toneMappedHdrToSdr)
                assertFalse(source.hdrCmafPassthrough)
                assertTrue(source.inputColorInfo.isHdr)
                assertEquals(VideoDynamicRange.SDR, source.outputColorInfo.dynamicRange)
                assertEquals(8, source.outputColorInfo.bitDepth)
                assertEquals(VideoColorPrimaries.BT709, source.outputColorInfo.primaries)
                assertEquals(VideoColorTransfer.SDR, source.outputColorInfo.transfer)
                assertEquals(VideoColorMatrix.BT709, source.outputColorInfo.matrix)
                assertEquals(VideoColorRange.LIMITED, source.outputColorInfo.range)
            } finally {
                fallback?.close()
            }
        }

    @Test
    fun transcodesLegacyMediaForAvFoundationThroughTheSelectedRuntime() =
        runBlocking {
            val configuredPath =
                System.getProperty(LEGACY_TEST_MEDIA_PROPERTY)?.takeIf(String::isNotBlank) ?: return@runBlocking
            val configured = Path.of(configuredPath)
            val inputs =
                if (Files.isDirectory(configured)) {
                    Files.list(configured).use { paths ->
                        paths
                            .filter(Files::isRegularFile)
                            .filter { path ->
                                path.fileName
                                    .toString()
                                    .substringAfterLast('.', "")
                                    .lowercase() in
                                    setOf("avi", "wmv", "asf")
                            }.sorted()
                            .toList()
                    }
                } else {
                    listOf(configured)
                }
            require(inputs.isNotEmpty() && inputs.all(Files::isRegularFile)) {
                "The configured legacy integration-test media does not exist."
            }
            val extension = configuredTestExtension()
            assertTrue(extension.desktopCapabilities.canTranscodeVideo)
            assertTrue(extension.desktopCapabilities.canTranscodeAudio)
            inputs.forEach { input ->
                var fallback: DesktopPlaybackBridgeSession? = null
                try {
                    fallback =
                        extension.open(
                            DesktopPlaybackBridgeRequest(
                                uri = input.toUri().toString(),
                                forceAvFoundationCompatibility = true,
                            ),
                        )
                    val source = fallback.source

                    assertTrue(source.avFoundationCompatibleTranscode)
                    assertFalse(source.videoCopiedWithoutReencoding)
                    assertFalse(source.hdrCmafPassthrough)
                    assertEquals(VideoDynamicRange.SDR, source.outputColorInfo.dynamicRange)
                    assertTrue(
                        URI
                            .create(source.playlistUrl)
                            .toURL()
                            .readText()
                            .contains("#EXT-X-MAP"),
                    )
                } finally {
                    fallback?.close()
                }
            }
        }

    @Test
    fun transcodesLocalMediaToMpegTsHlsThroughTheSelectedRuntime() =
        runBlocking {
            val extension = configuredTestExtension()
            if (!extension.desktopCapabilities.canTranscodeVideo) return@runBlocking
            val input = Files.createTempFile("kmediaplayer-bridge-cast-test-", ".mkv")
            var fallback: DesktopPlaybackBridgeSession? = null
            try {
                val encoded =
                    KMediaBridgeDesktopExtensionIntegrationTest::class.java.classLoader
                        .getResourceAsStream("kmediabridge-test.mkv.b64")!!
                        .bufferedReader()
                        .readText()
                Files.write(input, Base64.getMimeDecoder().decode(encoded))

                fallback =
                    extension.open(
                        DesktopPlaybackBridgeRequest(
                            uri = input.toUri().toString(),
                            forceAvFoundationCompatibility = true,
                            segmentContainer = DesktopPlaybackBridgeSegmentContainer.MPEG2_TS,
                        ),
                    )
                val source = fallback.source
                val masterPlaylistUri = URI.create(source.playlistUrl)
                val masterPlaylist = masterPlaylistUri.toURL().readText()
                val mediaPlaylistReference =
                    requireNotNull(
                        masterPlaylist.lineSequence().firstOrNull { line ->
                            line.isNotBlank() && !line.startsWith('#')
                        },
                    )
                val mediaPlaylistUri = masterPlaylistUri.resolve(mediaPlaylistReference)
                val (playlist, segment) =
                    withTimeout(10_000) {
                        while (true) {
                            val candidate = mediaPlaylistUri.toURL().readText()
                            candidate
                                .lineSequence()
                                .firstOrNull { line -> line.substringBefore('?').endsWith(".ts") }
                                ?.let { segment -> return@withTimeout candidate to segment }
                            delay(25)
                        }
                        error("Unreachable")
                    }

                assertEquals(DesktopPlaybackBridgeSegmentContainer.MPEG2_TS, source.segmentContainer)
                assertTrue(source.avFoundationCompatibleTranscode)
                assertFalse(source.videoCopiedWithoutReencoding)
                assertFalse("#EXT-X-MAP" in playlist)
                val syncByte =
                    mediaPlaylistUri
                        .resolve(segment)
                        .toURL()
                        .readBytes()
                        .first()
                        .toInt() and 0xff
                assertEquals(0x47, syncByte)
            } finally {
                fallback?.close()
                input.deleteIfExists()
            }
        }

    private companion object {
        const val HDR_TEST_MEDIA_PROPERTY: String = "composemediaplayer.test.hdrMedia"
        const val HDR_TEST_RUNTIME_PROPERTY: String = "composemediaplayer.test.kMediaBridgeRuntimeDirectory"
        const val LEGACY_TEST_MEDIA_PROPERTY: String = "composemediaplayer.test.legacyMedia"

        fun configuredTestExtension(): KMediaBridgeDesktopExtension =
            System
                .getProperty(HDR_TEST_RUNTIME_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.let(KMediaBridgeDesktopRuntimeSelection::fromExternalDirectory)
                ?.let(::KMediaBridgeDesktopExtension)
                ?: KMediaBridgeDesktopExtension()
    }
}
