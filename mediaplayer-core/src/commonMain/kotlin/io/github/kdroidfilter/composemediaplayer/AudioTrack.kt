package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

@Stable
data class AudioTrack(
    val id: String,
    val label: String,
    val language: String = "",
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val bitrate: Int? = null,
    val isDefault: Boolean = false,
    val isEmbedded: Boolean = true,
) {
    val isExternal: Boolean
        get() = !isEmbedded
}
