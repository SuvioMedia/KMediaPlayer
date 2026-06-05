package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

/**
 * Controls how video frames are rendered when more than one platform path is available.
 */
enum class VideoOutputMode {
    /**
     * Use the platform default. On macOS this favors tone-mapped SDR for stable Compose rendering unless
     * the legacy composemediaplayer.macos.hdrMetal property explicitly enables native HDR.
     */
    AUTO,

    /**
     * Render through the normal Compose bitmap/canvas path without requesting HDR tone mapping.
     */
    COMPOSE_SDR,

    /**
     * Ask the platform decoder to map HDR sources into SDR before frames reach Compose.
     */
    TONE_MAPPED_SDR,

    /**
     * Prefer a native HDR/EDR platform surface when available. This can require platform-native layering.
     */
    NATIVE_HDR,
}

@Stable
data class VideoPlaybackOptions(
    val videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
)

