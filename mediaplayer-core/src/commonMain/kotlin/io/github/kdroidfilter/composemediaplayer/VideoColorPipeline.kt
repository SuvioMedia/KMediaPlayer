package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Immutable

/** Dynamic-range signal carried by a source or by the active display output. */
enum class VideoDynamicRange {
    UNKNOWN,
    SDR,
    HDR10,
    HDR10_PLUS,
    HLG,
    DOLBY_VISION,
}

/** Preflight support reported for a dynamic-range signal on the active display. */
enum class VideoDynamicRangeSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class VideoColorPrimaries { UNKNOWN, BT601_525, BT601_625, BT709, BT2020, DISPLAY_P3 }

enum class VideoColorTransfer { UNKNOWN, SDR, SRGB, LINEAR, PQ, HLG }

enum class VideoColorMatrix { UNKNOWN, RGB, BT601, BT709, BT2020_NCL, BT2020_CL, ICTCP }

enum class VideoColorRange { UNKNOWN, LIMITED, FULL }

enum class DolbyVisionEnhancementLayer { NONE, MEL, FEL, UNKNOWN }

@Immutable
data class MasteringDisplayMetadata(
    val redX: Float,
    val redY: Float,
    val greenX: Float,
    val greenY: Float,
    val blueX: Float,
    val blueY: Float,
    val whiteX: Float,
    val whiteY: Float,
    val minLuminanceNits: Float,
    val maxLuminanceNits: Float,
)

@Immutable
data class ContentLightLevelMetadata(
    val maxContentLightLevelNits: Int? = null,
    val maxFrameAverageLightLevelNits: Int? = null,
)

@Immutable
data class Hdr10PlusInfo(
    val applicationIdentifier: Int = 4,
    val applicationVersion: Int? = null,
    val hasPerFrameMetadata: Boolean = true,
)

@Immutable
data class DolbyVisionInfo(
    val profile: Int? = null,
    val level: Int? = null,
    /** `null` when codec/container signalling does not expose whether an RPU is present. */
    val hasRpu: Boolean? = null,
    val enhancementLayer: DolbyVisionEnhancementLayer = DolbyVisionEnhancementLayer.UNKNOWN,
    val hasHdr10CompatibleBaseLayer: Boolean = false,
)

/**
 * A compressed-stream Dolby Vision profile rewrite performed before the platform decoder.
 *
 * Profile 8.1 is represented by profile 8 plus an HDR10-compatible base layer. Nullable loss
 * flags keep the status honest when the container probe cannot distinguish MEL from FEL.
 */
@Immutable
data class DolbyVisionProfileMapping(
    val sourceProfile: Int?,
    val outputProfile: Int?,
    val outputHasHdr10CompatibleBaseLayer: Boolean,
    val enhancementLayerDiscarded: Boolean? = null,
    val felMappingDiscarded: Boolean? = null,
)

/** Color description obtained from the selected compressed video track. */
@Immutable
data class VideoColorInfo(
    val dynamicRange: VideoDynamicRange = VideoDynamicRange.UNKNOWN,
    val bitDepth: Int? = null,
    val primaries: VideoColorPrimaries = VideoColorPrimaries.UNKNOWN,
    val transfer: VideoColorTransfer = VideoColorTransfer.UNKNOWN,
    val matrix: VideoColorMatrix = VideoColorMatrix.UNKNOWN,
    val range: VideoColorRange = VideoColorRange.UNKNOWN,
    val masteringDisplay: MasteringDisplayMetadata? = null,
    val contentLightLevel: ContentLightLevelMetadata? = null,
    val hdr10Plus: Hdr10PlusInfo? = null,
    val dolbyVision: DolbyVisionInfo? = null,
) {
    val isHdr: Boolean
        get() = dynamicRange != VideoDynamicRange.UNKNOWN && dynamicRange != VideoDynamicRange.SDR
}

/** Capabilities reported for the display currently presenting the player window. */
@Immutable
data class DisplayColorCapabilities(
    val isKnown: Boolean = false,
    val supportedDynamicRanges: Set<VideoDynamicRange> = emptySet(),
    val minLuminanceNits: Float? = null,
    val maxLuminanceNits: Float? = null,
    val referenceWhiteNits: Float? = null,
) {
    init {
        requireValidLuminance("minLuminanceNits", minLuminanceNits, allowZero = true)
        requireValidLuminance("maxLuminanceNits", maxLuminanceNits)
        requireValidLuminance("referenceWhiteNits", referenceWhiteNits)
        if (minLuminanceNits != null && maxLuminanceNits != null) {
            require(minLuminanceNits <= maxLuminanceNits) {
                "minLuminanceNits must not exceed maxLuminanceNits."
            }
        }
    }

    val supportsHdr: Boolean
        get() = supportedDynamicRanges.any { it != VideoDynamicRange.UNKNOWN && it != VideoDynamicRange.SDR }

    fun supports(dynamicRange: VideoDynamicRange): Boolean =
        dynamicRange == VideoDynamicRange.SDR || dynamicRange in supportedDynamicRanges

    /**
     * Returns a tri-state result suitable for filtering sources before playback starts.
     *
     * An absent range is only [VideoDynamicRangeSupport.UNSUPPORTED] when the platform produced a
     * known display capability set. Callers that require strict compatibility can show a source
     * only for [VideoDynamicRangeSupport.SUPPORTED].
     */
    fun supportFor(dynamicRange: VideoDynamicRange): VideoDynamicRangeSupport =
        when {
            dynamicRange == VideoDynamicRange.UNKNOWN -> VideoDynamicRangeSupport.UNKNOWN
            supports(dynamicRange) -> VideoDynamicRangeSupport.SUPPORTED
            isKnown -> VideoDynamicRangeSupport.UNSUPPORTED
            else -> VideoDynamicRangeSupport.UNKNOWN
        }

    private fun requireValidLuminance(
        name: String,
        value: Float?,
        allowZero: Boolean = false,
    ) {
        if (value == null) return
        require(value.isFinite()) { "$name must be finite." }
        require(if (allowZero) value >= 0f else value > 0f) { "$name must be positive." }
    }
}

