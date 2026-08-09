package io.github.kdroidfilter.composemediaplayer.mpv

import dev.nucleusframework.window.tao.TextureViewHostCapabilities
import dev.nucleusframework.window.tao.TextureViewHostDynamicRange
import dev.nucleusframework.window.tao.TextureViewHostPixelFormat
import dev.nucleusframework.window.tao.TextureViewHostPresentationState

internal enum class MpvTextureOutputRequirement {
    KNOWN_OUTPUT,
    HDR_TEN_BIT_OUTPUT,
    FP16_SCRGB_OUTPUT,
}

internal fun TextureViewHostCapabilities.hasPresentedMpvTextureFrameAfter(
    submittedGeneration: Long,
    submittedPresentCount: Long,
    outputRequirement: MpvTextureOutputRequirement,
): Boolean {
    if (presentationState != TextureViewHostPresentationState.PRESENTED) return false
    if (generation != submittedGeneration) return false
    if (presentedFrameCount <= submittedPresentCount) return false
    if (outputPixelFormat == TextureViewHostPixelFormat.UNKNOWN) return false

    return when (outputRequirement) {
        MpvTextureOutputRequirement.KNOWN_OUTPUT -> true
        MpvTextureOutputRequirement.HDR_TEN_BIT_OUTPUT ->
            actualDynamicRange == TextureViewHostDynamicRange.HDR &&
                outputPixelFormat.componentBitDepth >= 10
        MpvTextureOutputRequirement.FP16_SCRGB_OUTPUT ->
            actualDynamicRange == TextureViewHostDynamicRange.HDR &&
                outputPixelFormat == TextureViewHostPixelFormat.RGBA16_FLOAT_SCRGB
    }
}
