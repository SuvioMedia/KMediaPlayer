package io.github.kdroidfilter.composemediaplayer.consumer.mpv

import androidx.compose.runtime.Composable
import io.github.kdroidfilter.composemediaplayer.BackendVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerBackend
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.inspectMpvBackend
import io.github.kdroidfilter.composemediaplayer.mpvVideoPlayerBackend

fun publishedBackends(options: MpvPlaybackOptions = MpvPlaybackOptions()): List<VideoPlayerBackend> =
    listOf(mpvVideoPlayerBackend(options))

fun publishedMpvAvailability(options: MpvPlaybackOptions = MpvPlaybackOptions()): MpvBackendAvailability =
    inspectMpvBackend(options)

@Composable
fun PublishedMpvSurface(playerState: VideoPlayerState) {
    BackendVideoPlayerSurface(playerState = playerState)
}
