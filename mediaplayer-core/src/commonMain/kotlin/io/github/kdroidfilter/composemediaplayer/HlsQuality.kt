package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

enum class AdaptiveQualityMode {
    AUTO,
    MANUAL,
}

@Stable
data class AdaptiveQualityVariant(
    val id: String,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val codecs: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Adaptive quality id must not be blank." }
        require(label.isNotBlank()) { "Adaptive quality label must not be blank." }
        require(width == null || width > 0) { "Adaptive quality width must be greater than 0 when set." }
        require(height == null || height > 0) { "Adaptive quality height must be greater than 0 when set." }
        require(bitrate == null || bitrate > 0) { "Adaptive quality bitrate must be greater than 0 when set." }
    }
}

sealed class AdaptiveQualitySelectionResult {
    val isApplied: Boolean
        get() = this is Auto || this is Selected

    data object Auto : AdaptiveQualitySelectionResult()

    data class Selected(
        val quality: AdaptiveQualityVariant,
    ) : AdaptiveQualitySelectionResult()

    data class NotFound(
        val variantId: String,
    ) : AdaptiveQualitySelectionResult()

    data object NotSupported : AdaptiveQualitySelectionResult()
}

@Deprecated("Use AdaptiveQualityMode.", ReplaceWith("AdaptiveQualityMode"))
enum class HlsQualityMode {
    AUTO,
    MANUAL,
}

@Stable
@Deprecated("Use AdaptiveQualityVariant.", ReplaceWith("AdaptiveQualityVariant"))
data class HlsQualityVariant(
    val id: String,
    val label: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Int? = null,
    val codecs: String? = null,
) {
    init {
        require(id.isNotBlank()) { "HLS quality id must not be blank." }
        require(label.isNotBlank()) { "HLS quality label must not be blank." }
        require(width == null || width > 0) { "HLS quality width must be greater than 0 when set." }
        require(height == null || height > 0) { "HLS quality height must be greater than 0 when set." }
        require(bitrate == null || bitrate > 0) { "HLS quality bitrate must be greater than 0 when set." }
    }
}

@Deprecated("Use AdaptiveQualitySelectionResult.", ReplaceWith("AdaptiveQualitySelectionResult"))
sealed class HlsQualitySelectionResult {
    val isApplied: Boolean
        get() = this is Auto || this is Selected

    data object Auto : HlsQualitySelectionResult()

    data class Selected(
        val quality: HlsQualityVariant,
    ) : HlsQualitySelectionResult()

    data class NotFound(
        val variantId: String,
    ) : HlsQualitySelectionResult()

    data object NotSupported : HlsQualitySelectionResult()
}

internal fun HlsQualityMode.toAdaptiveQualityMode(): AdaptiveQualityMode =
    when (this) {
        HlsQualityMode.AUTO -> AdaptiveQualityMode.AUTO
        HlsQualityMode.MANUAL -> AdaptiveQualityMode.MANUAL
    }

internal fun HlsQualityVariant.toAdaptiveQualityVariant(): AdaptiveQualityVariant =
    AdaptiveQualityVariant(
        id = id,
        label = label,
        width = width,
        height = height,
        bitrate = bitrate,
        codecs = codecs,
    )

internal fun HlsQualitySelectionResult.toAdaptiveQualitySelectionResult(): AdaptiveQualitySelectionResult =
    when (this) {
        HlsQualitySelectionResult.Auto -> AdaptiveQualitySelectionResult.Auto
        is HlsQualitySelectionResult.Selected ->
            AdaptiveQualitySelectionResult.Selected(quality.toAdaptiveQualityVariant())
        is HlsQualitySelectionResult.NotFound -> AdaptiveQualitySelectionResult.NotFound(variantId)
        HlsQualitySelectionResult.NotSupported -> AdaptiveQualitySelectionResult.NotSupported
    }
