@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.ui.graphics.toPixelMap
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeRequest
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeSession
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.kmediabridge.KMediaBridgeDesktopExtension
import io.github.kdroidfilter.composemediaplayer.kmediabridge.KMediaBridgeDesktopRuntimeSelection
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64
import java.util.EnumSet
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IsolatedRuntimeBridgeFirstIntegrationTest {
    @Test
    fun bridgeThenMpvRenderAssWithoutNativeCollisions() {
        exerciseRuntimeCoexistence(FirstClient.BRIDGE)
    }
}

class IsolatedRuntimeMpvFirstIntegrationTest {
    @Test
    fun mpvThenBridgeRenderAssWithoutNativeCollisions() {
        exerciseRuntimeCoexistence(FirstClient.MPV)
    }
}

private enum class FirstClient {
    BRIDGE,
    MPV,
}

private fun exerciseRuntimeCoexistence(firstClient: FirstClient) =
    runBlocking {
        val video = Files.createTempFile("kmediaplayer-shared-runtime-", ".mkv")
        val subtitle = Files.createTempFile("kmediaplayer-shared-runtime-", ".ass")
        val fontsDirectory = Files.createTempDirectory("kmediaplayer-shared-runtime-fonts-")
        var bridge: DesktopPlaybackBridgeSession? = null
        var player: MpvVideoPlayerState? = null
        var stagedFont: Path? = null
        try {
            makeApplicationPrivate(fontsDirectory)
            val font = loadHostTestFont()
            val copiedFont = fontsDirectory.resolve(font.source.fileName)
            Files.copy(font.source, copiedFont, StandardCopyOption.REPLACE_EXISTING)
            stagedFont = copiedFont
            writeVideoFixture(video)
            Files.writeString(subtitle, authoredAssFixture(font.family))

            if (firstClient == FirstClient.BRIDGE) {
                bridge = openBridge(video)
            }

            player =
                assertIs<MpvVideoPlayerState>(
                    createMpvVideoPlayerState(
                        MpvPlaybackOptions(
                            subtitleFontsDirectory = fontsDirectory.toString(),
                        ),
                    ),
                )
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
            stagedFont?.deleteIfExists()
            fontsDirectory.deleteIfExists()
        }
    }

private suspend fun openBridge(video: Path): DesktopPlaybackBridgeSession =
    configuredKMediaBridgeExtension().open(
        DesktopPlaybackBridgeRequest(
            uri = video.toUri().toString(),
            allowHdrCmafPassthrough = true,
        ),
    )

private fun configuredKMediaBridgeExtension(): KMediaBridgeDesktopExtension =
    System
        .getProperty(KMEDIA_BRIDGE_RUNTIME_PROPERTY)
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.let(KMediaBridgeDesktopRuntimeSelection::fromExternalDirectory)
        ?.let(::KMediaBridgeDesktopExtension)
        ?: KMediaBridgeDesktopExtension()

private fun openPausedAndRender(
    player: MpvVideoPlayerState,
    video: Path,
): IntArray {
    player.openUri(video.toUri().toString(), InitialPlayerState.PAUSE)
    await("MPV did not finish loading the runtime-coexistence fixture.") {
        player.hasMedia && !player.isLoading
    }
    if (isMacArm64Host()) {
        await("Bundled MPV did not select the pipelined VideoToolbox decoder for H.264.") {
            player.renderingInfo.videoDecoder
                ?.contains("videotoolbox-async", ignoreCase = true) == true
        }
    }
    return renderPixels(player)
}

private fun isMacArm64Host(): Boolean =
    System.getProperty("os.name").contains("mac", ignoreCase = true) &&
        System.getProperty("os.arch").lowercase() in setOf("aarch64", "arm64")

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
        "MPV did not render the authored ASS layer through its isolated libass runtime.",
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
        IsolatedRuntimeBridgeFirstIntegrationTest::class.java.classLoader
            .getResourceAsStream("kmediabridge-subtitle-test.mkv.b64")
            .orEmpty()
            .bufferedReader()
            .readText()
    Files.write(output, Base64.getMimeDecoder().decode(encoded))
}

private fun java.io.InputStream?.orEmpty(): java.io.InputStream =
    requireNotNull(this) { "The shared-runtime video fixture is missing." }

private fun authoredAssFixture(fontFamily: String): String =
    """
    [Script Info]
    ScriptType: v4.00+
    PlayResX: 160
    PlayResY: 90

    [V4+ Styles]
    Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
    Style: Default,$fontFamily,20,&H0000FF00,&H000000FF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,1,0,7,0,0,0,1

    [Events]
    Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
    Dialogue: 0,0:00:00.00,0:00:10.00,Default,,0,0,0,,{\an7\pos(8,8)}KMediaPlayer
    """.trimIndent()

private data class HostTestFont(
    val family: String,
    val source: Path,
)

private val HOST_FONT_CANDIDATES =
    listOf(
        HostTestFont("DejaVu Sans", Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")),
        HostTestFont("Liberation Sans", Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf")),
        HostTestFont("Arial", Path.of("/System/Library/Fonts/Supplemental/Arial.ttf")),
        HostTestFont("SF Pro", Path.of("/System/Library/Fonts/SFNS.ttf")),
        HostTestFont("Arial", Path.of("C:/Windows/Fonts/arial.ttf")),
        HostTestFont("Segoe UI", Path.of("C:/Windows/Fonts/segoeui.ttf")),
    )

private fun loadHostTestFont(): HostTestFont =
    HOST_FONT_CANDIDATES
        .firstOrNull { font -> Files.isRegularFile(font.source) }
        ?: error("No supported host font was available for the MPV ASS integration test.")

private fun makeApplicationPrivate(directory: Path) {
    val posixView =
        Files.getFileAttributeView(
            directory,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    if (posixView != null) {
        posixView.setPermissions(
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        return
    }

    val aclView =
        requireNotNull(
            Files.getFileAttributeView(
                directory,
                AclFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ),
        ) { "The host filesystem cannot enforce an application-private font directory." }
    val processUser =
        ProcessHandle
            .current()
            .info()
            .user()
            .orElseThrow { IllegalStateException("The current process owner is unavailable.") }
    val processOwner =
        directory.fileSystem.userPrincipalLookupService.lookupPrincipalByName(processUser)
    aclView.owner = processOwner
    val ownerOnly =
        AclEntry
            .newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(processOwner)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            .build()
    aclView.setAcl(listOf(ownerOnly))
}

private const val FRAME_WIDTH = 160
private const val FRAME_HEIGHT = 90
private const val MINIMUM_ASS_PIXELS = 500
private const val POLL_INTERVAL_MILLIS = 25L
private const val TEST_TIMEOUT_NANOS = 15_000_000_000L
private const val KMEDIA_BRIDGE_RUNTIME_PROPERTY =
    "composemediaplayer.test.kMediaBridgeRuntimeDirectory"
