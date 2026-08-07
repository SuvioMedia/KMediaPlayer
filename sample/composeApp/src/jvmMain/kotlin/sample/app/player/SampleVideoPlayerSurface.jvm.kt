package sample.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.PreviewableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackSurface
import sample.app.theme.AppTheme

internal actual val sampleVideoPickerUsesAllFiles: Boolean = true

internal actual val samplePlayerSheetsUseInlineHost: Boolean = true

@Composable
internal actual fun SampleVideoPlayerSurface(
    player: SampleVideoPlayerHandle,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    val playerState = player.playerState
    val desktopSurfaceHost = player.playbackSurfaceHost as? DesktopSamplePlaybackSurfaceHost
    Box(modifier = modifier.background(Color.Black)) {
        if (playerState is PreviewableVideoPlayerState || desktopSurfaceHost == null) {
            VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                overlay = overlay,
            )
        } else {
            DesktopPlaybackSurface(
                session = desktopSurfaceHost.session,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                overlay = {
                    // Nucleus renders the native-video overlay in its own ComposeScene, so it
                    // does not inherit the Material theme from App's outer scene automatically.
                    AppTheme {
                        Box(modifier = Modifier.fillMaxSize()) {
                            overlay()
                        }
                    }
                },
                onSurfaceAttached = player::notifySurfaceAttached,
            )
        }
    }
}
