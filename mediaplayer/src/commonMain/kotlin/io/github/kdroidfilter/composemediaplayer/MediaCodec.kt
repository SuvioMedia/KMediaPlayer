package io.github.kdroidfilter.composemediaplayer

/**
 * Media track type handled by a [MediaCodec].
 */
enum class MediaCodecType {
    AUDIO,
    VIDEO,
}

/**
 * Common audio and video codecs that can be checked against the current platform backend.
 *
 * Codec support is platform and runtime dependent. Android and web targets query the current
 * runtime where possible, while desktop and iOS expose conservative backend capabilities.
 *
 * @property type Whether the codec belongs to an audio or video track.
 * @property displayName Human-readable codec name.
 * @property mimeTypes Common MIME types used to identify the codec.
 */
enum class MediaCodec(
    val type: MediaCodecType,
    val displayName: String,
    val mimeTypes: Set<String>,
) {
    H264(MediaCodecType.VIDEO, "H.264 / AVC", setOf("video/avc")),
    H265(MediaCodecType.VIDEO, "H.265 / HEVC", setOf("video/hevc")),
    AV1(MediaCodecType.VIDEO, "AV1", setOf("video/av01")),
    VP8(MediaCodecType.VIDEO, "VP8", setOf("video/x-vnd.on2.vp8")),
    VP9(MediaCodecType.VIDEO, "VP9", setOf("video/x-vnd.on2.vp9")),
    MPEG4(MediaCodecType.VIDEO, "MPEG-4 Part 2", setOf("video/mp4v-es")),
    MPEG2(MediaCodecType.VIDEO, "MPEG-2 Video", setOf("video/mpeg2")),
    THEORA(MediaCodecType.VIDEO, "Theora", setOf("video/ogg")),
    H263(MediaCodecType.VIDEO, "H.263", setOf("video/3gpp")),
    WMV(MediaCodecType.VIDEO, "Windows Media Video", setOf("video/x-ms-wmv")),

    AAC(MediaCodecType.AUDIO, "AAC", setOf("audio/mp4a-latm", "audio/aac")),
    MP3(MediaCodecType.AUDIO, "MP3", setOf("audio/mpeg")),
    OPUS(MediaCodecType.AUDIO, "Opus", setOf("audio/opus")),
    VORBIS(MediaCodecType.AUDIO, "Vorbis", setOf("audio/vorbis")),
    FLAC(MediaCodecType.AUDIO, "FLAC", setOf("audio/flac")),
    AC3(MediaCodecType.AUDIO, "AC-3", setOf("audio/ac3")),
    EAC3(MediaCodecType.AUDIO, "E-AC-3", setOf("audio/eac3", "audio/eac3-joc")),
    ALAC(MediaCodecType.AUDIO, "ALAC", setOf("audio/alac")),
    PCM(MediaCodecType.AUDIO, "PCM", setOf("audio/raw", "audio/wav")),
    AMR_NB(MediaCodecType.AUDIO, "AMR-NB", setOf("audio/3gpp", "audio/amr")),
    AMR_WB(MediaCodecType.AUDIO, "AMR-WB", setOf("audio/amr-wb")),
    WMA(MediaCodecType.AUDIO, "Windows Media Audio", setOf("audio/x-ms-wma")),
}

/**
 * Immutable snapshot of codecs reported as supported by the current platform backend.
 */
data class MediaCodecSupport(
    val audioCodecs: Set<MediaCodec>,
    val videoCodecs: Set<MediaCodec>,
) {
    /**
     * All audio and video codecs reported as supported by the current platform backend.
     */
    val allCodecs: Set<MediaCodec>
        get() = audioCodecs + videoCodecs

    /**
     * Returns true when [codec] is present in this support snapshot.
     */
    fun isSupported(codec: MediaCodec): Boolean =
        when (codec.type) {
            MediaCodecType.AUDIO -> codec in audioCodecs
            MediaCodecType.VIDEO -> codec in videoCodecs
        }
}

/**
 * Queries codecs supported by the current platform backend.
 *
 * Query methods are suspend functions because the first runtime query may inspect system decoders
 * or browser media capabilities. Platform implementations cache the result where a runtime query is needed.
 */
object SupportedMediaCodecs {
    /**
     * Queries a snapshot of all audio and video codecs reported as supported.
     */
    suspend fun query(): MediaCodecSupport =
        MediaCodecSupport(
            audioCodecs = queryAudioCodecs(),
            videoCodecs = queryVideoCodecs(),
        )

    /**
     * Queries audio codecs reported as supported.
     */
    suspend fun queryAudioCodecs(): Set<MediaCodec> = platformQuerySupportedAudioCodecs()

    /**
     * Queries video codecs reported as supported.
     */
    suspend fun queryVideoCodecs(): Set<MediaCodec> = platformQuerySupportedVideoCodecs()

    /**
     * Queries whether [codec] is reported as supported.
     */
    suspend fun queryIsSupported(codec: MediaCodec): Boolean =
        when (codec.type) {
            MediaCodecType.AUDIO -> codec in queryAudioCodecs()
            MediaCodecType.VIDEO -> codec in queryVideoCodecs()
        }
}

/**
 * Returns true when this codec is reported as supported by the current platform backend.
 */
suspend fun MediaCodec.isSupported(): Boolean = SupportedMediaCodecs.queryIsSupported(this)

internal expect suspend fun platformQuerySupportedAudioCodecs(): Set<MediaCodec>

internal expect suspend fun platformQuerySupportedVideoCodecs(): Set<MediaCodec>
