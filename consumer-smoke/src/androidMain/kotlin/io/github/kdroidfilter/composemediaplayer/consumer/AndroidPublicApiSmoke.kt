package io.github.kdroidfilter.composemediaplayer.consumer

import androidx.compose.runtime.Composable
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import io.github.kdroidfilter.composemediaplayer.AndroidMediaSourceProvider
import io.github.kdroidfilter.composemediaplayer.AndroidMediaSourceRequest
import io.github.kdroidfilter.composemediaplayer.AndroidSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.RenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SurfaceType
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.VideoSourcePipelineExtension
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension
import io.github.kdroidfilter.composemediaplayer.kmediabridge.KMediaBridgeAndroidExtension
import io.github.kdroidfilter.composemediaplayer.rememberRenderableVideoPlayerState

@UnstableApi
fun mediaItem(request: AndroidMediaSourceRequest): MediaItem = request.mediaItem

@UnstableApi
fun mediaSourceProvider(factory: (AndroidMediaSourceRequest) -> MediaSource?): AndroidMediaSourceProvider =
    AndroidMediaSourceProvider { request -> factory(request) }

@UnstableApi
fun createPublishedAndroidAssSubtitleExtension(): AndroidSubtitlePipelineExtension = AssSubtitleExtension()

fun createPublishedKMediaBridgeExtension(): VideoSourcePipelineExtension = KMediaBridgeAndroidExtension()

@Composable
@UnstableApi
fun rememberConfiguredAndroidPlayer(provider: AndroidMediaSourceProvider): RenderableVideoPlayerState =
    rememberRenderableVideoPlayerState(androidMediaSourceProvider = provider)

@Composable
@UnstableApi
fun androidVideoSurface(
    playerState: RenderableVideoPlayerState,
    surfaceType: SurfaceType,
) {
    VideoPlayerSurface(
        playerState = playerState,
        surfaceType = surfaceType,
    )
}
