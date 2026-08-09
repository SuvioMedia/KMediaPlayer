package io.github.kdroidfilter.composemediaplayer.mpv

import dev.nucleusframework.window.WindowDynamicRangeMode
import dev.nucleusframework.window.tao.TextureViewHostCapabilities
import dev.nucleusframework.window.tao.TextureViewHostDynamicRange
import dev.nucleusframework.window.tao.TextureViewHostPixelFormat
import dev.nucleusframework.window.tao.TextureViewHostPresentationState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvTexturePresentationEvidenceTest {
    @Test
    fun acceptsSdrAndHdrOnlyWithMatchingOutputEvidence() {
        val generation = 9L

        assertTrue(
            host(
                generation,
                TextureViewHostDynamicRange.SDR,
                TextureViewHostPixelFormat.RGBA8_SRGB,
            ).hasPresentedMpvTextureFrameAfter(
                generation,
                4,
                MpvTextureOutputRequirement.KNOWN_OUTPUT,
            ),
        )
        assertTrue(
            host(
                generation,
                TextureViewHostDynamicRange.HDR,
                TextureViewHostPixelFormat.RGB10_A2_BT2020_PQ,
            ).hasPresentedMpvTextureFrameAfter(
                generation,
                4,
                MpvTextureOutputRequirement.HDR_TEN_BIT_OUTPUT,
            ),
        )
        assertFalse(
            host(
                generation,
                TextureViewHostDynamicRange.HDR,
                TextureViewHostPixelFormat.RGB10_A2_BT2020_PQ,
            ).hasPresentedMpvTextureFrameAfter(
                generation,
                4,
                MpvTextureOutputRequirement.FP16_SCRGB_OUTPUT,
            ),
        )
    }

    @Test
    fun rejectsPendingStaleAndNotYetPresentedFrames() {
        val generation = 12L

        assertFalse(
            host(generation, presentedFrames = 5).hasPresentedMpvTextureFrameAfter(
                generation,
                5,
                MpvTextureOutputRequirement.KNOWN_OUTPUT,
            ),
        )
        assertFalse(
            host(generation + 1).hasPresentedMpvTextureFrameAfter(
                generation,
                5,
                MpvTextureOutputRequirement.KNOWN_OUTPUT,
            ),
        )
        assertFalse(
            host(generation, presentationState = TextureViewHostPresentationState.PENDING)
                .hasPresentedMpvTextureFrameAfter(
                    generation,
                    5,
                    MpvTextureOutputRequirement.KNOWN_OUTPUT,
                ),
        )
    }

    private fun host(
        generation: Long,
        dynamicRange: TextureViewHostDynamicRange = TextureViewHostDynamicRange.HDR,
        pixelFormat: TextureViewHostPixelFormat = TextureViewHostPixelFormat.RGBA16_FLOAT_SCRGB,
        presentedFrames: Long = 6,
        presentationState: TextureViewHostPresentationState = TextureViewHostPresentationState.PRESENTED,
    ): TextureViewHostCapabilities =
        TextureViewHostCapabilities(
            requestedMode = WindowDynamicRangeMode.EXTENDED_IF_AVAILABLE,
            actualDynamicRange = dynamicRange,
            presentationState = presentationState,
            sdrWhiteLevelNits = 203f,
            maximumLuminanceNits = if (dynamicRange == TextureViewHostDynamicRange.HDR) 1_000f else 203f,
            headroom = if (dynamicRange == TextureViewHostDynamicRange.HDR) 4.9f else 1f,
            generation = generation,
            presentedFrameCount = presentedFrames,
            outputPixelFormat = pixelFormat,
            producerInfo = null,
        )
}
