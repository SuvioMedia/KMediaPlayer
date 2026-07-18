package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Mutable coordinator used by platform states; all routing decisions remain in the pure planner. */
internal class VideoColorPipelineController(
    private val playbackOptions: VideoPlaybackOptions,
    initialCapabilities: PlayerCapabilities = PlayerCapabilities(),
) {
    private fun PlayerCapabilities.withConfiguredExtensions(): PlayerCapabilities =
        withPipelineExtensions(playbackOptions)

    private val configuredInitialCapabilities = initialCapabilities.withConfiguredExtensions()
    private var source = VideoColorInfo()
    private var decoderInput = source
    private var appliedDolbyVisionProfileMapping: DolbyVisionProfileMapping? = null
    private var display = configuredInitialCapabilities.displayColorCapabilities
    private var decoder = configuredInitialCapabilities.decoderColorCapabilities
    private var renderer = configuredInitialCapabilities.rendererColorCapabilities
    private var conversion = configuredInitialCapabilities.colorConversionCapabilities
    private var decoderName: String? = null
    private var surfaceKind = VideoSurfaceKind.UNKNOWN
    private var nativeSurfaceAvailable = false
    private var isProjection = false
    private var isLive = false
    private var isDrmProtected = false
    private var allowAutomaticDolbyVisionConversion = true
    private var verification = ColorPipelineVerification.NONE
    private var platformRuntimeFallbackReason: ColorPipelineFallbackReason? = null
    private var platformRuntimeDetail: String? = null
    private val mutableStatus =
        MutableStateFlow(
            VideoColorPipelineStatus(
                requestedDynamicRangePolicy = playbackOptions.dynamicRangePolicy,
                requestedDolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                display = display,
                decoderCapabilities = decoder,
                rendererCapabilities = renderer,
                conversionCapabilities = conversion,
            ),
        )

    val status: StateFlow<VideoColorPipelineStatus> = mutableStatus.asStateFlow()
    var currentPlan: VideoColorPipelinePlan? = null
        private set

    fun updateSource(
        source: VideoColorInfo,
        decoderInput: VideoColorInfo = source,
        appliedDolbyVisionProfileMapping: DolbyVisionProfileMapping? = null,
        decoderName: String? = this.decoderName,
        decoderCapabilities: DecoderColorCapabilities = decoder,
        isLive: Boolean = this.isLive,
        isDrmProtected: Boolean = this.isDrmProtected,
        allowAutomaticDolbyVisionConversion: Boolean = this.allowAutomaticDolbyVisionConversion,
    ): VideoColorPipelinePlan? {
        this.source = source
        this.decoderInput = decoderInput
        this.appliedDolbyVisionProfileMapping = appliedDolbyVisionProfileMapping
        this.decoderName = decoderName
        this.decoder = decoderCapabilities
        this.isLive = isLive
        this.isDrmProtected = isDrmProtected
        this.allowAutomaticDolbyVisionConversion = allowAutomaticDolbyVisionConversion
        verification = ColorPipelineVerification.NONE
        return evaluate()
    }

    fun updateOutput(
        displayCapabilities: DisplayColorCapabilities = display,
        rendererCapabilities: RendererColorCapabilities = renderer,
        conversionCapabilities: ColorConversionCapabilities = conversion,
        surfaceKind: VideoSurfaceKind = this.surfaceKind,
        nativeSurfaceAvailable: Boolean = this.nativeSurfaceAvailable,
        isProjection: Boolean = this.isProjection,
        verification: ColorPipelineVerification = ColorPipelineVerification.NONE,
        platformRuntimeFallbackReason: ColorPipelineFallbackReason? = this.platformRuntimeFallbackReason,
        platformRuntimeDetail: String? = this.platformRuntimeDetail,
    ): VideoColorPipelinePlan? {
        display = displayCapabilities
        renderer = rendererCapabilities
        conversion =
            PlayerCapabilities(colorConversionCapabilities = conversionCapabilities)
                .withConfiguredExtensions()
                .colorConversionCapabilities
        this.surfaceKind = surfaceKind
        this.nativeSurfaceAvailable = nativeSurfaceAvailable
        this.isProjection = isProjection
        this.verification = verification
        this.platformRuntimeFallbackReason = platformRuntimeFallbackReason
        this.platformRuntimeDetail = platformRuntimeDetail
        return evaluate()
    }

    fun updateCapabilities(capabilities: PlayerCapabilities): VideoColorPipelinePlan? {
        val configuredCapabilities = capabilities.withConfiguredExtensions()
        decoder = configuredCapabilities.decoderColorCapabilities
        return updateOutput(
            displayCapabilities = configuredCapabilities.displayColorCapabilities,
            rendererCapabilities = configuredCapabilities.rendererColorCapabilities,
            conversionCapabilities = configuredCapabilities.colorConversionCapabilities,
            verification = ColorPipelineVerification.NONE,
        )
    }

    fun resetSource() {
        source = VideoColorInfo()
        decoderInput = source
        appliedDolbyVisionProfileMapping = null
        decoderName = null
        isLive = false
        isDrmProtected = false
        allowAutomaticDolbyVisionConversion = true
        verification = ColorPipelineVerification.NONE
        currentPlan = null
        mutableStatus.value =
            VideoColorPipelineStatus(
                requestedDynamicRangePolicy = playbackOptions.dynamicRangePolicy,
                requestedDolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                display = display,
                decoderCapabilities = decoder,
                surface = surfaceKind,
                rendererCapabilities = renderer,
                conversionCapabilities = conversion,
            )
    }

    /** Returns an error whenever rendering would otherwise use an un-managed or unsupported color route. */
    fun pipelineErrorOrNull(): VideoPlayerError.ColorPipelineError? {
        val plan = currentPlan ?: return null
        if (plan.route != ColorPipelineRoute.UNSUPPORTED) return null
        return VideoPlayerError.ColorPipelineError(
            reason = plan.fallbackReason,
            message = plan.detail ?: "The requested color pipeline is unavailable.",
        )
    }

    private fun evaluate(): VideoColorPipelinePlan? {
        if (source.dynamicRange == VideoDynamicRange.UNKNOWN) {
            if (
                playbackOptions.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR ||
                playbackOptions.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR
            ) {
                val request = currentRequest()
                return VideoColorPipelinePlanner.plan(request).also { plan ->
                    currentPlan = plan
                    mutableStatus.value = plan.toStatus(request, decoderName, verification)
                }
            }
            currentPlan = null
            mutableStatus.value =
                VideoColorPipelineStatus(
                    requestedDynamicRangePolicy = playbackOptions.dynamicRangePolicy,
                    requestedDolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
                    source = source,
                    display = display,
                    decoderName = decoderName,
                    decoderCapabilities = decoder,
                    surface = surfaceKind,
                    rendererCapabilities = renderer,
                    conversionCapabilities = conversion,
                    fallbackReason = ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN,
                )
            return null
        }
        val request = currentRequest()
        return VideoColorPipelinePlanner.plan(request).also { plan ->
            currentPlan = plan
            mutableStatus.value = plan.toStatus(request, decoderName, verification)
        }
    }

    private fun currentRequest(): VideoColorPipelineRequest =
        VideoColorPipelineRequest(
            source = source,
            decoderInput = decoderInput,
            appliedDolbyVisionProfileMapping = appliedDolbyVisionProfileMapping,
            display = display,
            decoder = decoder,
            renderer = renderer,
            conversion = conversion,
            dynamicRangePolicy = playbackOptions.dynamicRangePolicy,
            dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
            isProjection = isProjection,
            nativeSurfaceAvailable = nativeSurfaceAvailable,
            surfaceKind = surfaceKind,
            isLive = isLive,
            isDrmProtected = isDrmProtected,
            allowAutomaticDolbyVisionConversion = allowAutomaticDolbyVisionConversion,
            platformRuntimeFallbackReason = platformRuntimeFallbackReason,
            platformRuntimeDetail = platformRuntimeDetail,
        )
}
