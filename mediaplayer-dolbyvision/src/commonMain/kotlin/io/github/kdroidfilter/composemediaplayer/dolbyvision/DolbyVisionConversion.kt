package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.ColorConversionCapabilities
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import io.github.kdroidfilter.composemediaplayer.VideoSourcePipelineExtension

/** Containers for which the bounded VOD bridge may preserve packet timestamps and audio. */
enum class DolbyVisionContainer {
    MP4,
    FRAGMENTED_MP4,
    MATROSKA,
    HLS_VOD,
    HLS_LIVE,
    UNKNOWN,
}

data class DolbyVisionConversionRequest(
    val container: DolbyVisionContainer,
    val profile: Int?,
    val hasRpu: Boolean,
    val enhancementLayer: DolbyVisionEnhancementLayer,
    val isDrmProtected: Boolean = false,
    val maximumBufferedFragments: Int = DEFAULT_MAXIMUM_BUFFERED_FRAGMENTS,
    val maximumBufferedBytes: Long = DEFAULT_MAXIMUM_BUFFERED_BYTES,
) {
    init {
        require(maximumBufferedFragments in MINIMUM_BUFFERED_FRAGMENTS..MAXIMUM_BUFFERED_FRAGMENTS) {
            "maximumBufferedFragments must be between $MINIMUM_BUFFERED_FRAGMENTS and $MAXIMUM_BUFFERED_FRAGMENTS."
        }
        require(maximumBufferedBytes in MINIMUM_BUFFERED_BYTES..MAXIMUM_BUFFERED_BYTES) {
            "maximumBufferedBytes must be between $MINIMUM_BUFFERED_BYTES and $MAXIMUM_BUFFERED_BYTES."
        }
    }

    val isLive: Boolean get() = container == DolbyVisionContainer.HLS_LIVE

    companion object {
        const val DEFAULT_MAXIMUM_BUFFERED_FRAGMENTS = 4
        const val MINIMUM_BUFFERED_FRAGMENTS = 2
        const val MAXIMUM_BUFFERED_FRAGMENTS = 12
        const val DEFAULT_MAXIMUM_BUFFERED_BYTES = 32L * 1024L * 1024L
        const val MINIMUM_BUFFERED_BYTES = 1024L * 1024L
        const val MAXIMUM_BUFFERED_BYTES = 256L * 1024L * 1024L
    }
}

enum class DolbyVisionConversionRejection {
    NONE,
    MODULE_RUNTIME_UNAVAILABLE,
    SOURCE_PROFILE_UNSUPPORTED,
    RPU_MISSING,
    CONTAINER_UNSUPPORTED,
    LIVE_SOURCE_UNSUPPORTED,
    DRM_UNSUPPORTED,
}

data class DolbyVisionConversionPlan(
    val canConvert: Boolean,
    val rejection: DolbyVisionConversionRejection,
    val outputProfile: Int? = null,
    val discardsEnhancementLayer: Boolean = false,
    val discardsFelMapping: Boolean = false,
    val detail: String? = null,
)

object DolbyVisionConversionPlanner {
    fun plan(
        request: DolbyVisionConversionRequest,
        runtimeAvailable: Boolean,
    ): DolbyVisionConversionPlan {
        if (request.isLive) {
            return rejected(
                DolbyVisionConversionRejection.LIVE_SOURCE_UNSUPPORTED,
                "Live HLS is never rewritten by the Dolby Vision bridge.",
            )
        }
        if (request.isDrmProtected) {
            return rejected(
                DolbyVisionConversionRejection.DRM_UNSUPPORTED,
                "Encrypted/DRM media is never rewritten by the Dolby Vision bridge.",
            )
        }
        if (request.container !in SUPPORTED_VOD_CONTAINERS) {
            return rejected(
                DolbyVisionConversionRejection.CONTAINER_UNSUPPORTED,
                "Only unencrypted MP4/fMP4, Matroska and VOD HLS are supported.",
            )
        }
        if (request.profile != DOLBY_VISION_PROFILE_7) {
            return rejected(
                DolbyVisionConversionRejection.SOURCE_PROFILE_UNSUPPORTED,
                "Profile 7 input is required for Profile 8.1 conversion.",
            )
        }
        if (!request.hasRpu) {
            return rejected(
                DolbyVisionConversionRejection.RPU_MISSING,
                "The source has no confirmed Dolby Vision RPU.",
            )
        }
        if (!runtimeAvailable) {
            return rejected(
                DolbyVisionConversionRejection.MODULE_RUNTIME_UNAVAILABLE,
                "The libdovi runtime and fragment remuxer are unavailable.",
            )
        }

        val fel = request.enhancementLayer == DolbyVisionEnhancementLayer.FEL
        return DolbyVisionConversionPlan(
            canConvert = true,
            rejection = DolbyVisionConversionRejection.NONE,
            outputProfile = DOLBY_VISION_PROFILE_8,
            discardsEnhancementLayer = request.enhancementLayer != DolbyVisionEnhancementLayer.NONE,
            discardsFelMapping = fel,
            detail =
                if (fel) {
                    "Profile 7 FEL is converted to Profile 8.1; the enhancement layer and FEL mapping are discarded."
                } else {
                    "Profile 7 RPU is converted to Profile 8.1 without re-encoding the base-layer picture."
                },
        )
    }

