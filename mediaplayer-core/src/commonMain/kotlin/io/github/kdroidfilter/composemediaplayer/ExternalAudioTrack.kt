package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

/**
 * An app-provided audio track that is aligned to the current content timeline.
 *
 * The source and request-header values are deliberately omitted from [toString] because either may contain
 * short-lived playback credentials. External audio is session-bound: platform implementations clear it when a new
 * primary source is opened or the current source is released.
 */
@Stable
data class ExternalAudioTrack(
    val id: String,
    val label: String,
    val source: MediaSourceSpec,
    val language: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val isDefault: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "External audio track id must not be blank." }
        require(label.isNotBlank()) { "External audio track label must not be blank." }
        require(source.uri.isNotBlank()) { "External audio track source URI must not be blank." }
        require(channels == null || channels > 0) { "External audio channel count must be positive." }
        require(sampleRate == null || sampleRate > 0) { "External audio sample rate must be positive." }
        require(bitrate == null || bitrate >= 0) { "External audio bitrate must not be negative." }
    }

    fun asAudioTrack(): AudioTrack =
        AudioTrack(
            id = id,
            label = label,
            language = language,
            channels = channels,
            sampleRate = sampleRate,
            bitrate = bitrate,
            isDefault = isDefault,
            isEmbedded = false,
        )

    override fun toString(): String =
        "ExternalAudioTrack(id=$id, label=$label, language=$language, " +
            "source=<redacted>, requestHeaderCount=${requestHeaders.size}, channels=$channels, " +
            "sampleRate=$sampleRate, bitrate=$bitrate, isDefault=$isDefault)"
}
