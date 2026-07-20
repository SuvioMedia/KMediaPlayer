package io.github.kdroidfilter.composemediaplayer

internal enum class WebMediaSourceKind {
    EMPTY,
    LOCAL_BLOB,
    LOCAL_FILE,
    DATA,
    REMOTE_MEDIA,
    OTHER,
    ;

    val isLocal: Boolean
        get() = this == LOCAL_BLOB || this == LOCAL_FILE || this == DATA

    val shouldUseCors: Boolean
        get() = this == REMOTE_MEDIA

    val allowsCorsRetry: Boolean
        get() = shouldUseCors
}

internal fun String.toWebMediaSourceKind(): WebMediaSourceKind {
    val trimmed = trim()
    if (trimmed.isEmpty()) return WebMediaSourceKind.EMPTY

    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("blob:") -> WebMediaSourceKind.LOCAL_BLOB
        lower.startsWith("file:") -> WebMediaSourceKind.LOCAL_FILE
        lower.startsWith("data:") -> WebMediaSourceKind.DATA
        lower.startsWith("http://") || lower.startsWith("https://") -> WebMediaSourceKind.REMOTE_MEDIA
        else -> WebMediaSourceKind.OTHER
    }
}

internal enum class WebAdaptiveStreamingFormat {
    HLS,
    DASH,
    MSS,
}

internal fun String.webAdaptiveStreamingFormatOrNull(): WebAdaptiveStreamingFormat? {
    val cleanUri = substringBefore('?').substringBefore('#').trim().lowercase()
    return when {
        cleanUri.endsWith(".m3u8") -> WebAdaptiveStreamingFormat.HLS
        cleanUri.endsWith(".mpd") -> WebAdaptiveStreamingFormat.DASH
        cleanUri.endsWith(".ism") ||
            cleanUri.endsWith(".isml") ||
            cleanUri.contains(".ism/manifest") ||
            cleanUri.contains(".isml/manifest") -> WebAdaptiveStreamingFormat.MSS
        else -> null
    }
}

internal fun String?.webAdaptiveStreamingMimeFormatOrNull(): WebAdaptiveStreamingFormat? =
    when (this?.substringBefore(';')?.trim()?.lowercase()) {
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
        -> WebAdaptiveStreamingFormat.HLS
        "application/dash+xml" -> WebAdaptiveStreamingFormat.DASH
        "application/vnd.ms-sstr+xml" -> WebAdaptiveStreamingFormat.MSS
        else -> null
    }
