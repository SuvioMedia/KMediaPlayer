package io.github.kdroidfilter.composemediaplayer

internal enum class WebMediaSourceKind {
    EMPTY,
    LOCAL_BLOB,
    LOCAL_FILE,
    DATA,
    REMOTE_HLS,
    REMOTE_MEDIA,
    OTHER,
    ;

    val isLocal: Boolean
        get() = this == LOCAL_BLOB || this == LOCAL_FILE || this == DATA

    val shouldUseCors: Boolean
        get() = this == REMOTE_HLS || this == REMOTE_MEDIA

    val allowsCorsRetry: Boolean
        get() = shouldUseCors

    val allowsHlsController: Boolean
        get() = this == REMOTE_HLS
}

internal fun String.toWebMediaSourceKind(): WebMediaSourceKind {
    val trimmed = trim()
    if (trimmed.isEmpty()) return WebMediaSourceKind.EMPTY

    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("blob:") -> WebMediaSourceKind.LOCAL_BLOB
        lower.startsWith("file:") -> WebMediaSourceKind.LOCAL_FILE
        lower.startsWith("data:") -> WebMediaSourceKind.DATA
        lower.startsWith("http://") || lower.startsWith("https://") ->
            if (trimmed.isHlsSource()) WebMediaSourceKind.REMOTE_HLS else WebMediaSourceKind.REMOTE_MEDIA
        else -> WebMediaSourceKind.OTHER
    }
}
