package sample.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopVideoPlayerWindow

internal actual val sampleVideoPickerUsesAllFiles: Boolean = true

@Composable
internal actual fun SampleVideoPlayerSurface(
    player: SampleVideoPlayerHandle,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    val playerState = player.playerState
    var windowVisible by remember { mutableStateOf(false) }
    LaunchedEffect(playerState, playerState.hasMedia) {
        if (playerState.hasMedia) windowVisible = true
    }

    Box(
        modifier =
            modifier
                .background(Color.Black)
                .clickable { windowVisible = true },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (windowVisible) "Playback is open in the native desktop window" else "Click to reopen player",
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    DesktopVideoPlayerWindow(
        playerState = playerState,
        visible = windowVisible && playerState.hasMedia,
        onCloseRequest = { windowVisible = false },
        title = "Compose Media Player",
        contentScale = contentScale,
        overlay = { overlay() },
        onSurfaceAttached = { player.notifySurfaceAttached() },
    )
}
