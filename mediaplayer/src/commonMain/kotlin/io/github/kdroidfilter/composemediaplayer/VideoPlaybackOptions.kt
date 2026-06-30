package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

/**
 * Controls how video frames are rendered when more than one platform path is available.
 */
enum class VideoOutputMode {
    /**
     * Use the platform default. On macOS this favors tone-mapped SDR for stable Compose rendering unless
     * the legacy composemediaplayer.macos.hdrMetal property explicitly enables native HDR. On Windows and Linux,
     * this keeps the platform/canvas path unless a source needs a fallback backend.
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
     * Prefer a native HDR/EDR platform surface when available. On macOS this selects the AVFoundation native HDR
     * surface. On Windows and Linux with [DesktopVideoBackend.AUTO], this selects the libVLC native-view backend
     * as a best-effort HDR-preserving path that avoids copying frames into Compose.
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

/**
 * Selects the JVM desktop playback backend.
 *
 * [AUTO] keeps the platform default and optional fallback policy. [PLATFORM] disables optional fallbacks.
 * [LIBVLC] requires the in-process libVLC canvas backend on macOS, Windows, and Linux. [LIBVLC_NATIVE] requires
 * the libVLC native-view backend: NSView on macOS, HWND on Windows, and X11/XWayland xwindow on Linux.
 */
enum class DesktopVideoBackend {
    /**
     * Use the platform default and optional fallback policy.
     */
    AUTO,

    /**
     * Prefer the platform media framework.
     */
    PLATFORM,

    /**
     * Use a user-installed libVLC backend that copies decoded frames into Compose.
     */
    LIBVLC,

    /**
     * Use a user-installed libVLC backend with VLC rendering directly into a native desktop view. This avoids the
     * Compose SDR frame-copy path, but HDR passthrough still depends on VLC, the OS compositor, GPU, and display.
     */
    LIBVLC_NATIVE,
}

/**
 * Controls whether the player should infer 3D/VR projection metadata from source names and media dimensions.
 */
enum class VideoProjectionDetectionMode {
    /**
     * Infer projection only when [VideoPlaybackOptions.projection] is left at its default flat 2D value.
     */
    AUTO,

    /**
     * Never infer projection. The configured [VideoPlaybackOptions.projection] is used as-is.
     */
    DISABLED,
}

@Stable
data class VideoPlaybackOptions(
    val videoOutputMode: VideoOutputMode = VideoOutputMode.AUTO,
    val dolbyVisionMode: DolbyVisionMode = DolbyVisionMode.AUTO,
    val desktopVideoBackend: DesktopVideoBackend = DesktopVideoBackend.AUTO,
    val projection: VideoProjectionSettings = VideoProjectionSettings(),
    val projectionView: VideoProjectionViewSettings = VideoProjectionViewSettings(),
    val projectionViewControlMode: VideoProjectionViewControlMode = VideoProjectionViewControlMode.AUTO,
    val projectionTextureCrop: VideoTextureCrop = VideoTextureCrop(),
    val projectionDetectionMode: VideoProjectionDetectionMode = VideoProjectionDetectionMode.AUTO,
)
