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

/**
 * Controls how Dolby Vision streams are handled on platforms that expose more than one compatibility path.
 */
enum class DolbyVisionMode {
    /**
     * Use the platform default and only apply compatibility workarounds when the implementation knows they are needed.
     */
    AUTO,

    /**
     * Keep Dolby Vision signaling intact and rely on the platform decoder/display path.
     */
    PASSTHROUGH,

    /**
     * Prefer an HDR10/HEVC-compatible path when the platform can fall back from Dolby Vision safely.
     */
    PREFER_HDR10_COMPATIBLE,

    /**
     * Request realtime Dolby Vision Profile 7 to Profile 8.1 conversion.
     *
     * This is only active on builds where [PlayerCapabilities.hdr.supportsDolbyVisionProfile7To8Transcoding]
     * is true. Other builds keep playback fail-safe and expose the unsupported request through diagnostics.
     */
    TRANSCODE_PROFILE_7_TO_8_1,
}

@Stable
data class VideoPlaybackOptions(
    val videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
    val dolbyVisionMode: DolbyVisionMode = DolbyVisionMode.AUTO,
)
