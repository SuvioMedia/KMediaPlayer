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
)
