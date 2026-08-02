package sample.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionType
import io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer
import kotlinx.coroutines.delay
import sample.app.player.desktopPipelineExtensions

fun main(args: Array<String>) {
    GraalVmInitializer.initialize()
    val initialVideoUrl =
        args.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("sample.app.videoUrl")?.takeIf { it.isNotBlank() }
    val demoSubtitleEnabled =
        System.getProperty("sample.app.demoSubtitle")
            ?.toBooleanStrictOrNull()
            ?: (initialVideoUrl == null)
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
            extensions = desktopPipelineExtensions,
        )
    val colorSelfTestSeconds =
        System.getProperty("sample.app.colorSelfTestSeconds")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
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

    application {
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
        Window(
            onCloseRequest = ::exitApplication,
            title = "Compose Media Player",
            state = windowState,
        ) {
            LaunchedEffect(windowX, windowY, windowWidth, windowHeight) {
                if (windowX != null && windowY != null) {
                    delay(300)
                    window.setLocation(windowX, windowY)
                    window.setSize(windowWidth, windowHeight)
                    window.toFront()
                }
            }
            if (colorSelfTestSeconds != null) {
                DesktopColorPipelineSelfTest(
                    inputUri = checkNotNull(initialVideoUrl) { "The color self-test requires sample.app.videoUrl." },
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
                    onComplete = ::exitApplication,
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
                    initialDesktopBackendName = System.getProperty("sample.app.playbackBackend"),
                    initialDesktopSourceAdapterName = System.getProperty("sample.app.sourceAdapter"),
                    initialFullscreen =
                        System.getProperty("sample.app.initialFullscreen")
                            ?.toBooleanStrictOrNull()
                            ?: false,
                )
            }
        }
    }
}
