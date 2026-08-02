package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MediaSourceSpec

/** A playback request whose string representation never exposes its URI or headers. */
@Stable
public data class DesktopPlaybackRequest(
    public val source: MediaSourceSpec,
    public val requestHeaders: Map<String, String> = emptyMap(),
    public val initialPlayerState: InitialPlayerState = InitialPlayerState.PLAY,
) {
    init {
        require(source.uri.isNotBlank()) { "A desktop playback URI must not be blank." }
        require(requestHeaders.keys.none(String::isBlank)) { "Request-header names must not be blank." }
    }

    override fun toString(): String =
        "DesktopPlaybackRequest(source=<redacted>, mimeType=${source.mimeType ?: "<unknown>"}, " +
            "requestHeaders=<redacted:${requestHeaders.size}>, initialPlayerState=$initialPlayerState)"
}