/** Decoder capabilities are deliberately separate from display and renderer capabilities. */
@Immutable
data class DecoderColorCapabilities(
    val isKnown: Boolean = false,
    val supportedDynamicRanges: Set<VideoDynamicRange> = emptySet(),
    val maxBitDepth: Int? = null,
    /** Empty when the decoder API reports Dolby Vision support without profile granularity. */
    val supportedDolbyVisionProfiles: Set<Int> = emptySet(),
    val isDolbyVisionProfileSupportKnown: Boolean = false,
) {
    fun supports(dynamicRange: VideoDynamicRange): Boolean = dynamicRange in supportedDynamicRanges

    fun supportsDolbyVisionProfile(profile: Int?): Boolean {
        if (!supports(VideoDynamicRange.DOLBY_VISION)) return false
        if (profile == null || !isDolbyVisionProfileSupportKnown) return true
        return profile in supportedDolbyVisionProfiles
    }

    fun supports(colorInfo: VideoColorInfo): Boolean =
        if (colorInfo.dynamicRange == VideoDynamicRange.DOLBY_VISION) {
            supportsDolbyVisionProfile(colorInfo.dolbyVision?.profile)
        } else {
            supports(colorInfo.dynamicRange)
        }
}

/** Color paths implemented by the renderer, independent of the decoder and display. */
@Immutable
data class RendererColorCapabilities(
    val nativeSurfaceDynamicRanges: Set<VideoDynamicRange> = emptySet(),
    val controlledHdrDynamicRanges: Set<VideoDynamicRange> = emptySet(),
    /**
     * Color-correct output transfer conversions performed by the controlled renderer.
     *
     * For example, an Android renderer may decode HLG into linear BT.2020 and present it through
     * a PQ surface. Keeping that mapping explicit prevents the status from claiming an HLG output
     * when the compositor is actually receiving HDR10/PQ.
     */
    val controlledOutputConversions: Map<VideoDynamicRange, VideoDynamicRange> = emptyMap(),
    val supportsToneMappingToSdr: Boolean = false,
    val supportsNativeToneMappingToSdr: Boolean = false,
    val supportsHdrProjection: Boolean = false,
    val supportsHdr10PlusPassthrough: Boolean = false,
    val supportsHdr10PlusApplication: Boolean = false,
    val supportsDolbyVisionMetadata: Boolean = false,
    val supportsDolbyVisionToneMappingToSdr: Boolean = false,
) {
    fun supportsNative(dynamicRange: VideoDynamicRange): Boolean = dynamicRange in nativeSurfaceDynamicRanges

    fun supportsControlled(
        dynamicRange: VideoDynamicRange,
        isProjection: Boolean,
    ): Boolean = dynamicRange in controlledHdrDynamicRanges && (!isProjection || supportsHdrProjection)

    fun controlledOutputFor(dynamicRange: VideoDynamicRange): VideoDynamicRange =
        controlledOutputConversions[dynamicRange] ?: dynamicRange
}

@Immutable
data class ColorConversionCapabilities(
    val supportsDolbyVisionProfile7To8: Boolean = false,
    val supportsHdr10PlusApplication: Boolean = false,
    /** A bounded source bridge can decode HDR and emit explicitly tagged BT.709 SDR. */
    val supportsHdrToSdrSourceBridge: Boolean = false,
    /**
     * A bounded VOD bridge can emit sequential fragments that the player can consume while playing.
     *
     * This is a functional transport capability, not a resolution, frame-rate, latency, or thermal
     * performance qualification. Those guarantees require separate hardware evidence.
     */
    val supportsStreamingVOD: Boolean = false,
)

enum class VideoSurfaceKind {
    UNKNOWN,
    COMPOSE_CANVAS,
    TEXTURE_VIEW,
    SURFACE_VIEW,
    NATIVE_LAYER,
    NATIVE_CHILD_WINDOW,
    CONTROLLED_GPU_SURFACE,
    WEB_VIDEO,
    WEB_GL_CANVAS,
    WEB_GPU_CANVAS,
}

enum class ColorPipelineRenderer {
    UNKNOWN,
    SYSTEM_NATIVE,
    CONTROLLED_HDR,
    CONTROLLED_SDR,
    SOURCE_BRIDGE_SDR,
    DOLBY_VISION_CONVERTER,
}

enum class DynamicMetadataHandling {
    NONE,
    PASSTHROUGH,
    APPLIED_BY_RENDERER,

    /** Dynamic-range metadata was consumed while a source bridge produced new output pixels. */
    APPLIED_BY_SOURCE_BRIDGE,
    CONVERTED,
    DROPPED,
}

enum class ColorPipelineVerification {
    NONE,
    INFERRED,
    RENDERER_CONFIGURED,
    SYSTEM_REPORTED,
}

