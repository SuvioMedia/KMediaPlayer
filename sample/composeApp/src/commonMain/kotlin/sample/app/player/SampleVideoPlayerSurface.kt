package sample.app.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

internal expect val sampleVideoPickerUsesAllFiles: Boolean

internal expect val samplePlayerSheetsUseInlineHost: Boolean

@Composable
internal expect fun SampleVideoPlayerSurface(
    player: SampleVideoPlayerHandle,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
)
