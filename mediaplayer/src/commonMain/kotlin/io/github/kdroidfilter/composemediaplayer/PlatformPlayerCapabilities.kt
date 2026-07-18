package io.github.kdroidfilter.composemediaplayer

internal expect fun platformPlayerCapabilities(playbackOptions: VideoPlaybackOptions): PlayerCapabilities

internal fun platformPlayerCapabilities(): PlayerCapabilities = platformPlayerCapabilities(VideoPlaybackOptions())