enum class ColorPipelineFallbackReason {
    NONE,
    SOURCE_COLOR_UNKNOWN,
    DISPLAY_CAPABILITIES_UNKNOWN,
    DISPLAY_DOES_NOT_SUPPORT_SOURCE,
    DECODER_DOES_NOT_SUPPORT_SOURCE,
    HDR_SURFACE_UNAVAILABLE,
    HDR_PROJECTION_UNAVAILABLE,
    TONE_MAPPER_UNAVAILABLE,
    DOLBY_VISION_CONVERTER_UNAVAILABLE,
    DOLBY_VISION_PROFILE_UNSUPPORTED,
    DOLBY_VISION_RPU_UNAVAILABLE,
    DOLBY_VISION_BASE_LAYER_USED,
    DOLBY_VISION_BASE_LAYER_UNAVAILABLE,
    DYNAMIC_METADATA_UNSUPPORTED,
    RENDERER_CONFIGURATION_FAILED,
    SOURCE_IS_SDR,
    LIVE_SOURCE_CONVERSION_UNSUPPORTED,
    DRM_CONVERSION_UNSUPPORTED,
    PLATFORM_RUNTIME_UNAVAILABLE,
    EXPLICIT_SDR_REQUEST,
}

/** Live, typed description of the color pipeline that is actually selected. */
@Immutable
data class VideoColorPipelineStatus(
    val requestedDynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.AUTO,
    val requestedDolbyVisionPolicy: DolbyVisionPolicy = DolbyVisionPolicy.AUTO,
    val source: VideoColorInfo = VideoColorInfo(),
    val display: DisplayColorCapabilities = DisplayColorCapabilities(),
    val decoderName: String? = null,
    val decoderCapabilities: DecoderColorCapabilities = DecoderColorCapabilities(),
    val surface: VideoSurfaceKind = VideoSurfaceKind.UNKNOWN,
    val renderer: ColorPipelineRenderer = ColorPipelineRenderer.UNKNOWN,
    val rendererCapabilities: RendererColorCapabilities = RendererColorCapabilities(),
    val conversionCapabilities: ColorConversionCapabilities = ColorConversionCapabilities(),
    val plannedOutputDynamicRange: VideoDynamicRange = VideoDynamicRange.UNKNOWN,
    val outputDynamicRange: VideoDynamicRange = VideoDynamicRange.UNKNOWN,
    /** Planned Dolby Vision output configuration; profile 8 + HDR10 base means Profile 8.1. */
    val plannedOutputDolbyVision: DolbyVisionInfo? = null,
    /** Confirmed Dolby Vision output configuration, or `null` while the route is unverified. */
    val outputDolbyVision: DolbyVisionInfo? = null,
    val plannedDolbyVisionProfileMapping: DolbyVisionProfileMapping? = null,
    /** Mapping already applied by the source bridge; this does not by itself confirm HDR display output. */
    val dolbyVisionProfileMapping: DolbyVisionProfileMapping? = null,
    /** Metadata handling selected by the planner; it is not evidence that a frame reached the output. */
    val plannedMetadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.NONE,
    /** Metadata handling on the confirmed output route, or [DynamicMetadataHandling.NONE] while pending. */
    val metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.NONE,
    val verification: ColorPipelineVerification = ColorPipelineVerification.NONE,
    val requestHonored: Boolean = false,
    val fallbackReason: ColorPipelineFallbackReason = ColorPipelineFallbackReason.NONE,
    val detail: String? = null,
)

enum class ColorPipelineRoute {
    SYSTEM_NATIVE_SURFACE,
    CONTROLLED_HDR_RENDERER,
    CONTROLLED_SDR_RENDERER,
    SOURCE_BRIDGE_SDR,
    DOLBY_VISION_CONVERSION,
    UNSUPPORTED,
}

@Immutable
data class VideoColorPipelineRequest(
    val source: VideoColorInfo,
    /** The color signal presented to the decoder after an optional source bridge. */
    val decoderInput: VideoColorInfo = source,
    /** Mapping already installed by a source bridge for this request. */
    val appliedDolbyVisionProfileMapping: DolbyVisionProfileMapping? = null,
    val display: DisplayColorCapabilities,
    val decoder: DecoderColorCapabilities,
    val renderer: RendererColorCapabilities,
    val conversion: ColorConversionCapabilities = ColorConversionCapabilities(),
    val dynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.AUTO,
    val dolbyVisionPolicy: DolbyVisionPolicy = DolbyVisionPolicy.AUTO,
    val isProjection: Boolean = false,
    val nativeSurfaceAvailable: Boolean = false,
    val surfaceKind: VideoSurfaceKind = VideoSurfaceKind.UNKNOWN,
    val isLive: Boolean = false,
    val isDrmProtected: Boolean = false,
    val allowAutomaticDolbyVisionConversion: Boolean = true,
    val platformRuntimeFallbackReason: ColorPipelineFallbackReason? = null,
    val platformRuntimeDetail: String? = null,
)

@Immutable
data class VideoColorPipelinePlan(
    val route: ColorPipelineRoute,
    val outputDynamicRange: VideoDynamicRange,
    val metadataHandling: DynamicMetadataHandling,
    val requestHonored: Boolean,
    val outputDolbyVision: DolbyVisionInfo? = null,
    val dolbyVisionProfileMapping: DolbyVisionProfileMapping? = null,
    val fallbackReason: ColorPipelineFallbackReason = ColorPipelineFallbackReason.NONE,
    val detail: String? = null,
)

