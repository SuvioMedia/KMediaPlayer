package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

/** Best-effort classification of channel-based and object-based spatial audio metadata. */
enum class AudioSpatialFormat {
    NONE,
    MULTICHANNEL,
    DOLBY_ATMOS,
    POSSIBLE_DOLBY_ATMOS,
    OTHER_SPATIAL_AUDIO,
    ;

    /** Whether preserving the encoded stream may retain object-based spatial metadata. */
    val mayCarryObjectBasedMetadata: Boolean
        get() = this == DOLBY_ATMOS || this == POSSIBLE_DOLBY_ATMOS || this == OTHER_SPATIAL_AUDIO
}

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
    val mimeType: String? = null,
    val codec: String? = null,
) {
    val isExternal: Boolean
        get() = !isEmbedded

    /**
     * Classifies only metadata exposed by the active backend. E-AC-3 and TrueHD can carry Atmos but are reported as
     * [AudioSpatialFormat.POSSIBLE_DOLBY_ATMOS] unless JOC/Atmos metadata is identified explicitly.
     */
    val spatialAudioFormat: AudioSpatialFormat
        get() = detectAudioSpatialFormat(mimeType = mimeType, codec = codec, channels = channels)
}

internal fun detectAudioSpatialFormat(
    mimeType: String?,
    codec: String?,
    channels: Int?,
): AudioSpatialFormat {
    val normalizedMimeType = mimeType.orEmpty().trim().lowercase()
    val normalizedCodec = codec.orEmpty().trim().lowercase()
    val descriptors = "$normalizedMimeType $normalizedCodec"
    return when {
        descriptors.contains("atmos") || descriptors.contains("eac3-joc") || descriptors.contains("joc") ->
            AudioSpatialFormat.DOLBY_ATMOS

        POSSIBLE_DOLBY_ATMOS_DESCRIPTORS.any(descriptors::contains) -> AudioSpatialFormat.POSSIBLE_DOLBY_ATMOS
        OTHER_SPATIAL_AUDIO_DESCRIPTORS.any(descriptors::contains) -> AudioSpatialFormat.OTHER_SPATIAL_AUDIO
        channels != null && channels > STEREO_CHANNEL_COUNT -> AudioSpatialFormat.MULTICHANNEL
        else -> AudioSpatialFormat.NONE
    }
}

private val POSSIBLE_DOLBY_ATMOS_DESCRIPTORS =
    listOf("audio/eac3", "audio/e-ac3", "ec-3", "eac3", "true-hd", "truehd", "mlpa", "audio/ac4", "ac-4")
private val OTHER_SPATIAL_AUDIO_DESCRIPTORS =
    listOf("audio/iamf", "iamf", "mpeg-h", "mhm1", "mhm2", "dts:x", "dtsx", "dts-uhd")
private const val STEREO_CHANNEL_COUNT = 2
