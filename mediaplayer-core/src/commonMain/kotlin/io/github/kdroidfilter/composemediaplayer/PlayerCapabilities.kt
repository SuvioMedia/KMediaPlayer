package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
data class MediaSourceSpec(
    val uri: String,
    val mimeType: String? = null,
)

@Stable
data class PlayerCapabilities(
    val supportsMkv: Boolean = false,
    val supportsPiP: Boolean = false,
    val decoderColorCapabilities: DecoderColorCapabilities = DecoderColorCapabilities(),
    val displayColorCapabilities: DisplayColorCapabilities = DisplayColorCapabilities(),
    val rendererColorCapabilities: RendererColorCapabilities = RendererColorCapabilities(),
    val colorConversionCapabilities: ColorConversionCapabilities = ColorConversionCapabilities(),
    val supportedUriSchemes: Set<String> = DEFAULT_SUPPORTED_URI_SCHEMES,
    val supportsHls: Boolean = false,
) {
    fun canPlaySource(
        uri: String,
        mimeType: String? = null,
    ): Boolean = canPlaySource(MediaSourceSpec(uri = uri, mimeType = mimeType))

    fun canPlaySource(source: MediaSourceSpec): Boolean {
        val trimmedUri = source.uri.trim()
        if (trimmedUri.isEmpty()) return false

        val scheme = trimmedUri.sourceScheme()
        if (scheme.isNotEmpty() && !supportsUriScheme(scheme)) return false

        val normalizedMimeType =
            source.mimeType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (normalizedMimeType.isHlsMimeType() || trimmedUri.isHlsUri()) return supportsHls
        if (normalizedMimeType.isMkvMimeType() || trimmedUri.isMkvUri()) return supportsMkv

        return true
    }

    private fun supportsUriScheme(scheme: String): Boolean =
        supportedUriSchemes.any { it.trim().equals(scheme, ignoreCase = true) }
}

private val DEFAULT_SUPPORTED_URI_SCHEMES = setOf("asset", "blob", "content", "data", "file", "http", "https")
private const val WINDOWS_DRIVE_PATH_MIN_LENGTH = 3

private fun String.sourceScheme(): String =
    when {
        isWindowsDrivePath() || isWindowsUncPath() -> "file"
        else -> substringBefore(':', missingDelimiterValue = "").lowercase()
    }

private fun String.isWindowsDrivePath(): Boolean =
    length >= WINDOWS_DRIVE_PATH_MIN_LENGTH &&
        this[0].isLetter() &&
        this[1] == ':' &&
        (this[2] == '\\' || this[2] == '/')

private fun String.isWindowsUncPath(): Boolean = startsWith("\\\\")

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