/** Pure decision engine shared by preflight, every platform backend and unit tests. */
object VideoColorPipelinePlanner {
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun plan(request: VideoColorPipelineRequest): VideoColorPipelinePlan {
        val sourceRange = request.source.dynamicRange

        if (request.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR && !request.source.isHdr) {
            return unsupported(
                if (sourceRange == VideoDynamicRange.SDR) {
                    ColorPipelineFallbackReason.SOURCE_IS_SDR
                } else {
                    ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN
                },
                "REQUIRE_HDR needs a source whose HDR signal is known.",
            )
        }

        if (sourceRange == VideoDynamicRange.UNKNOWN) {
            return unsupported(
                ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN,
                "The source color transfer is unknown, so a color-managed output cannot be guaranteed.",
            )
        }

        if (
            request.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR &&
            request.platformRuntimeFallbackReason != null
        ) {
            return unsupported(
                request.platformRuntimeFallbackReason,
                request.platformRuntimeDetail ?: "The platform could not confirm the required HDR output.",
            )
        }

        val canDecodeDolbyVisionBaseLayer =
            sourceRange == VideoDynamicRange.DOLBY_VISION &&
                request.source.dolbyVision?.hasHdr10CompatibleBaseLayer == true &&
                request.decoder.supports(VideoDynamicRange.HDR10)
        val canDecodeConvertedDolbyVision =
            sourceRange == VideoDynamicRange.DOLBY_VISION &&
                request.source.dolbyVision?.profile == DOLBY_VISION_PROFILE_7 &&
                !request.isLive &&
                !request.isDrmProtected &&
                request.conversion.supportsDolbyVisionProfile7To8 &&
                request.conversion.supportsStreamingVOD &&
                request.decoder.supportsDolbyVisionProfile(DOLBY_VISION_PROFILE_8)
        if (
            request.decoder.isKnown &&
            !request.decoder.supports(request.decoderInput) &&
            !canDecodeDolbyVisionBaseLayer &&
            !canDecodeConvertedDolbyVision
        ) {
            return unsupported(
                ColorPipelineFallbackReason.DECODER_DOES_NOT_SUPPORT_SOURCE,
                "The selected decoder cannot produce color-correct frames for this source.",
            )
        }

        if (request.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR) {
            if (sourceRange == VideoDynamicRange.SDR && request.nativeSurfaceAvailable && !request.isProjection) {
                return VideoColorPipelinePlan(
                    route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
                    outputDynamicRange = VideoDynamicRange.SDR,
                    metadataHandling = DynamicMetadataHandling.NONE,
                    requestHonored = true,
                    fallbackReason = ColorPipelineFallbackReason.EXPLICIT_SDR_REQUEST,
                )
            }
            return systemToneMappedSdr(request, ColorPipelineFallbackReason.EXPLICIT_SDR_REQUEST)
                ?: controlledSdr(request, ColorPipelineFallbackReason.EXPLICIT_SDR_REQUEST)
        }

        if (sourceRange == VideoDynamicRange.SDR) {
            return if (request.nativeSurfaceAvailable && !request.isProjection) {
                VideoColorPipelinePlan(
                    route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
                    outputDynamicRange = VideoDynamicRange.SDR,
                    metadataHandling = DynamicMetadataHandling.NONE,
                    requestHonored = true,
                )
            } else {
                controlledSdr(request, ColorPipelineFallbackReason.NONE)
            }
        }

        if (sourceRange == VideoDynamicRange.DOLBY_VISION) {
            return planDolbyVision(request)
        }

        val exactNative =
            request.nativeSurfaceAvailable &&
                !request.isProjection &&
                request.platformRuntimeFallbackReason == null &&
                request.display.supports(sourceRange) &&
                request.decoder.supports(sourceRange) &&
                request.renderer.supportsNative(sourceRange) &&
                (
                    sourceRange != VideoDynamicRange.HDR10_PLUS ||
                        request.renderer.supportsHdr10PlusPassthrough
                )
        if (exactNative) {
            return VideoColorPipelinePlan(
                route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
                outputDynamicRange = sourceRange,
                metadataHandling = DynamicMetadataHandling.PASSTHROUGH,
                requestHonored = true,
            )
        }

        val controlledOutputRange =
            request.renderer.controlledOutputConversions[sourceRange]
                ?: if (
                    sourceRange == VideoDynamicRange.HDR10_PLUS &&
                    request.renderer.supportsHdr10PlusApplication
                ) {
                    VideoDynamicRange.HDR10
                } else {
                    sourceRange
                }
        val controlledHdr =
            request.display.supports(controlledOutputRange) &&
                request.decoder.supports(sourceRange) &&
                request.renderer.supportsControlled(sourceRange, request.isProjection) &&
                (
                    sourceRange != VideoDynamicRange.HDR10_PLUS ||
                        request.renderer.supportsHdr10PlusApplication
                )
        if (controlledHdr) {
            val handling =
                if (sourceRange == VideoDynamicRange.HDR10_PLUS) {
                    DynamicMetadataHandling.APPLIED_BY_RENDERER
                } else {
                    DynamicMetadataHandling.PASSTHROUGH
                }
            return VideoColorPipelinePlan(
                route = ColorPipelineRoute.CONTROLLED_HDR_RENDERER,
                outputDynamicRange = controlledOutputRange,
                metadataHandling = handling,
                requestHonored = true,
                fallbackReason =
                    request.platformRuntimeFallbackReason
                        ?: if (request.isProjection && !request.renderer.supportsHdrProjection) {
                            ColorPipelineFallbackReason.HDR_PROJECTION_UNAVAILABLE
                        } else {
                            ColorPipelineFallbackReason.NONE
                        },
                detail = request.platformRuntimeDetail,
            )
        }

        if (sourceRange == VideoDynamicRange.HDR10_PLUS) {
            staticHdr10Fallback(request)?.let { return it }
        }

        systemToneMappedSdr(request, hdrUnavailableReason(request))?.let { return it }

        if (request.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR) {
            return unsupported(
                hdrUnavailableReason(request),
                request.platformRuntimeDetail ?: "No confirmed HDR output route is available.",
            )
        }
        return controlledSdr(request, hdrUnavailableReason(request))
    }

    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun planDolbyVision(request: VideoColorPipelineRequest): VideoColorPipelinePlan {
        val dolbyVision = request.source.dolbyVision ?: DolbyVisionInfo()
        val profile = dolbyVision.profile
        val appliedMapping = request.appliedDolbyVisionProfileMapping
        if (appliedMapping != null) {
            if (
                appliedMapping.sourceProfile != profile ||
                appliedMapping.outputProfile != DOLBY_VISION_PROFILE_8 ||
                !appliedMapping.outputHasHdr10CompatibleBaseLayer
            ) {
                return unsupported(
                    ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED,
                    "The installed source bridge did not expose a valid Profile 7 to Profile 8.1 mapping.",
                )
            }
            if (!dolbyVisionConversionTargetAvailable(request, appliedMapping.outputProfile)) {
                return unsupported(
                    ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
                    "The Profile 8.1 source is prepared, but no confirmed Dolby Vision " +
                        "decoder/display route is available.",
                )
            }
            return dolbyVisionConversionPlan(dolbyVision, appliedMapping)
        }

        val nativeAvailable =
            request.nativeSurfaceAvailable &&
                !request.isProjection &&
                request.display.supports(VideoDynamicRange.DOLBY_VISION) &&
                request.decoder.supportsDolbyVisionProfile(profile) &&
                request.renderer.supportsNative(VideoDynamicRange.DOLBY_VISION) &&
                request.renderer.supportsDolbyVisionMetadata
        val nativePlan = if (nativeAvailable) nativeDolbyVisionPlan(dolbyVision) else null
        val baseLayerPlan = dolbyVisionBaseLayerPlan(request)
        val automaticConversionPlan =
            if (
                request.dolbyVisionPolicy == DolbyVisionPolicy.AUTO &&
                request.allowAutomaticDolbyVisionConversion
            ) {
                availableDolbyVisionConversionPlan(request, dolbyVision)
            } else {
                null
            }

        when (request.dolbyVisionPolicy) {
            DolbyVisionPolicy.REQUIRE_NATIVE ->
                return nativePlan
                    ?: unsupported(
                        ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED,
                        "The active decoder/display path cannot preserve Dolby Vision signaling.",
                    )
            DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER -> {
                baseLayerPlan?.let { return it.copy(requestHonored = true) }
                nativePlan?.let {
                    return it.copy(
                        requestHonored = false,
                        fallbackReason = ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_UNAVAILABLE,
                        detail =
                            "No verified HDR10-compatible base-layer route is available; " +
                                "native Dolby Vision is used.",
                    )
                }
            }
            DolbyVisionPolicy.AUTO -> {
                nativePlan?.let { return it }
                automaticConversionPlan?.let { return it }
                baseLayerPlan?.let { return it }
            }
            DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1 ->
                availableDolbyVisionConversionPlan(request, dolbyVision)?.let { return it }
        }

        val conversionConsidered =
            request.dolbyVisionPolicy == DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1 ||
                (
                    request.dolbyVisionPolicy == DolbyVisionPolicy.AUTO &&
                        request.allowAutomaticDolbyVisionConversion
                )
        val conversionFailureReason =
            when {
                !conversionConsidered -> ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED
                request.isLive -> ColorPipelineFallbackReason.LIVE_SOURCE_CONVERSION_UNSUPPORTED
                request.isDrmProtected -> ColorPipelineFallbackReason.DRM_CONVERSION_UNSUPPORTED
                profile != DOLBY_VISION_PROFILE_7 -> ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED
                dolbyVision.hasRpu == false -> ColorPipelineFallbackReason.DOLBY_VISION_RPU_UNAVAILABLE
                !request.conversion.supportsDolbyVisionProfile7To8 ||
                    !request.conversion.supportsStreamingVOD ->
                    ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE
                !dolbyVisionConversionTargetAvailable(request, DOLBY_VISION_PROFILE_8) ->
                    ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
                else -> ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE
            }

        systemToneMappedSdr(
            request,
            conversionFailureReason,
        )?.let { return it }

        if (request.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR) {
            return unsupported(
                conversionFailureReason,
                "Dolby Vision cannot be preserved or converted by this runtime.",
            )
        }
        return controlledSdr(request, conversionFailureReason)
    }

