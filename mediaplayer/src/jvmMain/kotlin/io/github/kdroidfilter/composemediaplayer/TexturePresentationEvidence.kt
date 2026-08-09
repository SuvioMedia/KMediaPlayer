package io.github.kdroidfilter.composemediaplayer

import dev.nucleusframework.window.tao.TextureViewHostCapabilities
import dev.nucleusframework.window.tao.TextureViewHostDynamicRange
import dev.nucleusframework.window.tao.TextureViewHostPixelFormat
import dev.nucleusframework.window.tao.TextureViewHostPresentationState

/** The output evidence required before a producer frame can be reported as configured. */
internal enum class TexturePresentationOutputRequirement {
    KNOWN_OUTPUT,
    HDR_TEN_BIT_OUTPUT,
    FP16_SCRGB_OUTPUT,
}

/**
 * Returns true only for a system presentation that happened after submission on the same output
 * generation. Rendering a producer frame alone is deliberately not sufficient evidence.
 */
internal fun TextureViewHostCapabilities.hasPresentedTextureFrameAfter(
    submittedGeneration: Long,
    submittedPresentCount: Long,
    outputRequirement: TexturePresentationOutputRequirement,
): Boolean {
    if (presentationState != TextureViewHostPresentationState.PRESENTED) return false
    if (generation != submittedGeneration) return false
    if (presentedFrameCount <= submittedPresentCount) return false
    if (outputPixelFormat == TextureViewHostPixelFormat.UNKNOWN) return false

    return when (outputRequirement) {
        TexturePresentationOutputRequirement.KNOWN_OUTPUT -> true
        TexturePresentationOutputRequirement.HDR_TEN_BIT_OUTPUT ->
            actualDynamicRange == TextureViewHostDynamicRange.HDR &&
                outputPixelFormat.componentBitDepth >= 10
        TexturePresentationOutputRequirement.FP16_SCRGB_OUTPUT ->
            actualDynamicRange == TextureViewHostDynamicRange.HDR &&
                outputPixelFormat == TextureViewHostPixelFormat.RGBA16_FLOAT_SCRGB
    }
}
