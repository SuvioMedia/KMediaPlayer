package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
data class MediaSourceSpec(
    val uri: String,
    val mimeType: String? = null,
)

enum class HdrSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

@Stable
data class HdrCapabilities(
    val hdr: HdrSupport = HdrSupport.UNKNOWN,
    val hdr10: HdrSupport = HdrSupport.UNKNOWN,
    val hlg: HdrSupport = HdrSupport.UNKNOWN,
    val dolbyVision: HdrSupport = HdrSupport.UNKNOWN,
    val supportsNativeHdrPlayback: Boolean = false,
    val supportsToneMappingToSdr: Boolean = false,
    val maxExtendedDynamicRange: Float = 1f,
) {
    val hasHdrDisplay: Boolean
        get() = maxExtendedDynamicRange > 1f
}

@Stable
data class PlayerCapabilities(
    val supportsMkv: Boolean = false,
    val supportsPiP: Boolean = false,
    val hdr: HdrCapabilities = HdrCapabilities(),
) {
    fun canPlaySource(
        uri: String,
        mimeType: String? = null,
    ): Boolean = canPlaySource(MediaSourceSpec(uri = uri, mimeType = mimeType))

    fun canPlaySource(source: MediaSourceSpec): Boolean {
        val trimmedUri = source.uri.trim()
        if (trimmedUri.isEmpty()) return false

        val scheme = trimmedUri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme.isNotEmpty() && scheme !in DEFAULT_SUPPORTED_URI_SCHEMES) return false

        val normalizedMimeType =
            source.mimeType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (normalizedMimeType.isHlsMimeType() || trimmedUri.isHlsUri()) return true
        if (normalizedMimeType.isMkvMimeType() || trimmedUri.isMkvUri()) return supportsMkv

        return true
    }
}

internal expect fun platformPlayerCapabilities(): PlayerCapabilities

private val DEFAULT_SUPPORTED_URI_SCHEMES = setOf("asset", "blob", "content", "data", "file", "http", "https")

private fun String?.isHlsMimeType(): Boolean =
    this == "application/vnd.apple.mpegurl" ||
        this == "application/x-mpegurl" ||
        this == "audio/mpegurl" ||
        this == "audio/x-mpegurl"

private fun String?.isMkvMimeType(): Boolean = this == "video/x-matroska" || this == "video/matroska"

private fun String.isHlsUri(): Boolean {
    val clean = substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".m3u8")
}

private fun String.isMkvUri(): Boolean {
    val clean = substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".mkv")
}