    private fun nativeDolbyVisionPlan(dolbyVision: DolbyVisionInfo): VideoColorPipelinePlan =
        VideoColorPipelinePlan(
            route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
            outputDynamicRange = VideoDynamicRange.DOLBY_VISION,
            metadataHandling = DynamicMetadataHandling.PASSTHROUGH,
            requestHonored = true,
            outputDolbyVision = dolbyVision,
        )

    @Suppress("ComplexCondition")
    private fun availableDolbyVisionConversionPlan(
        request: VideoColorPipelineRequest,
        dolbyVision: DolbyVisionInfo,
    ): VideoColorPipelinePlan? {
        if (
            request.isLive ||
            request.isDrmProtected ||
            !request.conversion.supportsDolbyVisionProfile7To8 ||
            !request.conversion.supportsStreamingVOD ||
            dolbyVision.profile != DOLBY_VISION_PROFILE_7 ||
            dolbyVision.hasRpu == false ||
            !dolbyVisionConversionTargetAvailable(request, DOLBY_VISION_PROFILE_8)
        ) {
            return null
        }
        return dolbyVisionConversionPlan(dolbyVision, dolbyVision.profile7To81Mapping())
    }

    private fun dolbyVisionConversionTargetAvailable(
        request: VideoColorPipelineRequest,
        outputProfile: Int?,
    ): Boolean =
        request.nativeSurfaceAvailable &&
            !request.isProjection &&
            request.display.supports(VideoDynamicRange.DOLBY_VISION) &&
            request.decoder.supportsDolbyVisionProfile(outputProfile) &&
            request.renderer.supportsNative(VideoDynamicRange.DOLBY_VISION) &&
            request.renderer.supportsDolbyVisionMetadata

