package sample.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.application.NucleusBackend
import dev.nucleusframework.application.nucleusApplication
import dev.nucleusframework.window.WindowDynamicRangeMode
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import sample.app.player.desktopPipelineExtensions
import sample.app.player.sampleLibVlcVideoPlayerBackend

fun main(args: Array<String>) {
    val initialVideoUrl =
        args.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("sample.app.videoUrl")?.takeIf { it.isNotBlank() }
    val demoSubtitleEnabled =
        System.getProperty("sample.app.demoSubtitle")
            ?.toBooleanStrictOrNull()
            ?: false
    val windowX = System.getProperty("sample.app.windowX")?.toIntOrNull()
    val windowY = System.getProperty("sample.app.windowY")?.toIntOrNull()
    val windowWidth = System.getProperty("sample.app.windowWidth")?.toIntOrNull() ?: 720
    val windowHeight = System.getProperty("sample.app.windowHeight")?.toIntOrNull() ?: 1000
    val initialProjection =
        System.getProperty("sample.app.projectionType")
            ?.trim()
            ?.let { requested ->
                VideoProjectionType.entries.firstOrNull { it.name.equals(requested, ignoreCase = true) }
            }?.let { projectionType -> VideoProjectionSettings(projectionType = projectionType) }
            ?: VideoProjectionSettings()
    val playbackOptions =
        VideoPlaybackOptions(
            dynamicRangePolicy =
                System.getProperty("sample.app.dynamicRangePolicy")
                    ?.trim()
                    ?.uppercase()
                    ?.let { runCatching { DynamicRangePolicy.valueOf(it) }.getOrNull() }
                    ?: DynamicRangePolicy.AUTO,
            dolbyVisionPolicy =
                System.getProperty("sample.app.dolbyVisionPolicy")
                    ?.trim()
                    ?.uppercase()
                    ?.let { runCatching { DolbyVisionPolicy.valueOf(it) }.getOrNull() }
                    ?: DolbyVisionPolicy.AUTO,
            desktopVideoBackend =
                System.getProperty("sample.app.desktopVideoBackend")
                    ?.trim()
                    ?.uppercase()
                    ?.let { runCatching { DesktopVideoBackend.valueOf(it) }.getOrNull() }
                    ?: DesktopVideoBackend.AUTO,
            desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
            extensions = desktopPipelineExtensions,
        )
    val colorSelfTestSeconds =
        System.getProperty("sample.app.colorSelfTestSeconds")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    val mpvPerformanceSelfTestSeconds =
        System.getProperty("sample.app.mpvPerformanceSelfTestSeconds")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    val mpvPerformanceSelfTestResultFile =
        System.getProperty("sample.app.mpvPerformanceSelfTestResultFile")
            ?.takeIf { it.isNotBlank() }
            ?: java.io.File(
                System.getProperty("java.io.tmpdir"),
                "kmp-mpv-performance-self-test-result.txt",
            ).absolutePath
    val colorSelfTestResultFile =
        System.getProperty("sample.app.colorSelfTestResultFile")
            ?.takeIf { it.isNotBlank() }
            ?: java.io.File(
                System.getProperty("java.io.tmpdir"),
                "kmp-desktop-color-self-test-result.txt",
            ).absolutePath
    val colorSelfTestExpectedSource =
        System.getProperty("sample.app.colorSelfTestExpectedSource")
            ?.trim()
            ?.uppercase()
            ?.let { io.github.kdroidfilter.composemediaplayer.VideoDynamicRange.valueOf(it) }
    val colorSelfTestExpectedOutput =
        System.getProperty("sample.app.colorSelfTestExpectedOutput")
            ?.trim()
            ?.uppercase()
            ?.let { io.github.kdroidfilter.composemediaplayer.VideoDynamicRange.valueOf(it) }
    val colorSelfTestRequireAudioSync =
        System.getProperty("sample.app.colorSelfTestRequireAudioSync")
            ?.toBooleanStrictOrNull()
            ?: false
    val windowLifecycleSelfTest =
        System.getProperty("sample.app.windowLifecycleSelfTest")
            ?.toBooleanStrictOrNull()
            ?: false
    val initialDesktopBackendName = System.getProperty("sample.app.playbackBackend")
    val colorSelfTestBackend =
        initialDesktopBackendName
            ?.trim()
            ?.uppercase()
            ?.takeIf { colorSelfTestSeconds != null && it in kMediaVlcSampleBackendNames }
            ?.let { sampleLibVlcVideoPlayerBackend(playbackOptions) }

    nucleusApplication(args = args, backend = NucleusBackend.Tao) {
        val applicationScope = this
        val windowState =
            rememberWindowState(
                position =
                    if (windowX != null && windowY != null) {
                        WindowPosition.Absolute(windowX.dp, windowY.dp)
                    } else {
                        WindowPosition.PlatformDefault
                    },
                width = windowWidth.dp,
                height = windowHeight.dp,
            )
        fun requestNativeFullscreen(fullscreen: Boolean) {
            windowState.placement =
                if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }
        applicationScope.DecoratedWindow(
            onCloseRequest = applicationScope::exitApplication,
            title = "Compose Media Player",
            state = windowState,
            nativePopupLayers = true,
            dynamicRangeMode = WindowDynamicRangeMode.EXTENDED_IF_AVAILABLE,
            onKeyEvent = { event ->
                if (event.key == Key.Escape &&
                    event.type == KeyEventType.KeyDown &&
                    windowState.placement == WindowPlacement.Fullscreen
                ) {
                    requestNativeFullscreen(false)
                    true
                } else {
                    false
                }
            },
        ) {
            if (mpvPerformanceSelfTestSeconds != null) {
                DesktopMpvPerformanceSelfTest(
                    inputUri =
                        checkNotNull(initialVideoUrl) {
                            "The MPV performance self-test requires sample.app.videoUrl."
                        },
                    durationSeconds = mpvPerformanceSelfTestSeconds,
                    resultFilePath = mpvPerformanceSelfTestResultFile,
                    window = nucleusWindow,
                    initialProjection = initialProjection,
                    onComplete = applicationScope::exitApplication,
                )
            } else if (colorSelfTestSeconds != null) {
                DesktopColorPipelineSelfTest(
                    inputUri =
                        checkNotNull(initialVideoUrl) {
                            "The color self-test requires sample.app.videoUrl."
                        },
                    expectedSource =
                        checkNotNull(colorSelfTestExpectedSource) {
                            "The color self-test requires sample.app.colorSelfTestExpectedSource."
                        },
                    expectedOutput =
                        checkNotNull(colorSelfTestExpectedOutput) {
                            "The color self-test requires sample.app.colorSelfTestExpectedOutput."
                        },
                    requireAudioSync = colorSelfTestRequireAudioSync,
                    durationSeconds = colorSelfTestSeconds,
                    resultFilePath = colorSelfTestResultFile,
                    playbackOptions = playbackOptions,
                    playerBackend = colorSelfTestBackend,
                    windowState = windowState,
                    verifyWindowLifecycle = windowLifecycleSelfTest,
                    onComplete = applicationScope::exitApplication,
                )
            } else {
                App(
                    initialVideoUrl = initialVideoUrl,
                    demoSubtitleEnabled = demoSubtitleEnabled,
                    initialLoop =
                        System.getProperty("sample.app.loop")
                            ?.toBooleanStrictOrNull()
                            ?: false,
                    playbackOptions = playbackOptions,
                    initialProjection = initialProjection,
                    initialDesktopBackendName = initialDesktopBackendName,
                    initialDesktopSourceAdapterName = System.getProperty("sample.app.sourceAdapter"),
                    initialFullscreen =
                        System.getProperty("sample.app.initialFullscreen")
                            ?.toBooleanStrictOrNull()
                            ?: false,
                    nativeFullscreen = windowState.placement == WindowPlacement.Fullscreen,
                    onNativeFullscreenRequest = { fullscreen ->
                        requestNativeFullscreen(fullscreen)
                    },
                )
            }
        }
    }
}

private val kMediaVlcSampleBackendNames =
    setOf(
        "LIBVLC_NATIVE",
        "LIBVLC4_TEXTURE",
        "LIBVLC4-TEXTURE",
        "KMEDIAVLC",
    )
