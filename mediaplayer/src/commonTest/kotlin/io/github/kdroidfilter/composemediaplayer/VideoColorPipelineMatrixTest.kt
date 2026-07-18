@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth")

package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoColorPipelineMatrixTest {
    @Test
    fun `platform source display policy projection and converter matrix keeps every route honest`() {
        platformFixtures().forEach { platform ->
            sourceFixtures().forEach { source ->
                displayFixtures().forEach { display ->
                    DynamicRangePolicy.entries.forEach { dynamicPolicy ->
                        DolbyVisionPolicy.entries.forEach { dolbyVisionPolicy ->
                            listOf(false, true).forEach { projection ->
                                listOf(false, true).forEach { converterAvailable ->
                                    val request =
                                        VideoColorPipelineRequest(
                                            source = source,
                                            display = display,
                                            decoder = platform.decoder,
                                            renderer = platform.renderer,
                                            conversion =
                                                if (converterAvailable) {
                                                    FULL_CONVERSION
                                                } else {
                                                    ColorConversionCapabilities()
                                                },
                                            dynamicRangePolicy = dynamicPolicy,
                                            dolbyVisionPolicy = dolbyVisionPolicy,
                                            isProjection = projection,
                                            nativeSurfaceAvailable = platform.nativeSurfaceAvailable,
                                            surfaceKind =
                                                if (projection) {
                                                    VideoSurfaceKind.CONTROLLED_GPU_SURFACE
                                                } else {
                                                    platform.surfaceKind
                                                },
                                        )
                                    val context =
                                        listOf(
                                            "platform=${platform.name}",
                                            "source=${source.fixtureName()}",
                                            "display=$display",
                                            "dynamic=$dynamicPolicy",
                                            "dv=$dolbyVisionPolicy",
                                            "projection=$projection",
                                            "converter=$converterAvailable",
                                        ).joinToString()
                                    val plan = VideoColorPipelinePlanner.plan(request)

                                    assertRoutePreconditions(request, plan, context)
                                    val inferred =
                                        plan.toStatus(
                                            request = request,
                                            decoderName = platform.name,
                                            verification = ColorPipelineVerification.INFERRED,
                                        )
                                    assertEquals(VideoDynamicRange.UNKNOWN, inferred.outputDynamicRange, context)
                                    assertFalse(inferred.requestHonored, context)
                                    assertEquals(request.decoder, inferred.decoderCapabilities, context)
                                    assertEquals(request.renderer, inferred.rendererCapabilities, context)
                                    assertEquals(request.conversion, inferred.conversionCapabilities, context)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertRoutePreconditions(
        request: VideoColorPipelineRequest,
        plan: VideoColorPipelinePlan,
        context: String,
    ) {
        when (plan.route) {
            ColorPipelineRoute.SYSTEM_NATIVE_SURFACE -> {
                assertTrue(request.nativeSurfaceAvailable, context)
                assertFalse(request.isProjection, context)
                if (plan.outputDynamicRange == VideoDynamicRange.SDR && request.source.isHdr) {
                    assertTrue(request.renderer.supportsNativeToneMappingToSdr, context)
                }
                if (plan.outputDynamicRange.isHdrForTest()) {
                    assertTrue(request.display.supports(plan.outputDynamicRange), context)
                    assertTrue(request.renderer.supportsNative(plan.outputDynamicRange), context)
                    assertTrue(request.decoderSupportsSelectedPixels(plan.outputDynamicRange), context)
                }
            }
            ColorPipelineRoute.CONTROLLED_HDR_RENDERER -> {
                assertTrue(plan.outputDynamicRange.isHdrForTest(), context)
                assertTrue(request.display.supports(plan.outputDynamicRange), context)
                val rendererInputRange =
                    if (
                        request.source.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                        plan.metadataHandling == DynamicMetadataHandling.APPLIED_BY_RENDERER
                    ) {
                        VideoDynamicRange.HDR10_PLUS
                    } else {
                        plan.outputDynamicRange
                    }
                assertTrue(request.renderer.supportsControlled(rendererInputRange, request.isProjection), context)
                assertTrue(request.decoderSupportsSelectedPixels(plan.outputDynamicRange), context)
            }
            ColorPipelineRoute.CONTROLLED_SDR_RENDERER -> {
                assertEquals(VideoDynamicRange.SDR, plan.outputDynamicRange, context)
                if (request.source.isHdr) assertTrue(request.renderer.supportsToneMappingToSdr, context)
            }
            ColorPipelineRoute.SOURCE_BRIDGE_SDR -> {
                assertEquals(VideoDynamicRange.SDR, plan.outputDynamicRange, context)
                assertTrue(request.source.isHdr, context)
                assertFalse(request.renderer.supportsToneMappingToSdr, context)
                assertTrue(request.conversion.supportsHdrToSdrSourceBridge, context)
                assertTrue(request.conversion.supportsStreamingVOD, context)
                assertFalse(request.isLive, context)
                assertFalse(request.isDrmProtected, context)
            }
            ColorPipelineRoute.DOLBY_VISION_CONVERSION -> {
                assertEquals(DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1, request.dolbyVisionPolicy, context)
                assertEquals(7, request.source.dolbyVision?.profile, context)
                assertTrue(request.source.dolbyVision?.hasRpu == true, context)
                assertTrue(request.conversion.supportsDolbyVisionProfile7To8, context)
                assertTrue(request.conversion.supportsStreamingVOD, context)
                assertTrue(request.nativeSurfaceAvailable, context)
                assertFalse(request.isProjection, context)
            }
            ColorPipelineRoute.UNSUPPORTED -> assertEquals(VideoDynamicRange.UNKNOWN, plan.outputDynamicRange, context)
        }

        if (request.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR) {
            assertTrue(plan.route == ColorPipelineRoute.UNSUPPORTED || plan.outputDynamicRange.isHdrForTest(), context)
        }
        if (request.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR) {
            assertTrue(
                plan.route == ColorPipelineRoute.UNSUPPORTED || plan.outputDynamicRange == VideoDynamicRange.SDR,
                context,
            )
        }
    }

    private fun VideoColorPipelineRequest.decoderSupportsSelectedPixels(output: VideoDynamicRange): Boolean =
        when {
            source.dynamicRange == VideoDynamicRange.DOLBY_VISION && output == VideoDynamicRange.HDR10 ->
                decoder.supports(VideoDynamicRange.HDR10)
            source.dynamicRange == VideoDynamicRange.HDR10_PLUS && output == VideoDynamicRange.HDR10 ->
                decoder.supports(VideoDynamicRange.HDR10_PLUS)
            else -> decoder.supports(source.dynamicRange)
        }

    private fun platformFixtures(): List<PlatformFixture> =
        listOf(
            PlatformFixture("Android", ALL_DECODER, ANDROID_RENDERER, true, VideoSurfaceKind.SURFACE_VIEW),
            PlatformFixture("iOS", ALL_DECODER, APPLE_RENDERER, true, VideoSurfaceKind.NATIVE_LAYER),
            PlatformFixture("macOS JVM", ALL_DECODER, APPLE_RENDERER, true, VideoSurfaceKind.NATIVE_LAYER),
            PlatformFixture(
                "Windows JVM",
                HDR10_DECODER,
                WINDOWS_RENDERER,
                false,
                VideoSurfaceKind.CONTROLLED_GPU_SURFACE,
            ),
            PlatformFixture("Linux JVM", HDR10_DECODER, LINUX_RENDERER, false, VideoSurfaceKind.CONTROLLED_GPU_SURFACE),
            PlatformFixture("Web", ALL_DECODER, WEB_RENDERER, true, VideoSurfaceKind.WEB_VIDEO),
        )

    private fun sourceFixtures(): List<VideoColorInfo> =
        listOf(
            source(VideoDynamicRange.SDR, VideoColorTransfer.SDR, bitDepth = 8),
            source(VideoDynamicRange.HDR10, VideoColorTransfer.PQ, peakNits = 1_000),
            source(VideoDynamicRange.HDR10, VideoColorTransfer.PQ, peakNits = 4_000),
            source(VideoDynamicRange.HDR10_PLUS, VideoColorTransfer.PQ, peakNits = 4_000, hdr10Plus = true),
            source(VideoDynamicRange.HLG, VideoColorTransfer.HLG, peakNits = 1_000),
            dolbyVisionSource(profile = 5, enhancementLayer = DolbyVisionEnhancementLayer.NONE, hasBaseLayer = false),
            dolbyVisionSource(profile = 7, enhancementLayer = DolbyVisionEnhancementLayer.MEL, hasBaseLayer = true),
            dolbyVisionSource(profile = 7, enhancementLayer = DolbyVisionEnhancementLayer.FEL, hasBaseLayer = true),
            dolbyVisionSource(profile = 8, enhancementLayer = DolbyVisionEnhancementLayer.NONE, hasBaseLayer = true),
        )

    private fun displayFixtures(): List<DisplayColorCapabilities> =
        listOf(
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = ALL_RANGES,
                maxLuminanceNits = 1_000f,
                referenceWhiteNits = 203f,
            ),
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = setOf(VideoDynamicRange.SDR),
                maxLuminanceNits = 300f,
                referenceWhiteNits = 100f,
            ),
            DisplayColorCapabilities(),
        )

    private fun source(
        range: VideoDynamicRange,
        transfer: VideoColorTransfer,
        bitDepth: Int = 10,
        peakNits: Int? = null,
        hdr10Plus: Boolean = false,
    ): VideoColorInfo =
        VideoColorInfo(
            dynamicRange = range,
            bitDepth = bitDepth,
            primaries = if (range == VideoDynamicRange.SDR) VideoColorPrimaries.BT709 else VideoColorPrimaries.BT2020,
            transfer = transfer,
            matrix = if (range == VideoDynamicRange.SDR) VideoColorMatrix.BT709 else VideoColorMatrix.BT2020_NCL,
            range = VideoColorRange.LIMITED,
            contentLightLevel = peakNits?.let { ContentLightLevelMetadata(maxContentLightLevelNits = it) },
            hdr10Plus = if (hdr10Plus) Hdr10PlusInfo() else null,
        )

    private fun dolbyVisionSource(
        profile: Int,
        enhancementLayer: DolbyVisionEnhancementLayer,
        hasBaseLayer: Boolean,
    ): VideoColorInfo =
        source(VideoDynamicRange.DOLBY_VISION, VideoColorTransfer.PQ, peakNits = 4_000).copy(
            dolbyVision =
                DolbyVisionInfo(
                    profile = profile,
                    level = 9,
                    hasRpu = true,
                    enhancementLayer = enhancementLayer,
                    hasHdr10CompatibleBaseLayer = hasBaseLayer,
                ),
        )

    private fun VideoColorInfo.fixtureName(): String =
        if (dynamicRange == VideoDynamicRange.DOLBY_VISION) {
            "DV-P${dolbyVision?.profile}-${dolbyVision?.enhancementLayer}"
        } else {
            "$dynamicRange-${contentLightLevel?.maxContentLightLevelNits ?: "default"}"
        }

    private fun VideoDynamicRange.isHdrForTest(): Boolean =
        this != VideoDynamicRange.UNKNOWN && this != VideoDynamicRange.SDR

    private data class PlatformFixture(
        val name: String,
        val decoder: DecoderColorCapabilities,
        val renderer: RendererColorCapabilities,
        val nativeSurfaceAvailable: Boolean,
        val surfaceKind: VideoSurfaceKind,
    )

    private companion object {
        val ALL_RANGES = VideoDynamicRange.entries.toSet() - VideoDynamicRange.UNKNOWN
        val ALL_HDR = ALL_RANGES - VideoDynamicRange.SDR
        val HDR10_FAMILY = setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS, VideoDynamicRange.HLG)
        val ALL_DECODER =
            DecoderColorCapabilities(isKnown = true, supportedDynamicRanges = ALL_RANGES, maxBitDepth = 12)
        val HDR10_DECODER =
            DecoderColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = HDR10_FAMILY + VideoDynamicRange.SDR,
                maxBitDepth = 10,
            )
        val NATIVE_RENDERER =
            RendererColorCapabilities(
                nativeSurfaceDynamicRanges = ALL_HDR,
                supportsToneMappingToSdr = true,
                supportsNativeToneMappingToSdr = true,
                supportsHdr10PlusPassthrough = true,
                supportsDolbyVisionMetadata = true,
                supportsDolbyVisionToneMappingToSdr = true,
            )
        val APPLE_RENDERER =
            NATIVE_RENDERER.copy(
                controlledHdrDynamicRanges = HDR10_FAMILY,
                supportsHdrProjection = true,
            )
        val ANDROID_RENDERER =
            NATIVE_RENDERER.copy(
                controlledHdrDynamicRanges = HDR10_FAMILY,
                supportsHdrProjection = true,
                supportsHdr10PlusApplication = true,
            )
        val WINDOWS_RENDERER =
            RendererColorCapabilities(
                controlledHdrDynamicRanges = setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
                supportsToneMappingToSdr = true,
                supportsHdrProjection = true,
            )
        val LINUX_RENDERER =
            RendererColorCapabilities(
                controlledHdrDynamicRanges = setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
                supportsToneMappingToSdr = true,
                supportsHdrProjection = true,
            )
        val WEB_RENDERER =
            NATIVE_RENDERER.copy(
                controlledHdrDynamicRanges = setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HLG),
                supportsHdrProjection = true,
            )
        val FULL_CONVERSION =
            ColorConversionCapabilities(
                supportsDolbyVisionProfile7To8 = true,
                supportsHdr10PlusApplication = true,
                supportsHdrToSdrSourceBridge = true,
                supportsStreamingVOD = true,
            )
    }
}
