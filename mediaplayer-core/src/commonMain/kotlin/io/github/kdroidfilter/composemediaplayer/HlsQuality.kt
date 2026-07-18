package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

enum class HlsQualityMode {
    AUTO,
    MANUAL,
}

@Stable
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