    private fun dolbyVisionConversionPlan(
        source: DolbyVisionInfo,
        mapping: DolbyVisionProfileMapping,
    ): VideoColorPipelinePlan {
        val output =
            source.copy(
                profile = DOLBY_VISION_PROFILE_8,
                hasRpu = true,
                enhancementLayer = DolbyVisionEnhancementLayer.NONE,
                hasHdr10CompatibleBaseLayer = true,
            )
        return VideoColorPipelinePlan(
            route = ColorPipelineRoute.DOLBY_VISION_CONVERSION,
            outputDynamicRange = VideoDynamicRange.DOLBY_VISION,
            metadataHandling = DynamicMetadataHandling.CONVERTED,
            requestHonored = true,
            outputDolbyVision = output,
            dolbyVisionProfileMapping = mapping,
            detail = mapping.profile7To81Detail(),
        )
    }

    private fun dolbyVisionBaseLayerPlan(request: VideoColorPipelineRequest): VideoColorPipelinePlan? {
        val canDecodeBaseLayer =
            request.source.dolbyVision?.hasHdr10CompatibleBaseLayer == true &&
                request.display.supports(VideoDynamicRange.HDR10) &&
                request.decoder.supports(VideoDynamicRange.HDR10)
        if (!canDecodeBaseLayer) return null

        val route =
            when {
                request.nativeSurfaceAvailable &&
                    !request.isProjection &&
                    request.renderer.supportsNative(VideoDynamicRange.HDR10) -> ColorPipelineRoute.SYSTEM_NATIVE_SURFACE
                request.renderer.supportsControlled(VideoDynamicRange.HDR10, request.isProjection) ->
                    ColorPipelineRoute.CONTROLLED_HDR_RENDERER
                else -> return null
            }
        return VideoColorPipelinePlan(
            route = route,
            outputDynamicRange = VideoDynamicRange.HDR10,
            metadataHandling = DynamicMetadataHandling.DROPPED,
            requestHonored = false,
            fallbackReason = ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_USED,
            detail = "Dolby Vision metadata is dropped and the verified HDR10-compatible base layer is used.",
        )
    }

    private fun controlledSdr(
        request: VideoColorPipelineRequest,
        reason: ColorPipelineFallbackReason,
    ): VideoColorPipelinePlan =
        when {
            request.requiresUnavailableDolbyVisionToneMapper ->
                unsupported(
                    ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED,
                    "Dolby Vision cannot be tone-mapped without a compatible base layer or a verified DV renderer.",
                )
            request.canUseHdrToSdrSourceBridge -> sourceBridgeSdrPlan(request, reason)
            request.needsHdrToSdrToneMapper ->
                unsupported(
                    ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE,
                    "HDR cannot be displayed and this renderer has no verified HDR-to-SDR tone mapper.",
                )
            else -> controlledRendererSdrPlan(request, reason)
        }

    private val VideoColorPipelineRequest.requiresUnavailableDolbyVisionToneMapper: Boolean
        get() =
            source.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                source.dolbyVision?.hasHdr10CompatibleBaseLayer != true &&
                !renderer.supportsDolbyVisionToneMappingToSdr

    private val VideoColorPipelineRequest.needsHdrToSdrToneMapper: Boolean
        get() = source.isHdr && !renderer.supportsToneMappingToSdr

    private val VideoColorPipelineRequest.hasEligibleHdrToSdrSourceBridge: Boolean
        get() =
            conversion.supportsHdrToSdrSourceBridge &&
                conversion.supportsStreamingVOD &&
                !isLive &&
                !isDrmProtected

    private val VideoColorPipelineRequest.canUseHdrToSdrSourceBridge: Boolean
        get() = needsHdrToSdrToneMapper && hasEligibleHdrToSdrSourceBridge

    private fun sourceBridgeSdrPlan(
        request: VideoColorPipelineRequest,
        reason: ColorPipelineFallbackReason,
    ): VideoColorPipelinePlan =
        VideoColorPipelinePlan(
            route = ColorPipelineRoute.SOURCE_BRIDGE_SDR,
            outputDynamicRange = VideoDynamicRange.SDR,
            metadataHandling =
                if (
                    request.source.dynamicRange == VideoDynamicRange.HDR10_PLUS ||
                    request.source.dynamicRange == VideoDynamicRange.DOLBY_VISION
                ) {
                    DynamicMetadataHandling.DROPPED
                } else {
                    DynamicMetadataHandling.APPLIED_BY_SOURCE_BRIDGE
                },
            requestHonored = request.dynamicRangePolicy.acceptsSdrFallback,
            fallbackReason = reason,
            detail =
                if (request.decoderInput.dynamicRange == VideoDynamicRange.SDR) {
                    "The optional source bridge emitted explicitly tagged BT.709 SDR."
                } else {
                    "The optional source bridge will tone-map this HDR source to explicitly tagged BT.709 SDR."
                },
        )

