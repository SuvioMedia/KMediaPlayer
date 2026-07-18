import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension
import platform.UIKit.UIViewController
import sample.app.App

fun MainViewController(): UIViewController =
    ComposeUIViewController {
        val playbackOptions =
            remember {
                VideoPlaybackOptions(
                    extensions = listOf(AssSubtitleExtension()),
                )
            }
        App(playbackOptions = playbackOptions)
    }
