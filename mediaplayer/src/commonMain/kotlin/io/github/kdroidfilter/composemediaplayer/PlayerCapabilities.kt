package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
data class MediaSourceSpec(
    val uri: String,
    val mimeType: String? = null,
)

@Stable
data class PlayerCapabilities(
    val supportsHls: Boolean = false,
    val supportsMkv: Boolean = false,
    val supportsExternalSubtitles: Boolean = true,
    val supportsAudioTracks: Boolean = false,
    val supportsPiP: Boolean = false,
    val supportsHlsQualitySelection: Boolean = false,
    val supportsPlaybackDiagnostics: Boolean = false,
    val supportedUriSchemes: Set<String> = DEFAULT_URI_SCHEMES,
) {
    fun canPlaySource(
        uri: String,
        mimeType: String? = null,
    ): Boolean = canPlaySource(MediaSourceSpec(uri = uri, mimeType = mimeType))

    fun canPlaySource(source: MediaSourceSpec): Boolean {
        val trimmedUri = source.uri.trim()
        if (trimmedUri.isEmpty()) return false

        val scheme = trimmedUri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme.isNotEmpty() && scheme !in supportedUriSchemes) return false

        val normalizedMimeType = source.mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (normalizedMimeType.isHlsMimeType() || trimmedUri.isHlsUri()) return supportsHls
        if (normalizedMimeType.isMkvMimeType() || trimmedUri.isMkvUri()) return supportsMkv

        return true
    }

    companion object {
        val DEFAULT_URI_SCHEMES = setOf("asset", "blob", "content", "data", "file", "http", "https")
    }
}

private fun String?.isHlsMimeType(): Boolean =
    this == "application/vnd.apple.mpegurl" ||
        this == "application/x-mpegurl" ||
        this == "audio/mpegurl" ||
        this == "audio/x-mpegurl"

private fun String?.isMkvMimeType(): Boolean =
    this == "video/x-matroska" || this == "video/matroska"

private fun String.isHlsUri(): Boolean {
    val clean = substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".m3u8")
}

private fun String.isMkvUri(): Boolean {
    val clean = substringBefore('?').substringBefore('#').lowercase()
    return clean.endsWith(".mkv")
}