    private fun controlledRendererSdrPlan(
        request: VideoColorPipelineRequest,
        reason: ColorPipelineFallbackReason,
    ): VideoColorPipelinePlan =
        VideoColorPipelinePlan(
            route = ColorPipelineRoute.CONTROLLED_SDR_RENDERER,
            outputDynamicRange = VideoDynamicRange.SDR,
            metadataHandling =
                when {
                    request.source.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                        !request.renderer.supportsHdr10PlusApplication -> DynamicMetadataHandling.DROPPED
                    request.source.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                        !request.renderer.supportsDolbyVisionMetadata -> DynamicMetadataHandling.DROPPED
                    request.source.isHdr -> DynamicMetadataHandling.APPLIED_BY_RENDERER
                    else -> DynamicMetadataHandling.NONE
                },
            requestHonored = request.dynamicRangePolicy.acceptsSdrFallback || !request.source.isHdr,
            fallbackReason = reason,
            detail = request.platformRuntimeDetail,
        )

    @Suppress("ComplexCondition")
    private fun systemToneMappedSdr(
        request: VideoColorPipelineRequest,
        reason: ColorPipelineFallbackReason,
    ): VideoColorPipelinePlan? {
        if (
            !request.source.isHdr ||
            request.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR ||
            request.isProjection ||
            !request.nativeSurfaceAvailable ||
            request.platformRuntimeFallbackReason != null ||
            !request.decoder.supports(request.decoderInput) ||
            !request.renderer.supportsNativeToneMappingToSdr
        ) {
            return null
        }
        return VideoColorPipelinePlan(
            route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
            outputDynamicRange = VideoDynamicRange.SDR,
            metadataHandling =
                when {
                    request.source.dynamicRange == VideoDynamicRange.HDR10_PLUS &&
                        !request.renderer.supportsHdr10PlusPassthrough -> DynamicMetadataHandling.DROPPED
                    request.source.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                        !request.renderer.supportsDolbyVisionMetadata -> DynamicMetadataHandling.DROPPED
                    else -> DynamicMetadataHandling.APPLIED_BY_RENDERER
                },
            requestHonored = request.dynamicRangePolicy.acceptsSdrFallback,
            fallbackReason = reason,
            detail = request.platformRuntimeDetail,
        )
    }

    private fun staticHdr10Fallback(request: VideoColorPipelineRequest): VideoColorPipelinePlan? {
        if (!request.display.supports(VideoDynamicRange.HDR10)) return null
        val native =
            request.nativeSurfaceAvailable &&
                !request.isProjection &&
                request.platformRuntimeFallbackReason == null &&
                request.decoder.supports(VideoDynamicRange.HDR10_PLUS) &&
                request.renderer.supportsNative(VideoDynamicRange.HDR10)
        if (native) {
            return VideoColorPipelinePlan(
                route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
                outputDynamicRange = VideoDynamicRange.HDR10,
                metadataHandling = DynamicMetadataHandling.DROPPED,
                requestHonored = false,
                fallbackReason = ColorPipelineFallbackReason.DYNAMIC_METADATA_UNSUPPORTED,
                detail = "HDR10+ dynamic metadata is unavailable; the static HDR10-compatible signal is used.",
            )
        }
        val controlled =
            request.decoder.supports(VideoDynamicRange.HDR10_PLUS) &&
                request.renderer.supportsControlled(VideoDynamicRange.HDR10, request.isProjection)
        if (controlled) {
            return VideoColorPipelinePlan(
                route = ColorPipelineRoute.CONTROLLED_HDR_RENDERER,
                outputDynamicRange = VideoDynamicRange.HDR10,
                metadataHandling = DynamicMetadataHandling.DROPPED,
                requestHonored = false,
                fallbackReason = ColorPipelineFallbackReason.DYNAMIC_METADATA_UNSUPPORTED,
                detail = "HDR10+ dynamic metadata is unavailable; the static HDR10-compatible signal is used.",
            )
        }
        return null
    }

    private fun hdrUnavailableReason(request: VideoColorPipelineRequest): ColorPipelineFallbackReason =
        when {
            request.platformRuntimeFallbackReason != null -> request.platformRuntimeFallbackReason
            request.isProjection && !request.renderer.supportsHdrProjection ->
                ColorPipelineFallbackReason.HDR_PROJECTION_UNAVAILABLE
            !request.display.isKnown -> ColorPipelineFallbackReason.DISPLAY_CAPABILITIES_UNKNOWN
            !request.display.supports(request.source.dynamicRange) ->
                ColorPipelineFallbackReason.DISPLAY_DOES_NOT_SUPPORT_SOURCE
            request.decoder.isKnown && !request.decoder.supports(request.source.dynamicRange) ->
                ColorPipelineFallbackReason.DECODER_DOES_NOT_SUPPORT_SOURCE
            else -> ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
        }