    private fun rejected(
        rejection: DolbyVisionConversionRejection,
        detail: String,
    ): DolbyVisionConversionPlan =
        DolbyVisionConversionPlan(
            canConvert = false,
            rejection = rejection,
            detail = detail,
        )

    private val SUPPORTED_VOD_CONTAINERS =
        setOf(
            DolbyVisionContainer.MP4,
            DolbyVisionContainer.FRAGMENTED_MP4,
            DolbyVisionContainer.MATROSKA,
            DolbyVisionContainer.HLS_VOD,
        )
}

private const val DOLBY_VISION_PROFILE_7 = 7
private const val DOLBY_VISION_PROFILE_8 = 8

/** Converts one HEVC UNSPEC-62 RPU NAL unit through libdovi mode 2 semantics. */
interface DolbyVisionRpuConverter {
    val isAvailable: Boolean

    /** Completes any asynchronous runtime initialization and returns whether conversion is ready. */
    suspend fun prepare(): Boolean = isAvailable

    suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult
}

/**
 * Converter backed by the pinned native libdovi shim bundled with this artifact.
 *
 * [isAvailable] stays false on a target for which the native binary was not packaged. Calling
 * [close] is always safe and idempotent.
 */
expect class LibDoviRpuConverter() : DolbyVisionRpuConverter {
    override val isAvailable: Boolean

    override suspend fun prepare(): Boolean

    override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult

    fun close()
}

sealed interface DolbyVisionRpuConversionResult {
    data class Success(
        val rpuNalUnit: ByteArray,
    ) : DolbyVisionRpuConversionResult

    data class Invalid(
        val message: String,
    ) : DolbyVisionRpuConversionResult

    data class Unavailable(
        val message: String,
    ) : DolbyVisionRpuConversionResult
}

/** Marker installed in [io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions.extensions]. */
class DolbyVisionExtension(
    val converter: DolbyVisionRpuConverter = LibDoviRpuConverter(),
) : VideoSourcePipelineExtension {
    override val id: String = ID
    override val availability: VideoPipelineExtensionAvailability
        get() =
            when {
                !converter.isAvailable ->
                    VideoPipelineExtensionAvailability.unavailable(
                        "The libdovi runtime is unavailable on this target.",
                    )
                !platformDolbyVisionSourceBridgeAvailable() ->
                    VideoPipelineExtensionAvailability.unavailable(
                        "This platform has no bounded Dolby Vision playback bridge.",
                    )
                else -> VideoPipelineExtensionAvailability.Available
            }
    override val colorConversionCapabilities: ColorConversionCapabilities
        get() =
            ColorConversionCapabilities(
                supportsDolbyVisionProfile7To8 = availability.canContribute,
                supportsStreamingVOD = availability.canContribute,
            )

    @Suppress("ReturnCount")
    override suspend fun prepareSource(request: VideoPipelineSourceRequest): VideoPipelineSourcePreparation {
        val conversionRequested =
            request.dolbyVisionPolicy == DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1 ||
                (
                    request.dolbyVisionPolicy == DolbyVisionPolicy.AUTO &&
                        request.automaticDolbyVisionConversionAllowed
                )
        if (
            !conversionRequested ||
            request.source.dynamicRange != VideoDynamicRange.DOLBY_VISION
        ) {
            return VideoPipelineSourcePreparation.NotApplicable
        }
        val dolbyVision = request.source.dolbyVision
        if (dolbyVision?.profile != DOLBY_VISION_PROFILE_7) {
            return rejected(
                ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED,
                "Profile 7 input is required for Profile 8.1 conversion.",
            )
        }
        if (dolbyVision.hasRpu == false) {
            return rejected(
                ColorPipelineFallbackReason.DOLBY_VISION_RPU_UNAVAILABLE,
                "The source explicitly reports that no Dolby Vision RPU is present.",
            )
        }
        if (request.isLive) {
            return rejected(
                ColorPipelineFallbackReason.LIVE_SOURCE_CONVERSION_UNSUPPORTED,
                "Live HLS is never rewritten by the Dolby Vision bridge.",
            )
        }
        if (request.isDrmProtected) {
            return rejected(
                ColorPipelineFallbackReason.DRM_CONVERSION_UNSUPPORTED,
                "Encrypted/DRM media is never rewritten by the Dolby Vision bridge.",
            )
        }
        if (!converter.prepare() || !platformDolbyVisionSourceBridgeAvailable()) {
            return rejected(
                ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE,
                "The libdovi runtime or platform playback bridge is unavailable.",
            )
        }
        return preparePlatformDolbyVisionSource(request, converter)
    }

    private fun rejected(
        reason: ColorPipelineFallbackReason,
        detail: String,
    ) = VideoPipelineSourcePreparation.Rejected(reason, detail)

    companion object {
        const val ID = "io.github.shusek.composemediaplayer.dolbyvision"
    }
}

internal expect fun platformDolbyVisionSourceBridgeAvailable(): Boolean

internal expect suspend fun preparePlatformDolbyVisionSource(
    request: VideoPipelineSourceRequest,
    converter: DolbyVisionRpuConverter,
): VideoPipelineSourcePreparation

internal fun VideoPipelineSourceRequest.profile81OutputColorInfo() =
    source.copy(
        dolbyVision =
            source.dolbyVision?.copy(
                profile = DOLBY_VISION_PROFILE_8,
                hasRpu = true,
                enhancementLayer = DolbyVisionEnhancementLayer.NONE,
                hasHdr10CompatibleBaseLayer = true,
            ),
    )
