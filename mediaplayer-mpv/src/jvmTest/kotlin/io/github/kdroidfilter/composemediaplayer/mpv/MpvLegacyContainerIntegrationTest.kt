@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.ui.graphics.toPixelMap
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class MpvLegacyContainerIntegrationTest {
    @Test
    fun rendersConfiguredAviAndWmvFixtures() {
        val configured =
            System
                .getProperty(TEST_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: return
        val inputs = configured.legacyMediaFiles()
        assertTrue(inputs.any { it.extension() == "avi" }, "The configured fixtures contain no AVI file.")
        assertTrue(inputs.any { it.extension() in ASF_EXTENSIONS }, "The configured fixtures contain no WMV/ASF file.")

        val player = assertIs<MpvVideoPlayerState>(createMpvVideoPlayerState(configuredPlaybackOptions()))
        try {
            inputs.forEach { input ->
                renderFixture(player, input)
                player.stop()
            }
        } finally {
            player.dispose()
        }
    }

    @Test
    fun decodesConfiguredWmaProThroughThePlatformAudioOutput() {
        val input =
            System
                .getProperty(WMAPRO_MEDIA_PROPERTY)
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf(Files::isRegularFile)
                ?: return
        val player = assertIs<MpvVideoPlayerState>(createMpvVideoPlayerState(configuredPlaybackOptions()))
        try {
            player.openUri(input.toUri().toString(), InitialPlayerState.PLAY)
            await("MPV did not load the configured WMA Pro fixture.") {
                player.hasMedia && player.isPlaying && !player.isLoading
            }
            await("MPV did not select the configured WMA Pro audio track.") {
                player.availableAudioTracks.isNotEmpty() && player.currentAudioTrack != null
            }
            await("MPV did not decode WMA Pro through a platform audio output.") {
                player.renderingInfo.audioRenderer
                    ?.lowercase()
                    ?.startsWith("wmapro via ") == true
            }
            val audioRenderer =
                player.renderingInfo.audioRenderer
                    .orEmpty()
                    .lowercase()
            assertTrue(
                audioRenderer.usesRealPlatformAudioOutput(),
                "MPV decoded WMA Pro without a real platform audio output: $audioRenderer",
            )
            await("MPV did not advance while decoding the WMA Pro fixture.") {
                player.currentTime >= MINIMUM_AUDIO_PROGRESS
            }
            assertEquals(null, player.error)
        } finally {
            player.dispose()
        }
    }

    private fun renderFixture(
        player: MpvVideoPlayerState,
        input: Path,
    ) {
        player.openUri(input.toUri().toString(), InitialPlayerState.PLAY)
        await("MPV did not load ${input.fileName}.") {
            player.hasMedia && player.isPlaying && !player.isLoading
        }
        assertEquals(null, player.error, "MPV reported a playback error for ${input.fileName}.")

        await("MPV did not render ${input.fileName}.") {
            player.renderFrame(RENDER_WIDTH, RENDER_HEIGHT)
            player.currentFrame.value
                ?.toPixelMap()
                ?.buffer
                ?.hasVisibleVariation() == true
        }
        assertNotNull(player.currentFrame.value)
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

    private fun configuredPlaybackOptions(): MpvPlaybackOptions =
        System
            .getProperty(MPV_LIBRARY_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?.let(MpvRuntimeSource::ExplicitPath)
            ?.let { source -> MpvPlaybackOptions(runtimeSource = source) }
            ?: MpvPlaybackOptions()

    private fun IntArray.hasVisibleVariation(): Boolean = isNotEmpty() && any { pixel -> pixel != first() }

    private fun String.usesRealPlatformAudioOutput(): Boolean {
        val output = substringAfter(" via ", missingDelimiterValue = "").trim()
        if (output.isEmpty() || output == "null") return false
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("mac") || os.contains("darwin")) {
            output.startsWith("coreaudio") || output == "avfoundation"
        } else {
            true
        }
    }

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

    private companion object {
        const val TEST_MEDIA_PROPERTY: String = "composemediaplayer.legacyTestMedia"
        const val WMAPRO_MEDIA_PROPERTY: String = "composemediaplayer.wmaProTestMedia"
        const val MPV_LIBRARY_PROPERTY: String = "composemediaplayer.mpvLibraryPath"
        const val RENDER_WIDTH: Int = 320
        const val RENDER_HEIGHT: Int = 180
        const val POLL_INTERVAL_MILLIS: Long = 25L
        const val TEST_TIMEOUT_NANOS: Long = 15_000_000_000L
        val MINIMUM_AUDIO_PROGRESS = 250.milliseconds
        val ASF_EXTENSIONS: Set<String> = setOf("wmv", "asf")
        val LEGACY_EXTENSIONS: Set<String> = ASF_EXTENSIONS + "avi"
    }
}
