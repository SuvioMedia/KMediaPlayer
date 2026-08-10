package io.github.kdroidfilter.composemediaplayer.consumer.libvlc

import androidx.compose.runtime.Composable
import io.github.kdroidfilter.composemediaplayer.BackendVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.LibVlcAndroidDecodeMode
import io.github.kdroidfilter.composemediaplayer.LibVlcPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.LibVlcRuntimeSource
import io.github.kdroidfilter.composemediaplayer.VideoPlayerBackend
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.libVlcVideoPlayerBackend

fun publishedLibVlcBackends(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): List<VideoPlayerBackend> =
    listOf(libVlcVideoPlayerBackend(options))

fun publishedBundledLibVlcOptions(): LibVlcPlaybackOptions =
    LibVlcPlaybackOptions(
        runtimeSource = LibVlcRuntimeSource.Bundled,
        androidDecodeMode = LibVlcAndroidDecodeMode.SOFTWARE_ONLY,
    )

@Composable
fun PublishedLibVlcSurface(playerState: VideoPlayerState) {
    BackendVideoPlayerSurface(playerState = playerState)
}
