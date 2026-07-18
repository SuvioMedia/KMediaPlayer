package io.github.kdroidfilter.composemediaplayer.consumer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.PlaybackEvent
import io.github.kdroidfilter.composemediaplayer.RenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension
import io.github.kdroidfilter.composemediaplayer.createRenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.SharedFlow

@Composable
fun PublishedVideoSurface(playerState: RenderableVideoPlayerState) {
    VideoPlayerSurface(
        playerState = playerState,
        modifier = Modifier,
        contentScale = ContentScale.Fit,
    )
}

@Composable
fun PublishedLegacyVideoSurface(playerState: VideoPlayerState) {
    VideoPlayerSurface(playerState = playerState)
}

fun createPublishedVideoState(): RenderableVideoPlayerState = createRenderableVideoPlayerState()

fun createLegacyPublishedVideoState(): VideoPlayerState = createVideoPlayerState()

fun createPublishedAssSubtitleExtension(): SubtitlePipelineExtension = AssSubtitleExtension()

fun playbackEvents(playerState: VideoPlayerState): SharedFlow<PlaybackEvent> = playerState.playbackEvents

fun configureSubtitles(
    playerState: VideoPlayerState,
    textStyle: TextStyle,
    backgroundColor: Color,
) {
    playerState.subtitleTextStyle = textStyle
    playerState.subtitleBackgroundColor = backgroundColor
}

fun openPlatformFile(
    playerState: VideoPlayerState,
    file: PlatformFile,
) {
    playerState.openFile(file)
}
