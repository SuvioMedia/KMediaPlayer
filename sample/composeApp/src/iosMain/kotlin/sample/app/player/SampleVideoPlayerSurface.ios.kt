package sample.app.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface

internal actual val sampleVideoPickerUsesAllFiles: Boolean = false

@Composable
internal actual fun SampleVideoPlayerSurface(
    player: SampleVideoPlayerHandle,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    VideoPlayerSurface(player.playerState, modifier, contentScale, overlay)
}
