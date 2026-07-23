@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.ui.graphics.toPixelMap
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeRequest
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeSession
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.kmediabridge.KMediaBridgeDesktopExtension
import io.github.shusek.kmediaffmpeg.runtime.KMediaAssRuntime
import io.github.shusek.kmediaffmpeg.runtime.KMediaFfmpegRuntime
import io.github.shusek.kmediaffmpeg.runtime.RuntimeSource
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SharedRuntimeBridgeFirstIntegrationTest {
    @Test
    fun bridgeThenMpvRenderAssThroughOneRuntime() {
        exerciseSharedRuntime(FirstClient.BRIDGE)
    }
}

class SharedRuntimeMpvFirstIntegrationTest {
    @Test
    fun mpvThenBridgeRenderAssThroughOneRuntime() {
        exerciseSharedRuntime(FirstClient.MPV)
    }
}

private enum class FirstClient {
    BRIDGE,
    MPV,
}

private fun exerciseSharedRuntime(firstClient: FirstClient) =
    runBlocking {
        val video = Files.createTempFile("kmediaplayer-shared-runtime-", ".mkv")
        val subtitle = Files.createTempFile("kmediaplayer-shared-runtime-", ".ass")
        var bridge: DesktopPlaybackBridgeSession? = null
        var player: MpvVideoPlayerState? = null
        try {
            writeVideoFixture(video)
            Files.writeString(subtitle, authoredAssFixture())

            if (firstClient == FirstClient.BRIDGE) {
                bridge = openBridge(video)
            }

            player = assertIs<MpvVideoPlayerState>(createMpvVideoPlayerState())
            val baseline = openPausedAndRender(player, video)

            if (firstClient == FirstClient.MPV) {
                bridge = openBridge(video)
            }

            val track =
                SubtitleTrack(
                    id = "shared-runtime-ass",
                    label = "Shared runtime ASS",
                    language = "en",
                    src = subtitle.toUri().toString(),
                    format = SubtitleFormat.ASS,
                )
            player.addSubtitleTrack(track)
            assertEquals(TrackSelectionResult.Selected(track.id), player.selectSubtitleTrack(track))
            assertAssPixelsRendered(player, baseline)

            val runtime = KMediaFfmpegRuntime.current().orElseThrow()
            assertEquals(EXPECTED_RUNTIME_ID, runtime.runtimeId())
            assertEquals(
                runtime,
                KMediaFfmpegRuntime.initialize(RuntimeSource.bundled()),
                "FFmpeg runtime initialization must be process-idempotent.",
            )
            val assRuntime = KMediaAssRuntime.current().orElseThrow()
            assertEquals(EXPECTED_ASS_RUNTIME_ID, assRuntime.runtimeId())
            assertTrue(
                URI
                    .create(requireNotNull(bridge).source.playlistUrl)
                    .toURL()
                    .readText()
                    .contains("#EXT-X-MAP"),
            )
        } finally {
            player?.dispose()
            bridge?.close()
            subtitle.deleteIfExists()
            video.deleteIfExists()
        }
    }

private suspend fun openBridge(video: Path): DesktopPlaybackBridgeSession =
    KMediaBridgeDesktopExtension().open(
        DesktopPlaybackBridgeRequest(
            uri = video.toUri().toString(),
            allowHdrCmafPassthrough = true,
        ),
    )

private fun openPausedAndRender(
    player: MpvVideoPlayerState,
    video: Path,
): IntArray {
    player.openUri(video.toUri().toString(), InitialPlayerState.PAUSE)
    await("MPV did not finish loading the shared-runtime fixture.") {
        player.hasMedia && !player.isLoading
    }
    return renderPixels(player)
}

private fun assertAssPixelsRendered(
    player: MpvVideoPlayerState,
    baseline: IntArray,
) {
    val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
    var changedPixels = 0
    while (System.nanoTime() < deadline) {
        val rendered = renderPixels(player)
        changedPixels = rendered.indices.count { rendered[it] != baseline[it] }
        if (changedPixels >= MINIMUM_ASS_PIXELS) return
        Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    assertTrue(
        changedPixels >= MINIMUM_ASS_PIXELS,
        "MPV did not render the authored ASS layer through the shared libass runtime.",
    )
}

private fun renderPixels(player: MpvVideoPlayerState): IntArray {
    val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
    while (System.nanoTime() < deadline) {
        player.renderFrame(FRAME_WIDTH, FRAME_HEIGHT)
        player.currentFrame.value?.let { frame ->
            return frame.toPixelMap().buffer.copyOf()
        }
        Thread.sleep(POLL_INTERVAL_MILLIS)
    }
    error("MPV did not produce a software-rendered frame.")
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

private fun writeVideoFixture(output: Path) {
    val encoded =
        SharedRuntimeBridgeFirstIntegrationTest::class.java.classLoader
            .getResourceAsStream("kmediabridge-subtitle-test.mkv.b64")
            .orEmpty()
            .bufferedReader()
            .readText()
    Files.write(output, Base64.getMimeDecoder().decode(encoded))
}

private fun java.io.InputStream?.orEmpty(): java.io.InputStream =
    requireNotNull(this) { "The shared-runtime video fixture is missing." }

private fun authoredAssFixture(): String =
    """
    [Script Info]
    ScriptType: v4.00+
    PlayResX: 160
    PlayResY: 90

    [V4+ Styles]
    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
    Style: Default,Arial,20,&H0000FF00,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,7,0,0,0,1

    [Events]
    Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    Dialogue: 0,0:00:00.00,0:00:10.00,Default,,0,0,0,,{\an7\pos(8,8)\p1}m 0 0 l 100 0 100 48 0 48
    """.trimIndent()

private const val EXPECTED_RUNTIME_ID = "kmediaffmpeg-8.1.2-ass-0.17.5-78fbb23ab073fc90"
private const val EXPECTED_ASS_RUNTIME_ID = "kmediaass-0.17.5-36443523f0148567"
private const val FRAME_WIDTH = 160
private const val FRAME_HEIGHT = 90
private const val MINIMUM_ASS_PIXELS = 500
private const val POLL_INTERVAL_MILLIS = 25L
private const val TEST_TIMEOUT_NANOS = 15_000_000_000L
