package sample.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.composemediaplayer.VideoOutputMode
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.nucleus.graalvm.GraalVmInitializer
import kotlinx.coroutines.delay

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
    val playbackOptions =
        VideoPlaybackOptions(
            videoOutputMode =
                System.getProperty("sample.app.videoOutputMode")
                    ?.trim()
                    ?.uppercase()
                    ?.let { runCatching { VideoOutputMode.valueOf(it) }.getOrNull() }
                    ?: VideoOutputMode.AUTO,
        )

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
            App(
                initialVideoUrl = initialVideoUrl,
                demoSubtitleEnabled = demoSubtitleEnabled,
                playbackOptions = playbackOptions,
            )
        }
    }
}