    private fun unsupported(
        reason: ColorPipelineFallbackReason,
        detail: String,
    ): VideoColorPipelinePlan =
        VideoColorPipelinePlan(
            route = ColorPipelineRoute.UNSUPPORTED,
            outputDynamicRange = VideoDynamicRange.UNKNOWN,
            metadataHandling = DynamicMetadataHandling.NONE,
            requestHonored = false,
            fallbackReason = reason,
            detail = detail,
        )
}

private val DynamicRangePolicy.acceptsSdrFallback: Boolean
    get() = this == DynamicRangePolicy.AUTO || this == DynamicRangePolicy.FORCE_SDR

private fun DolbyVisionInfo.profile7To81Mapping(): DolbyVisionProfileMapping =
    DolbyVisionProfileMapping(
        sourceProfile = profile,
        outputProfile = DOLBY_VISION_PROFILE_8,
        outputHasHdr10CompatibleBaseLayer = true,
        enhancementLayerDiscarded =
            when (enhancementLayer) {
                DolbyVisionEnhancementLayer.NONE -> false
                DolbyVisionEnhancementLayer.MEL,
                DolbyVisionEnhancementLayer.FEL,
                -> true
                DolbyVisionEnhancementLayer.UNKNOWN -> null
            },
        felMappingDiscarded =
            when (enhancementLayer) {
                DolbyVisionEnhancementLayer.FEL -> true
                DolbyVisionEnhancementLayer.NONE,
                DolbyVisionEnhancementLayer.MEL,
                -> false
                DolbyVisionEnhancementLayer.UNKNOWN -> null
            },
    )

private fun DolbyVisionProfileMapping.profile7To81Detail(): String =
    when {
        felMappingDiscarded == true ->
            "Dolby Vision Profile 7 FEL will be converted to Profile 8.1; " +
                "the enhancement layer and FEL mapping are discarded."
        enhancementLayerDiscarded == true ->
            "Dolby Vision Profile 7 MEL will be converted to Profile 8.1; " +
                "the enhancement layer is discarded without re-encoding the base-layer picture."
        enhancementLayerDiscarded == false ->
            "Dolby Vision Profile 7 RPU will be converted to Profile 8.1 " +
                "without re-encoding the base-layer picture."
        else ->
            "Dolby Vision Profile 7 will be converted to Profile 8.1 without re-encoding the base-layer picture; " +
                "the source probe could not distinguish MEL from FEL."
    }

private const val DOLBY_VISION_PROFILE_7 = 7
private const val DOLBY_VISION_PROFILE_8 = 8

/** Converts a planner result into the public status without claiming unverified HDR output. */
fun VideoColorPipelinePlan.toStatus(
    request: VideoColorPipelineRequest,
    decoderName: String? = null,
    verification: ColorPipelineVerification = ColorPipelineVerification.NONE,
): VideoColorPipelineStatus {
    val renderer =
        when (route) {
            ColorPipelineRoute.SYSTEM_NATIVE_SURFACE -> ColorPipelineRenderer.SYSTEM_NATIVE
            ColorPipelineRoute.CONTROLLED_HDR_RENDERER -> ColorPipelineRenderer.CONTROLLED_HDR
            ColorPipelineRoute.CONTROLLED_SDR_RENDERER -> ColorPipelineRenderer.CONTROLLED_SDR
            ColorPipelineRoute.SOURCE_BRIDGE_SDR -> ColorPipelineRenderer.SOURCE_BRIDGE_SDR
            ColorPipelineRoute.DOLBY_VISION_CONVERSION -> ColorPipelineRenderer.DOLBY_VISION_CONVERTER
            ColorPipelineRoute.UNSUPPORTED -> ColorPipelineRenderer.UNKNOWN
        }
    val confirmedOutput =
        when {
            verification == ColorPipelineVerification.RENDERER_CONFIGURED ||
                verification == ColorPipelineVerification.SYSTEM_REPORTED -> outputDynamicRange
            else -> VideoDynamicRange.UNKNOWN
        }
    val confirmedMetadataHandling =
        metadataHandling.takeIf { confirmedOutput == outputDynamicRange } ?: DynamicMetadataHandling.NONE
    val confirmedOutputDolbyVision = outputDolbyVision.takeIf { confirmedOutput == outputDynamicRange }
    val appliedOrConfirmedDolbyVisionProfileMapping =
        request.appliedDolbyVisionProfileMapping
            ?: dolbyVisionProfileMapping.takeIf { confirmedOutput == outputDynamicRange }
    return VideoColorPipelineStatus(
        requestedDynamicRangePolicy = request.dynamicRangePolicy,
        requestedDolbyVisionPolicy = request.dolbyVisionPolicy,
        source = request.source,
        display = request.display,
        decoderName = decoderName,
        decoderCapabilities = request.decoder,
        surface = request.surfaceKind,
        renderer = renderer,
        rendererCapabilities = request.renderer,
        conversionCapabilities = request.conversion,
        plannedOutputDynamicRange = outputDynamicRange,
        outputDynamicRange = confirmedOutput,
        plannedOutputDolbyVision = outputDolbyVision,
        outputDolbyVision = confirmedOutputDolbyVision,
        plannedDolbyVisionProfileMapping = dolbyVisionProfileMapping,
        dolbyVisionProfileMapping = appliedOrConfirmedDolbyVisionProfileMapping,
        plannedMetadataHandling = metadataHandling,
        metadataHandling = confirmedMetadataHandling,
        verification = verification,
        requestHonored = requestHonored && confirmedOutput == outputDynamicRange,
        fallbackReason = fallbackReason,
        detail = detail,
    )
}
