package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoColorPipelinePlannerTest {
    @Test
    fun `native HDR needs decoder display renderer and a native flat surface`() {
        val plan = VideoColorPipelinePlanner.plan(request(VideoDynamicRange.HDR10))

        assertEquals(ColorPipelineRoute.SYSTEM_NATIVE_SURFACE, plan.route)
        assertEquals(VideoDynamicRange.HDR10, plan.outputDynamicRange)
        assertTrue(plan.requestHonored)
    }

    @Test
    fun `projection selects controlled HDR renderer`() {
        val plan = VideoColorPipelinePlanner.plan(request(VideoDynamicRange.HLG, isProjection = true))

        assertEquals(ColorPipelineRoute.CONTROLLED_HDR_RENDERER, plan.route)
        assertEquals(VideoDynamicRange.HLG, plan.outputDynamicRange)
    }

    @Test
    fun `controlled renderer reports an explicit HLG to HDR10 output conversion`() {
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.HLG, isProjection = true).copy(
                    renderer =
                        HDR_RENDERER.copy(
                            controlledOutputConversions =
                                mapOf(VideoDynamicRange.HLG to VideoDynamicRange.HDR10),
                        ),
                ),
            )

        assertEquals(ColorPipelineRoute.CONTROLLED_HDR_RENDERER, plan.route)
        assertEquals(VideoDynamicRange.HDR10, plan.outputDynamicRange)
        assertTrue(plan.requestHonored)
    }

    @Test
    fun `AUTO uses verified SDR tone mapping when HDR output is unavailable`() {
        val request =
            request(VideoDynamicRange.HDR10).copy(
                display = SDR_DISPLAY,
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
            )
        val plan = VideoColorPipelinePlanner.plan(request)

        assertEquals(ColorPipelineRoute.CONTROLLED_SDR_RENDERER, plan.route)
        assertEquals(VideoDynamicRange.SDR, plan.outputDynamicRange)
        assertEquals(ColorPipelineFallbackReason.DISPLAY_DOES_NOT_SUPPORT_SOURCE, plan.fallbackReason)
        assertTrue(plan.requestHonored)
    }

    @Test
    fun `REQUIRE HDR never silently falls back to SDR`() {
        VideoDynamicRange.entries.forEach { range ->
            listOf(HDR_DISPLAY, SDR_DISPLAY, DisplayColorCapabilities()).forEach { display ->
                listOf(false, true).forEach { projection ->
                    val plan =
                        VideoColorPipelinePlanner.plan(
                            request(range, isProjection = projection).copy(
                                display = display,
                                dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR,
                            ),
                        )
                    assertNotEquals(
                        ColorPipelineRoute.CONTROLLED_SDR_RENDERER,
                        plan.route,
                        "range=$range display=$display projection=$projection",
                    )
                    assertNotEquals(
                        ColorPipelineRoute.SOURCE_BRIDGE_SDR,
                        plan.route,
                        "range=$range display=$display projection=$projection",
                    )
                }
            }
        }
    }

    @Test
    fun `REQUIRE HDR exposes a platform runtime verification failure`() {
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.HDR10).copy(
                    dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR,
                    platformRuntimeFallbackReason = ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
                    platformRuntimeDetail = "The active HDR composition could not be confirmed.",
                ),
            )

        assertEquals(ColorPipelineRoute.UNSUPPORTED, plan.route)
        assertEquals(ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE, plan.fallbackReason)
        assertEquals("The active HDR composition could not be confirmed.", plan.detail)
        assertFalse(plan.requestHonored)
    }

    @Test
    fun `AUTO replaces an unconfirmed native HDR route with controlled HDR`() {
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.HDR10).copy(
                    dynamicRangePolicy = DynamicRangePolicy.AUTO,
                    platformRuntimeFallbackReason = ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
                    platformRuntimeDetail = "Native dataspace readback was unavailable.",
                ),
            )

        assertEquals(ColorPipelineRoute.CONTROLLED_HDR_RENDERER, plan.route)
        assertEquals(VideoDynamicRange.HDR10, plan.outputDynamicRange)
        assertEquals(ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE, plan.fallbackReason)
        assertEquals("Native dataspace readback was unavailable.", plan.detail)
        assertTrue(plan.requestHonored)
    }

    @Test
    fun `FORCE SDR always tone maps HDR and rejects missing tone mapper`() {
        val mapped =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.HDR10_PLUS).copy(dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR),
            )
        val unavailable =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.HDR10_PLUS).copy(
                    dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                    renderer = HDR_RENDERER.copy(supportsToneMappingToSdr = false),
                ),
            )

        assertEquals(ColorPipelineRoute.CONTROLLED_SDR_RENDERER, mapped.route)
        assertEquals(ColorPipelineRoute.UNSUPPORTED, unavailable.route)
        assertEquals(ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE, unavailable.fallbackReason)
    }

    @Test
    fun `AUTO uses optional source bridge when renderer cannot tone map HLG`() {
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.HLG).copy(
                    display = SDR_DISPLAY,
                    renderer = HDR_RENDERER.copy(supportsToneMappingToSdr = false),
                    conversion = HDR_TO_SDR_SOURCE_BRIDGE,
                ),
            )

        assertEquals(ColorPipelineRoute.SOURCE_BRIDGE_SDR, plan.route)
        assertEquals(VideoDynamicRange.SDR, plan.outputDynamicRange)
        assertEquals(DynamicMetadataHandling.APPLIED_BY_SOURCE_BRIDGE, plan.metadataHandling)
        assertEquals(ColorPipelineFallbackReason.DISPLAY_DOES_NOT_SUPPORT_SOURCE, plan.fallbackReason)
        assertTrue(plan.requestHonored)
    }

    @Test
    fun `source bridge reports prepared BT709 but waits for output verification`() {
        val request =
            request(VideoDynamicRange.HLG).copy(
                decoderInput =
                    VideoColorInfo(
                        dynamicRange = VideoDynamicRange.SDR,
                        bitDepth = 8,
                        primaries = VideoColorPrimaries.BT709,
                        transfer = VideoColorTransfer.SDR,
                        matrix = VideoColorMatrix.BT709,
                    ),
                display = SDR_DISPLAY,
                renderer = HDR_RENDERER.copy(supportsToneMappingToSdr = false),
                conversion = HDR_TO_SDR_SOURCE_BRIDGE,
            )
        val plan = VideoColorPipelinePlanner.plan(request)

        val pending = plan.toStatus(request, decoderName = "c2.android.avc.decoder")
        val configured =
            plan.toStatus(
                request,
                decoderName = "c2.android.avc.decoder",
                verification = ColorPipelineVerification.RENDERER_CONFIGURED,
            )

        assertEquals(ColorPipelineRoute.SOURCE_BRIDGE_SDR, plan.route)
        assertTrue(plan.detail.orEmpty().contains("explicitly tagged BT.709 SDR"))
        assertEquals(VideoDynamicRange.UNKNOWN, pending.outputDynamicRange)
        assertEquals(DynamicMetadataHandling.NONE, pending.metadataHandling)
        assertEquals(VideoDynamicRange.SDR, configured.outputDynamicRange)
        assertEquals(DynamicMetadataHandling.APPLIED_BY_SOURCE_BRIDGE, configured.metadataHandling)
        assertEquals(ColorPipelineRenderer.SOURCE_BRIDGE_SDR, configured.renderer)
    }

    @Test
    fun `source bridge is forbidden for REQUIRE HDR live and DRM`() {
        val base =
            request(VideoDynamicRange.HLG).copy(
                display = SDR_DISPLAY,
                renderer = HDR_RENDERER.copy(supportsToneMappingToSdr = false),
                conversion = HDR_TO_SDR_SOURCE_BRIDGE,
            )
        val requireHdr = VideoColorPipelinePlanner.plan(base.copy(dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR))
        val live = VideoColorPipelinePlanner.plan(base.copy(isLive = true))
        val drm = VideoColorPipelinePlanner.plan(base.copy(isDrmProtected = true))

        assertEquals(ColorPipelineRoute.UNSUPPORTED, requireHdr.route)
        assertEquals(ColorPipelineRoute.UNSUPPORTED, live.route)
        assertEquals(ColorPipelineRoute.UNSUPPORTED, drm.route)
        assertEquals(ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE, live.fallbackReason)
        assertEquals(ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE, drm.fallbackReason)
    }

    @Test
    fun `DV profile 7 conversion requires explicit policy module VOD and downstream DV route`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                transfer = VideoColorTransfer.PQ,
                dolbyVision =
                    DolbyVisionInfo(
                        profile = 7,
                        hasRpu = true,
                        enhancementLayer = DolbyVisionEnhancementLayer.FEL,
                    ),
            )
        val explicit =
            request(VideoDynamicRange.DOLBY_VISION).copy(
                source = source,
                dolbyVisionPolicy = DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1,
                conversion = DV_CONVERSION,
            )

        assertEquals(ColorPipelineRoute.DOLBY_VISION_CONVERSION, VideoColorPipelinePlanner.plan(explicit).route)
        assertEquals(
            ColorPipelineFallbackReason.LIVE_SOURCE_CONVERSION_UNSUPPORTED,
            VideoColorPipelinePlanner.plan(explicit.copy(isLive = true)).fallbackReason,
        )
        assertEquals(
            ColorPipelineFallbackReason.DRM_CONVERSION_UNSUPPORTED,
            VideoColorPipelinePlanner.plan(explicit.copy(isDrmProtected = true)).fallbackReason,
        )
        assertEquals(
            ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE,
            VideoColorPipelinePlanner.plan(explicit.copy(conversion = ColorConversionCapabilities())).fallbackReason,
        )
    }

    @Test
    fun `AUTO keeps native profile 7 when the decoder confirms profile 7 support`() {
        val source = profile7Source(DolbyVisionEnhancementLayer.MEL)
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    decoder =
                        HDR_DECODER.copy(
                            supportedDolbyVisionProfiles = setOf(7, 8),
                            isDolbyVisionProfileSupportKnown = true,
                        ),
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    conversion = DV_CONVERSION,
                ),
            )

        assertEquals(ColorPipelineRoute.SYSTEM_NATIVE_SURFACE, plan.route)
        assertEquals(7, plan.outputDolbyVision?.profile)
        assertNull(plan.dolbyVisionProfileMapping)
    }

    @Test
    fun `AUTO converts profile 7 to 8_1 when native P7 is unavailable but P8 is supported`() {
        val source = profile7Source(DolbyVisionEnhancementLayer.FEL)
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    decoder = P8_ONLY_DV_DECODER,
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    conversion = DV_CONVERSION,
                ),
            )

        assertEquals(ColorPipelineRoute.DOLBY_VISION_CONVERSION, plan.route)
        assertEquals(8, plan.outputDolbyVision?.profile)
        assertTrue(plan.outputDolbyVision?.hasHdr10CompatibleBaseLayer == true)
        assertEquals(7, plan.dolbyVisionProfileMapping?.sourceProfile)
        assertEquals(8, plan.dolbyVisionProfileMapping?.outputProfile)
        assertEquals(true, plan.dolbyVisionProfileMapping?.enhancementLayerDiscarded)
        assertEquals(true, plan.dolbyVisionProfileMapping?.felMappingDiscarded)
    }

    @Test
    fun `profile mapping status separates planned P8_1 from confirmed active P8_1`() {
        val request =
            request(VideoDynamicRange.DOLBY_VISION).copy(
                source = profile7Source(DolbyVisionEnhancementLayer.MEL),
                decoder = P8_ONLY_DV_DECODER,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                conversion = DV_CONVERSION,
            )
        val plan = VideoColorPipelinePlanner.plan(request)

        val pending = plan.toStatus(request, verification = ColorPipelineVerification.NONE)
        val confirmed = plan.toStatus(request, verification = ColorPipelineVerification.SYSTEM_REPORTED)

        assertEquals(8, pending.plannedOutputDolbyVision?.profile)
        assertTrue(pending.plannedOutputDolbyVision?.hasHdr10CompatibleBaseLayer == true)
        assertEquals(false, pending.plannedDolbyVisionProfileMapping?.felMappingDiscarded)
        assertNull(pending.outputDolbyVision)
        assertNull(pending.dolbyVisionProfileMapping)
        assertEquals(8, confirmed.outputDolbyVision?.profile)
        assertEquals(true, confirmed.dolbyVisionProfileMapping?.enhancementLayerDiscarded)
        assertEquals(false, confirmed.dolbyVisionProfileMapping?.felMappingDiscarded)
    }

    @Test
    fun `installed P8_1 bridge remains visible instead of being relabelled native P7`() {
        val source = profile7Source(DolbyVisionEnhancementLayer.FEL)
        val output =
            source.copy(
                dolbyVision =
                    source.dolbyVision?.copy(
                        profile = 8,
                        hasRpu = true,
                        enhancementLayer = DolbyVisionEnhancementLayer.NONE,
                        hasHdr10CompatibleBaseLayer = true,
                    ),
            )
        val mapping = requireNotNull(source.profile7To81MappingOrNull(output))
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    decoderInput = output,
                    appliedDolbyVisionProfileMapping = mapping,
                    decoder = P8_ONLY_DV_DECODER,
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    conversion = DV_CONVERSION,
                ),
            )

        assertEquals(ColorPipelineRoute.DOLBY_VISION_CONVERSION, plan.route)
        assertEquals(mapping, plan.dolbyVisionProfileMapping)
        assertEquals(8, plan.outputDolbyVision?.profile)

        val pending =
            plan.toStatus(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    decoderInput = output,
                    appliedDolbyVisionProfileMapping = mapping,
                    decoder = P8_ONLY_DV_DECODER,
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    conversion = DV_CONVERSION,
                ),
                verification = ColorPipelineVerification.NONE,
            )
        assertEquals(mapping, pending.dolbyVisionProfileMapping)
        assertNull(pending.outputDolbyVision)
        assertEquals(VideoDynamicRange.UNKNOWN, pending.outputDynamicRange)
    }

    @Test
    fun `unknown RPU signalling may be probed by the conversion bridge`() {
        val source =
            profile7Source(DolbyVisionEnhancementLayer.UNKNOWN).copy(
                dolbyVision = profile7Source(DolbyVisionEnhancementLayer.UNKNOWN).dolbyVision?.copy(hasRpu = null),
            )
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    decoder = P8_ONLY_DV_DECODER,
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    conversion = DV_CONVERSION,
                ),
            )

        assertEquals(ColorPipelineRoute.DOLBY_VISION_CONVERSION, plan.route)
        assertNull(plan.dolbyVisionProfileMapping?.enhancementLayerDiscarded)
        assertNull(plan.dolbyVisionProfileMapping?.felMappingDiscarded)
    }

    @Test
    fun `AUTO uses safe HDR10 base layer rather than implicit DV conversion`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                transfer = VideoColorTransfer.PQ,
                dolbyVision =
                    DolbyVisionInfo(
                        profile = 7,
                        hasRpu = true,
                        hasHdr10CompatibleBaseLayer = true,
                    ),
            )
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    display = HDR10_ONLY_DISPLAY,
                    dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                    conversion = DV_CONVERSION,
                ),
            )

        assertEquals(VideoDynamicRange.HDR10, plan.outputDynamicRange)
        assertEquals(ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_USED, plan.fallbackReason)
        assertEquals(DynamicMetadataHandling.DROPPED, plan.metadataHandling)
    }

    @Test
    fun `HDR10 plus is only reported when dynamic metadata handling is confirmed`() {
        val request =
            request(VideoDynamicRange.HDR10_PLUS).copy(
                renderer =
                    HDR_RENDERER.copy(
                        supportsHdr10PlusPassthrough = false,
                        supportsHdr10PlusApplication = false,
                    ),
            )
        val plan = VideoColorPipelinePlanner.plan(request)

        assertEquals(ColorPipelineRoute.SYSTEM_NATIVE_SURFACE, plan.route)
        assertEquals(VideoDynamicRange.HDR10, plan.outputDynamicRange)
        assertEquals(DynamicMetadataHandling.DROPPED, plan.metadataHandling)
        assertEquals(ColorPipelineFallbackReason.DYNAMIC_METADATA_UNSUPPORTED, plan.fallbackReason)
        assertFalse(plan.requestHonored)
    }

    @Test
    fun `HDR10 plus controlled renderer requires application capability rather than passthrough`() {
        val base =
            request(VideoDynamicRange.HDR10_PLUS).copy(
                isProjection = true,
                nativeSurfaceAvailable = false,
                surfaceKind = VideoSurfaceKind.CONTROLLED_GPU_SURFACE,
            )
        val applied =
            VideoColorPipelinePlanner.plan(
                base.copy(
                    renderer =
                        HDR_RENDERER.copy(
                            supportsHdr10PlusPassthrough = false,
                            supportsHdr10PlusApplication = true,
                        ),
                ),
            )
        val staticFallback =
            VideoColorPipelinePlanner.plan(
                base.copy(
                    renderer =
                        HDR_RENDERER.copy(
                            supportsHdr10PlusPassthrough = true,
                            supportsHdr10PlusApplication = false,
                        ),
                ),
            )

        assertEquals(ColorPipelineRoute.CONTROLLED_HDR_RENDERER, applied.route)
        assertEquals(VideoDynamicRange.HDR10, applied.outputDynamicRange)
        assertEquals(DynamicMetadataHandling.APPLIED_BY_RENDERER, applied.metadataHandling)
        assertEquals(VideoDynamicRange.HDR10, staticFallback.outputDynamicRange)
        assertEquals(DynamicMetadataHandling.DROPPED, staticFallback.metadataHandling)
    }

    @Test
    fun `status keeps planned dynamic metadata separate from confirmed handling`() {
        val request =
            request(VideoDynamicRange.HDR10_PLUS).copy(
                isProjection = true,
                nativeSurfaceAvailable = false,
                surfaceKind = VideoSurfaceKind.CONTROLLED_GPU_SURFACE,
                renderer =
                    HDR_RENDERER.copy(
                        supportsHdr10PlusPassthrough = false,
                        supportsHdr10PlusApplication = true,
                    ),
            )
        val plan = VideoColorPipelinePlanner.plan(request)

        val pending = plan.toStatus(request, verification = ColorPipelineVerification.NONE)
        val configured = plan.toStatus(request, verification = ColorPipelineVerification.RENDERER_CONFIGURED)

        assertEquals(DynamicMetadataHandling.APPLIED_BY_RENDERER, pending.plannedMetadataHandling)
        assertEquals(DynamicMetadataHandling.NONE, pending.metadataHandling)
        assertEquals(DynamicMetadataHandling.APPLIED_BY_RENDERER, configured.plannedMetadataHandling)
        assertEquals(DynamicMetadataHandling.APPLIED_BY_RENDERER, configured.metadataHandling)
    }

    @Test
    fun `Dolby Vision SDR conversion needs a compatible base layer or verified DV tone mapper`() {
        val profile5 =
            request(VideoDynamicRange.DOLBY_VISION).copy(
                source =
                    VideoColorInfo(
                        dynamicRange = VideoDynamicRange.DOLBY_VISION,
                        transfer = VideoColorTransfer.PQ,
                        dolbyVision = DolbyVisionInfo(profile = 5, hasRpu = true),
                    ),
                dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                renderer =
                    HDR_RENDERER.copy(
                        supportsDolbyVisionMetadata = false,
                        supportsDolbyVisionToneMappingToSdr = false,
                    ),
            )

        val plan = VideoColorPipelinePlanner.plan(profile5)

        assertEquals(ColorPipelineRoute.UNSUPPORTED, plan.route)
        assertEquals(ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED, plan.fallbackReason)
    }

    @Test
    fun `prefer HDR10 base layer wins over an available native Dolby Vision route`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                transfer = VideoColorTransfer.PQ,
                dolbyVision =
                    DolbyVisionInfo(
                        profile = 8,
                        hasRpu = true,
                        hasHdr10CompatibleBaseLayer = true,
                    ),
            )
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    dolbyVisionPolicy = DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER,
                ),
            )

        assertEquals(VideoDynamicRange.HDR10, plan.outputDynamicRange)
        assertEquals(ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_USED, plan.fallbackReason)
        assertTrue(plan.requestHonored)
    }

    @Test
    fun `prefer HDR10 base layer falls back to native Dolby Vision when no base layer exists`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                transfer = VideoColorTransfer.PQ,
                dolbyVision = DolbyVisionInfo(profile = 5, hasRpu = true),
            )

        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    dolbyVisionPolicy = DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER,
                ),
            )

        assertEquals(ColorPipelineRoute.SYSTEM_NATIVE_SURFACE, plan.route)
        assertEquals(VideoDynamicRange.DOLBY_VISION, plan.outputDynamicRange)
        assertEquals(ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_UNAVAILABLE, plan.fallbackReason)
        assertFalse(plan.requestHonored)
    }

    @Test
    fun `profile 7 conversion requires timestamped RPU metadata`() {
        val source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                transfer = VideoColorTransfer.PQ,
                dolbyVision =
                    DolbyVisionInfo(
                        profile = 7,
                        hasRpu = false,
                        hasHdr10CompatibleBaseLayer = true,
                    ),
            )

        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.DOLBY_VISION).copy(
                    source = source,
                    dolbyVisionPolicy = DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1,
                    conversion = DV_CONVERSION,
                ),
            )

        assertEquals(ColorPipelineRoute.CONTROLLED_SDR_RENDERER, plan.route)
        assertEquals(ColorPipelineFallbackReason.DOLBY_VISION_RPU_UNAVAILABLE, plan.fallbackReason)
    }

    @Test
    fun `system supplied SDR tone mapping is a valid AUTO fallback but not REQUIRE HDR`() {
        val request =
            request(VideoDynamicRange.HDR10).copy(
                display = SDR_DISPLAY,
                renderer = HDR_RENDERER.copy(supportsNativeToneMappingToSdr = true),
            )

        val automatic = VideoColorPipelinePlanner.plan(request)
        val strict =
            VideoColorPipelinePlanner.plan(
                request.copy(dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR),
            )

        assertEquals(ColorPipelineRoute.SYSTEM_NATIVE_SURFACE, automatic.route)
        assertEquals(VideoDynamicRange.SDR, automatic.outputDynamicRange)
        assertEquals(ColorPipelineFallbackReason.DISPLAY_DOES_NOT_SUPPORT_SOURCE, automatic.fallbackReason)
        assertEquals(ColorPipelineRoute.UNSUPPORTED, strict.route)
    }

    @Test
    fun `unknown source never becomes claimed SDR even under FORCE SDR`() {
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.UNKNOWN).copy(dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR),
            )

        assertEquals(ColorPipelineRoute.UNSUPPORTED, plan.route)
        assertEquals(ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN, plan.fallbackReason)
    }

    @Test
    fun `known incompatible decoder cannot be hidden by SDR fallback`() {
        val plan =
            VideoColorPipelinePlanner.plan(
                request(VideoDynamicRange.HDR10).copy(
                    dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                    decoder =
                        DecoderColorCapabilities(
                            isKnown = true,
                            supportedDynamicRanges = setOf(VideoDynamicRange.SDR),
                        ),
                ),
            )

        assertEquals(ColorPipelineRoute.UNSUPPORTED, plan.route)
        assertEquals(ColorPipelineFallbackReason.DECODER_DOES_NOT_SUPPORT_SOURCE, plan.fallbackReason)
    }

    @Test
    fun `status does not call inferred route active HDR`() {
        val request = request(VideoDynamicRange.HDR10)
        val plan = VideoColorPipelinePlanner.plan(request)

        val inferred =
            plan.toStatus(
                request,
                decoderName = "decoder",
                verification = ColorPipelineVerification.INFERRED,
            )
        val configured =
            plan.toStatus(
                request,
                decoderName = "decoder",
                verification = ColorPipelineVerification.RENDERER_CONFIGURED,
            )

        assertEquals(VideoDynamicRange.HDR10, inferred.plannedOutputDynamicRange)
        assertEquals(VideoDynamicRange.UNKNOWN, inferred.outputDynamicRange)
        assertFalse(inferred.requestHonored)
        assertEquals(VideoDynamicRange.HDR10, configured.outputDynamicRange)
        assertTrue(configured.requestHonored)
        assertEquals(request.decoder, configured.decoderCapabilities)
        assertEquals(request.renderer, configured.rendererCapabilities)
        assertEquals(request.conversion, configured.conversionCapabilities)
    }

    @Test
    fun `status does not call planned SDR an active output before a surface is configured`() {
        val request =
            request(VideoDynamicRange.HDR10).copy(
                dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                nativeSurfaceAvailable = false,
                surfaceKind = VideoSurfaceKind.UNKNOWN,
            )
        val plan = VideoColorPipelinePlanner.plan(request)

        val pending = plan.toStatus(request, verification = ColorPipelineVerification.NONE)
        val configured = plan.toStatus(request, verification = ColorPipelineVerification.RENDERER_CONFIGURED)

        assertEquals(VideoDynamicRange.SDR, pending.plannedOutputDynamicRange)
        assertEquals(VideoDynamicRange.UNKNOWN, pending.outputDynamicRange)
        assertFalse(pending.requestHonored)
        assertEquals(VideoDynamicRange.SDR, configured.outputDynamicRange)
        assertTrue(configured.requestHonored)
    }

    @Test
    fun `planner matrix never reports HDR from decoder recognition alone`() {
        val sourceRanges =
            listOf(
                VideoDynamicRange.SDR,
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HDR10_PLUS,
                VideoDynamicRange.HLG,
                VideoDynamicRange.DOLBY_VISION,
            )
        sourceRanges.forEach { sourceRange ->
            DynamicRangePolicy.entries.forEach { policy ->
                listOf(false, true).forEach { projection ->
                    listOf(false, true).forEach { displayHdr ->
                        val request =
                            request(sourceRange, isProjection = projection).copy(
                                display = if (displayHdr) HDR_DISPLAY else SDR_DISPLAY,
                                dynamicRangePolicy = policy,
                            )
                        val plan = VideoColorPipelinePlanner.plan(request)
                        val status = plan.toStatus(request, verification = ColorPipelineVerification.INFERRED)
                        assertFalse(
                            status.outputDynamicRange.isHdr,
                            "range=$sourceRange policy=$policy projection=$projection displayHdr=$displayHdr",
                        )
                    }
                }
            }
        }
    }

    private fun request(
        range: VideoDynamicRange,
        isProjection: Boolean = false,
    ): VideoColorPipelineRequest =
        VideoColorPipelineRequest(
            source =
                VideoColorInfo(
                    dynamicRange = range,
                    bitDepth = if (range == VideoDynamicRange.SDR) 8 else 10,
                    primaries =
                        if (range == VideoDynamicRange.SDR) {
                            VideoColorPrimaries.BT709
                        } else {
                            VideoColorPrimaries.BT2020
                        },
                    transfer =
                        when (range) {
                            VideoDynamicRange.SDR -> VideoColorTransfer.SDR
                            VideoDynamicRange.HLG -> VideoColorTransfer.HLG
                            else -> VideoColorTransfer.PQ
                        },
                    dolbyVision =
                        if (range == VideoDynamicRange.DOLBY_VISION) {
                            DolbyVisionInfo(profile = 8, hasRpu = true)
                        } else {
                            null
                        },
                ),
            display = HDR_DISPLAY,
            decoder = HDR_DECODER,
            renderer = HDR_RENDERER,
            nativeSurfaceAvailable = true,
            surfaceKind = VideoSurfaceKind.SURFACE_VIEW,
            isProjection = isProjection,
        )

    private fun profile7Source(enhancementLayer: DolbyVisionEnhancementLayer): VideoColorInfo =
        VideoColorInfo(
            dynamicRange = VideoDynamicRange.DOLBY_VISION,
            bitDepth = 10,
            primaries = VideoColorPrimaries.BT2020,
            transfer = VideoColorTransfer.PQ,
            matrix = VideoColorMatrix.BT2020_NCL,
            range = VideoColorRange.LIMITED,
            dolbyVision =
                DolbyVisionInfo(
                    profile = 7,
                    hasRpu = true,
                    enhancementLayer = enhancementLayer,
                    hasHdr10CompatibleBaseLayer = true,
                ),
        )

    private val VideoDynamicRange.isHdr: Boolean
        get() = this != VideoDynamicRange.UNKNOWN && this != VideoDynamicRange.SDR

    private companion object {
        val ALL_HDR =
            setOf(
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HDR10_PLUS,
                VideoDynamicRange.HLG,
                VideoDynamicRange.DOLBY_VISION,
            )
        val HDR_DISPLAY =
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = ALL_HDR + VideoDynamicRange.SDR,
                maxLuminanceNits = 1_000f,
                referenceWhiteNits = 203f,
            )
        val HDR10_ONLY_DISPLAY =
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = setOf(VideoDynamicRange.SDR, VideoDynamicRange.HDR10),
            )
        val SDR_DISPLAY =
            DisplayColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = setOf(VideoDynamicRange.SDR),
                maxLuminanceNits = 300f,
                referenceWhiteNits = 100f,
            )
        val HDR_DECODER =
            DecoderColorCapabilities(
                isKnown = true,
                supportedDynamicRanges = ALL_HDR + VideoDynamicRange.SDR,
                maxBitDepth = 10,
            )
        val HDR_RENDERER =
            RendererColorCapabilities(
                nativeSurfaceDynamicRanges = ALL_HDR,
                controlledHdrDynamicRanges = ALL_HDR,
                supportsToneMappingToSdr = true,
                supportsHdrProjection = true,
                supportsHdr10PlusPassthrough = true,
                supportsHdr10PlusApplication = true,
                supportsDolbyVisionMetadata = true,
                supportsDolbyVisionToneMappingToSdr = true,
            )
        val DV_CONVERSION =
            ColorConversionCapabilities(
                supportsDolbyVisionProfile7To8 = true,
                supportsStreamingVOD = true,
            )
        val HDR_TO_SDR_SOURCE_BRIDGE =
            ColorConversionCapabilities(
                supportsHdrToSdrSourceBridge = true,
                supportsStreamingVOD = true,
            )
        val P8_ONLY_DV_DECODER =
            HDR_DECODER.copy(
                supportedDolbyVisionProfiles = setOf(8),
                isDolbyVisionProfileSupportKnown = true,
            )
    }
}
