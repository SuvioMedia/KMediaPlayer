package io.github.kdroidfilter.composemediaplayer

import dev.nucleusframework.window.WindowDynamicRangeMode
import dev.nucleusframework.window.tao.TextureViewHostCapabilities
import dev.nucleusframework.window.tao.TextureViewHostDynamicRange
import dev.nucleusframework.window.tao.TextureViewHostPixelFormat
import dev.nucleusframework.window.tao.TextureViewHostPresentationState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TexturePresentationEvidenceTest {
    @Test
    fun requiresLaterPresentFromSameGeneration() {
        val generation = 7L
        val submittedPresentCount = 11L

        assertFalse(
            host(generation, submittedPresentCount).hasPresentedTextureFrameAfter(
                generation,
                submittedPresentCount,
                TexturePresentationOutputRequirement.KNOWN_OUTPUT,
            ),
        )
        assertFalse(
            host(generation + 1, submittedPresentCount + 1).hasPresentedTextureFrameAfter(
                generation,
                submittedPresentCount,
                TexturePresentationOutputRequirement.KNOWN_OUTPUT,
            ),
        )
        assertTrue(
            host(generation, submittedPresentCount + 1).hasPresentedTextureFrameAfter(
                generation,
                submittedPresentCount,
                TexturePresentationOutputRequirement.KNOWN_OUTPUT,
            ),
        )
    }

    @Test
    fun distinguishesSdrPqAndFp16OutputEvidence() {
        val generation = 3L

        assertTrue(
            host(
                generation = generation,
                presentedFrames = 1,
                dynamicRange = TextureViewHostDynamicRange.SDR,
                pixelFormat = TextureViewHostPixelFormat.RGBA8_SRGB,
            ).hasPresentedTextureFrameAfter(
                generation,
                0,
                TexturePresentationOutputRequirement.KNOWN_OUTPUT,
            ),
        )
        assertTrue(
            host(
                generation = generation,
                presentedFrames = 1,
                pixelFormat = TextureViewHostPixelFormat.RGB10_A2_BT2020_PQ,
            ).hasPresentedTextureFrameAfter(
                generation,
                0,
                TexturePresentationOutputRequirement.HDR_TEN_BIT_OUTPUT,
            ),
        )
        assertFalse(
            host(
                generation = generation,
                presentedFrames = 1,
                pixelFormat = TextureViewHostPixelFormat.RGB10_A2_BT2020_PQ,
            ).hasPresentedTextureFrameAfter(
                generation,
                0,
                TexturePresentationOutputRequirement.FP16_SCRGB_OUTPUT,
            ),
        )
        assertTrue(
            host(generation, 1).hasPresentedTextureFrameAfter(
                generation,
                0,
                TexturePresentationOutputRequirement.FP16_SCRGB_OUTPUT,
            ),
        )
    }

    private fun host(
        generation: Long,
        presentedFrames: Long,
        dynamicRange: TextureViewHostDynamicRange = TextureViewHostDynamicRange.HDR,
        pixelFormat: TextureViewHostPixelFormat = TextureViewHostPixelFormat.RGBA16_FLOAT_SCRGB,
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
