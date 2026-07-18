package io.github.kdroidfilter.composemediaplayer

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidHdrProjectionRuntimeTest {
    @Test
    fun requiresGles3AndYuvTargetForHdrInput() {
        val noGles3 =
            AndroidHdrProjectionRuntimeSupport(
                supportsGles3 = false,
                supportsYuvTarget = true,
                supportsPqOutput = true,
                supportsHlgOutput = true,
            )
        val noYuvTarget = noGles3.copy(supportsGles3 = true, supportsYuvTarget = false)

        assertFalse(noGles3.supportsHdrInput)
        assertFalse(noYuvTarget.supportsHdrInput)
        assertEquals(emptySet(), noGles3.controlledOutputRanges(hdr10AndHlgDisplay()))
        assertEquals(emptySet(), noYuvTarget.controlledOutputRanges(hdr10AndHlgDisplay()))
    }

    @Test
    fun exposesOnlyTransfersSupportedByBothEglAndDisplay() {
        val runtime =
            AndroidHdrProjectionRuntimeSupport(
                supportsGles3 = true,
                supportsYuvTarget = true,
                supportsPqOutput = true,
                supportsHlgOutput = false,
            )

        assertTrue(runtime.supportsHdrInput)
        assertEquals(setOf(VideoDynamicRange.HDR10), runtime.controlledOutputRanges(hdr10AndHlgDisplay()))
        assertEquals(
            emptySet(),
            runtime.controlledOutputRanges(
                DisplayColorCapabilities(
                    isKnown = true,
                    supportedDynamicRanges = setOf(VideoDynamicRange.SDR, VideoDynamicRange.HLG),
                ),
            ),
        )
    }

    @Test
    fun projectedSurfaceAdvertisesOnlyVerifiedControlledRoutes() {
        val runtime =
            AndroidHdrProjectionRuntimeSupport(
                supportsGles3 = true,
                supportsYuvTarget = true,
                supportsPqOutput = true,
                supportsHlgOutput = true,
            )

        val capabilities =
            queryAndroidRendererColorCapabilities(
                display = hdr10AndHlgDisplay(),
                activeSurfaceType = SurfaceType.ProjectedGlSurfaceView,
                projectionRuntime = runtime,
            )

        assertEquals(
            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
            capabilities.controlledHdrDynamicRanges,
        )
        assertTrue(capabilities.supportsHdrProjection)
        assertTrue(capabilities.supportsToneMappingToSdr)
        assertEquals(emptySet(), capabilities.nativeSurfaceDynamicRanges)
        assertEquals(
            VideoDynamicRange.HDR10,
            capabilities.controlledOutputFor(VideoDynamicRange.HLG),
        )
    }

    @Test
    fun surfaceViewDoesNotClaimControlledProjectionRenderer() {
        val runtime =
            AndroidHdrProjectionRuntimeSupport(
                supportsGles3 = true,
                supportsYuvTarget = true,
                supportsPqOutput = true,
                supportsHlgOutput = true,
            )

        val capabilities =
            queryAndroidRendererColorCapabilities(
                display = hdr10AndHlgDisplay(),
                activeSurfaceType = SurfaceType.SurfaceView,
                projectionRuntime = runtime,
            )

        assertEquals(emptySet(), capabilities.controlledHdrDynamicRanges)
        assertFalse(capabilities.supportsHdrProjection)
        assertFalse(capabilities.supportsToneMappingToSdr)
        assertEquals(emptyMap(), capabilities.controlledOutputConversions)
        assertEquals(
            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
            capabilities.nativeSurfaceDynamicRanges,
        )
    }

    @Test
    fun hlgControlledGraphUsesPqOutputWithoutChangingOtherTransfers() {
        val hlg =
            ColorInfo
                .Builder()
                .setColorSpace(C.COLOR_SPACE_BT2020)
                .setColorRange(C.COLOR_RANGE_LIMITED)
                .setColorTransfer(C.COLOR_TRANSFER_HLG)
                .build()
        val pq = hlg.buildUpon().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()

        assertEquals(pq, hlg.toAndroidControlledOutputColorInfo(convertHlgOutputToPq = true))
        assertEquals(hlg, hlg.toAndroidControlledOutputColorInfo(convertHlgOutputToPq = false))
        assertEquals(pq, pq.toAndroidControlledOutputColorInfo(convertHlgOutputToPq = true))
    }

    @Test
    fun hdr10PlusProjectionRequiresPerFrameMetadataInputAndAdvertisesApplicationNotPassthrough() {
        val display =
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges =
                    setOf(
                        VideoDynamicRange.SDR,
                        VideoDynamicRange.HDR10,
                    ),
            )
        val withoutMetadata =
            AndroidHdrProjectionRuntimeSupport(
                supportsGles3 = true,
                supportsYuvTarget = true,
                supportsPqOutput = true,
                supportsHlgOutput = false,
                supportsHdr10PlusMetadataInput = false,
            )
        val withMetadata = withoutMetadata.copy(supportsHdr10PlusMetadataInput = true)

        assertEquals(setOf(VideoDynamicRange.HDR10), withoutMetadata.controlledOutputRanges(display))
        assertEquals(
            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS),
            withMetadata.controlledOutputRanges(display),
        )
        val capabilities =
            queryAndroidRendererColorCapabilities(
                display = display,
                activeSurfaceType = SurfaceType.ProjectedGlSurfaceView,
                projectionRuntime = withMetadata,
            )
        assertTrue(capabilities.supportsHdr10PlusApplication)
        assertFalse(capabilities.supportsHdr10PlusPassthrough)
    }

    @Test
    fun hdr10PlusCurveUsesBt2020LuminanceFromMaxSclAndStaysWithinDisplayPeak() {
        val metadata =
            Hdr10PlusMetadata(
                timestampUs = 41_708,
                applicationIdentifier = 4,
                applicationVersion = 1,
                targetedSystemDisplayMaximumLuminance = 1_000,
                targetedSystemDisplayActualPeakLuminance = null,
                masteringDisplayActualPeakLuminance = null,
                windows =
                    listOf(
                        Hdr10PlusWindowMetadata(
                            maxScl = listOf(10_000, 8_000, 9_000),
                            averageMaxRgb = 2_000,
                            distributionPercentiles = emptyList(),
                            fractionBrightPixels = 0,
                            toneMapping =
                                Hdr10PlusToneMapping(
                                    kneePointX = 2_048,
                                    kneePointY = 1_800,
                                    bezierCurveAnchors = listOf(512),
                                ),
                        ),
                    ),
            )

        val curve = assertNotNull(metadata.toToneCurve(displayPeakNits = 600.0))

        assertEquals(858.47f, curve.sourcePeakNits, absoluteTolerance = 1e-3f)
        assertEquals(33, curve.normalizedOutputLuminance.size)
        assertTrue(curve.normalizedOutputLuminance.all { it in 0f..0.06f })
        assertTrue(
            (1 until curve.normalizedOutputLuminance.size).all { index ->
                curve.normalizedOutputLuminance[index] >= curve.normalizedOutputLuminance[index - 1]
            },
        )
        curve.normalizedOutputLuminance.forEachIndexed { index, actual ->
            val inputNits = curve.sourcePeakNits * index / 32.0
            val expected = HdrColorMath.applyHdr10PlusToneMapping(inputNits, metadata, 600.0) / 10_000.0
            assertEquals(expected.toFloat(), actual, absoluteTolerance = 1e-6f)
        }
    }

    @Test
    fun hdrPoliciesUseControlledSurfaceWhenNativeCompositionCannotBeConfirmed() {
        val controlledRenderer =
            RendererColorCapabilities(
                controlledHdrDynamicRanges = setOf(VideoDynamicRange.HDR10),
                supportsHdrProjection = true,
            )
        val nativePlan =
            VideoColorPipelinePlan(
                route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
                outputDynamicRange = VideoDynamicRange.HDR10,
                metadataHandling = DynamicMetadataHandling.PASSTHROUGH,
                requestHonored = true,
            )

        assertTrue(
            shouldUseControlledHdrVerificationSurface(
                policy = DynamicRangePolicy.REQUIRE_HDR,
                plan = nativePlan,
                nativeOutputCanBeConfirmed = false,
                controlledRenderer = controlledRenderer,
            ),
        )
        assertTrue(
            shouldUseControlledHdrVerificationSurface(
                policy = DynamicRangePolicy.AUTO,
                plan = nativePlan,
                nativeOutputCanBeConfirmed = false,
                controlledRenderer = controlledRenderer,
            ),
        )
        assertTrue(
            shouldUseControlledHdrVerificationSurface(
                policy = DynamicRangePolicy.PREFER_HDR,
                plan = nativePlan,
                nativeOutputCanBeConfirmed = false,
                controlledRenderer = controlledRenderer,
            ),
        )
        assertFalse(
            shouldUseControlledHdrVerificationSurface(
                policy = DynamicRangePolicy.FORCE_SDR,
                plan = nativePlan,
                nativeOutputCanBeConfirmed = false,
                controlledRenderer = controlledRenderer,
            ),
        )
        assertFalse(
            shouldUseControlledHdrVerificationSurface(
                policy = DynamicRangePolicy.REQUIRE_HDR,
                plan = nativePlan,
                nativeOutputCanBeConfirmed = true,
                controlledRenderer = controlledRenderer,
            ),
        )
        assertTrue(
            shouldUseControlledHdrVerificationSurface(
                policy = DynamicRangePolicy.REQUIRE_HDR,
                plan = nativePlan.copy(route = ColorPipelineRoute.CONTROLLED_HDR_RENDERER),
                nativeOutputCanBeConfirmed = false,
                controlledRenderer = controlledRenderer,
            ),
        )
    }

    @Test
    fun hdr10OutputFailureAlsoDisablesHdr10PlusApplicationOnTheSamePqSurface() {
        assertEquals(
            setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS),
            VideoDynamicRange.HDR10.androidControlledFailureRanges(),
        )
        assertEquals(
            setOf(VideoDynamicRange.HLG),
            VideoDynamicRange.HLG.androidControlledFailureRanges(),
        )
    }

    @Test
    fun unavailableToneMapperNeverSelectsTheControlledSdrSurface() {
        val unsupportedPlan =
            VideoColorPipelinePlan(
                route = ColorPipelineRoute.UNSUPPORTED,
                outputDynamicRange = VideoDynamicRange.SDR,
                metadataHandling = DynamicMetadataHandling.DROPPED,
                requestHonored = false,
                fallbackReason = ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE,
            )

        assertFalse(
            shouldUseControlledToneMappingSurface(
                policy = DynamicRangePolicy.AUTO,
                plan = unsupportedPlan,
                rendererSupportsToneMapping = false,
                sourceCanBeToneMapped = true,
            ),
        )
        assertTrue(
            shouldUseControlledToneMappingSurface(
                policy = DynamicRangePolicy.AUTO,
                plan = unsupportedPlan,
                rendererSupportsToneMapping = true,
                sourceCanBeToneMapped = true,
            ),
        )
        assertFalse(
            shouldUseControlledToneMappingSurface(
                policy = DynamicRangePolicy.AUTO,
                plan = unsupportedPlan,
                rendererSupportsToneMapping = true,
                sourceCanBeToneMapped = false,
            ),
        )
    }

    @Test
    fun sourceBridgeSdrNeverEnablesASecondControlledToneMapper() {
        val sourceBridgePlan =
            VideoColorPipelinePlan(
                route = ColorPipelineRoute.SOURCE_BRIDGE_SDR,
                outputDynamicRange = VideoDynamicRange.SDR,
                metadataHandling = DynamicMetadataHandling.APPLIED_BY_SOURCE_BRIDGE,
                requestHonored = true,
                fallbackReason = ColorPipelineFallbackReason.EXPLICIT_SDR_REQUEST,
            )

        assertFalse(
            shouldUseControlledToneMappingSurface(
                policy = DynamicRangePolicy.FORCE_SDR,
                plan = sourceBridgePlan,
                rendererSupportsToneMapping = true,
                sourceCanBeToneMapped = true,
            ),
        )
    }

    @Test
    fun autoUsesControlledSdrWhenNeitherNativeNorControlledHdrCanBeConfirmed() {
        val nativePlan =
            VideoColorPipelinePlan(
                route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
                outputDynamicRange = VideoDynamicRange.HDR10,
                metadataHandling = DynamicMetadataHandling.PASSTHROUGH,
                requestHonored = true,
            )

        assertTrue(
            shouldUseControlledToneMappingSurface(
                policy = DynamicRangePolicy.AUTO,
                plan = nativePlan,
                rendererSupportsToneMapping = true,
                sourceCanBeToneMapped = true,
                nativeHdrUnavailable = true,
            ),
        )
    }

    private fun hdr10AndHlgDisplay() =
        DisplayColorCapabilities(
            isKnown = true,
            supportedDynamicRanges =
                setOf(VideoDynamicRange.SDR, VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
        )
}
