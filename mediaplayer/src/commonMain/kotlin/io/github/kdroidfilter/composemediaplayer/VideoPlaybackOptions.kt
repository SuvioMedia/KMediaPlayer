package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

internal fun PlayerCapabilities.withPipelineExtensions(options: VideoPlaybackOptions): PlayerCapabilities =
    copy(
        colorConversionCapabilities =
            options.extensions.fold(colorConversionCapabilities) { accumulated, extension ->
                accumulated.mergedWith(extension.status().colorConversionCapabilities)
            },
    )

internal fun ColorConversionCapabilities.mergedWith(other: ColorConversionCapabilities): ColorConversionCapabilities =
    ColorConversionCapabilities(
        supportsDolbyVisionProfile7To8 = supportsDolbyVisionProfile7To8 || other.supportsDolbyVisionProfile7To8,
        supportsHdr10PlusApplication = supportsHdr10PlusApplication || other.supportsHdr10PlusApplication,
        supportsHdrToSdrSourceBridge = supportsHdrToSdrSourceBridge || other.supportsHdrToSdrSourceBridge,
        supportsStreamingVOD = supportsStreamingVOD || other.supportsStreamingVOD,
    )

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
     * Compose frame-copy path but is not evidence of a configured HDR swapchain or HDR display output.
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

/**
 * Selects the playback engine used by the WebAssembly target.
 */
enum class WebPlaybackEngine {
    /**
     * Use the directly linked Kotlin/Wasm Movi player for demuxing, decoding, adaptive streaming,
     * audio-track selection, and DRM.
     */
    MOVI,

    /**
     * Use native HTML5 video without a bundled adaptive-streaming implementation.
     *
     * This is an explicit compatibility route for non-adaptive sources. Recognized HLS, DASH and MSS manifests
     * require [MOVI], and a Movi failure never switches to this engine automatically.
     */
    LEGACY,
}

/**
 * Runtime-only DRM configuration for the WebAssembly player.
 *
 * The license URL and request headers are passed only to the license request made by Movi/Shaka. They are never
 * reused as media request headers. [toString] deliberately redacts all values so accidental logging cannot expose
 * license endpoints or credentials.
 */
@Stable
class WebDrmConfiguration(
    val licenseUrl: String,
    licenseRequestHeaders: Map<String, String> = emptyMap(),
) {
    val licenseRequestHeaders: Map<String, String> = licenseRequestHeaders.toMap()

    init {
        require(licenseUrl.isNotBlank()) { "The DRM license URL must not be blank." }
        require(this.licenseRequestHeaders.keys.none(String::isBlank)) {
            "DRM license request header names must not be blank."
        }
    }

    override fun toString(): String =
        "WebDrmConfiguration(licenseUrl=<redacted>, licenseRequestHeaders=<redacted:${licenseRequestHeaders.size}>)"
}

@Stable
data class VideoPlaybackOptions(
    val dynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.AUTO,
    val dolbyVisionPolicy: DolbyVisionPolicy = DolbyVisionPolicy.AUTO,
    val desktopVideoBackend: DesktopVideoBackend = DesktopVideoBackend.AUTO,
    val extensions: List<VideoPipelineExtension> = emptyList(),
    val projection: VideoProjectionSettings = VideoProjectionSettings(),
    val projectionView: VideoProjectionViewSettings = VideoProjectionViewSettings(),
    val projectionViewControlMode: VideoProjectionViewControlMode = VideoProjectionViewControlMode.AUTO,
    val projectionTextureCrop: VideoTextureCrop = VideoTextureCrop(),
    val projectionDetectionMode: VideoProjectionDetectionMode = VideoProjectionDetectionMode.AUTO,
    val webPlaybackEngine: WebPlaybackEngine = WebPlaybackEngine.MOVI,
    val webDrmConfiguration: WebDrmConfiguration? = null,
) {
    init {
        val invalidIds = extensions.map(VideoPipelineExtension::id).filter(String::isBlank)
        require(invalidIds.isEmpty()) { "Video pipeline extension ids must not be blank." }

        val duplicateIds =
            extensions
                .groupingBy(VideoPipelineExtension::id)
                .eachCount()
                .filterValues { count -> count > 1 }
                .keys
        require(duplicateIds.isEmpty()) {
            "Video pipeline extension ids must be unique: ${duplicateIds.sorted().joinToString()}."
        }
    }

    val extensionStatuses: List<VideoPipelineExtensionStatus>
        get() = extensions.map(VideoPipelineExtension::status)
}
