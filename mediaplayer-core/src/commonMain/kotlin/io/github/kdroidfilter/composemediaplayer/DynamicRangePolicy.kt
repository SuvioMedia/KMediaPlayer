package io.github.kdroidfilter.composemediaplayer

/** Controls the dynamic-range contract requested from the player. */
enum class DynamicRangePolicy {
    /** Select native HDR, a controlled HDR renderer, or verified SDR tone mapping in that order. */
    AUTO,

    /** Prefer HDR, but allow a verified SDR fallback when the active output cannot present HDR. */
    PREFER_HDR,

    /** Fail the video pipeline instead of silently falling back when HDR cannot be preserved. */
    REQUIRE_HDR,

    /** Always produce a color-managed SDR output, tone-mapping HDR sources when necessary. */
    FORCE_SDR,
}

/**
 * Controls how Dolby Vision streams are handled on platforms that expose more than one compatibility path.
 */
enum class DolbyVisionPolicy {
    /**
     * Use the platform default and only apply compatibility workarounds when the implementation knows they are needed.
     */
    AUTO,

    /**
     * Keep Dolby Vision signaling intact and rely on the platform decoder/display path.
     */
    REQUIRE_NATIVE,

    /**
     * Prefer an HDR10/HEVC-compatible path when the platform can fall back from Dolby Vision safely.
     */
    PREFER_HDR10_BASE_LAYER,

    /**
     * Request bounded streaming Dolby Vision Profile 7 to Profile 8.1 conversion.
     *
     * This is only active when a compatible converter is installed. Other builds keep playback fail-safe
     * and expose the unsupported request through [VideoColorPipelineStatus].
     */
    CONVERT_PROFILE_7_TO_8_1,
}
