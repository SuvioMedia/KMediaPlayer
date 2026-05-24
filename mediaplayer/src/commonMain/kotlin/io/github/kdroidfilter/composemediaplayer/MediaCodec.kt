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
 * Android queries advertised decoders, so passthrough-only audio support can still depend on the
 * active output device and may not be reported here.
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
    TRUEHD(MediaCodecType.AUDIO, "Dolby TrueHD", setOf("audio/true-hd")),
    DTS(MediaCodecType.AUDIO, "DTS", setOf("audio/vnd.dts")),
    DTS_HD(MediaCodecType.AUDIO, "DTS-HD", setOf("audio/vnd.dts.hd")),
    DTS_EXPRESS(MediaCodecType.AUDIO, "DTS Express", setOf("audio/vnd.dts.hd;profile=lbr")),
    DTS_X(MediaCodecType.AUDIO, "DTS:X", setOf("audio/vnd.dts.uhd;profile=p2")),
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
 * Immutable preflight snapshot of player capabilities and codec support for the current platform.
 */
class MediaSupportSnapshot internal constructor(
    val capabilities: PlayerCapabilities,
    val codecs: MediaCodecSupport,
    private val canPlaySourceQuery: (MediaSourceSpec) -> Boolean,
) {
    val audioCodecs: Set<MediaCodec>
        get() = codecs.audioCodecs

    val videoCodecs: Set<MediaCodec>
        get() = codecs.videoCodecs

    val allCodecs: Set<MediaCodec>
        get() = codecs.allCodecs

    fun isCodecSupported(codec: MediaCodec): Boolean = codecs.isSupported(codec)

    fun canPlaySource(
        uri: String,
        mimeType: String? = null,
    ): Boolean = canPlaySource(MediaSourceSpec(uri = uri, mimeType = mimeType))

    fun canPlaySource(source: MediaSourceSpec): Boolean = canPlaySourceQuery(source)
}

/**
 * Queries preflight media support for the current platform backend.
 *
 * Query methods are suspend functions because codec support may inspect system decoders or browser
 * media capabilities. Platform implementations cache runtime-heavy results where needed.
 */
object MediaSupport {
    /**
     * Queries player capabilities and codec support in one snapshot.
     */
    suspend fun query(): MediaSupportSnapshot =
        MediaSupportSnapshot(
            capabilities = queryCapabilities(),
            codecs = queryCodecs(),
            canPlaySourceQuery = ::platformQueryCanPlaySource,
        )

    /**
     * Queries player/source capabilities without creating a [VideoPlayerState].
     */
    suspend fun queryCapabilities(): PlayerCapabilities = platformPlayerCapabilities()

    /**
     * Queries a snapshot of all audio and video codecs reported as supported.
     */
    suspend fun queryCodecs(): MediaCodecSupport =
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
    suspend fun queryIsCodecSupported(codec: MediaCodec): Boolean =
        when (codec.type) {
            MediaCodecType.AUDIO -> codec in queryAudioCodecs()
            MediaCodecType.VIDEO -> codec in queryVideoCodecs()
        }

    /**
     * Queries whether the current platform can play a source by URI and optional MIME type.
     */
    suspend fun queryCanPlaySource(
        uri: String,
        mimeType: String? = null,
    ): Boolean = queryCanPlaySource(MediaSourceSpec(uri = uri, mimeType = mimeType))

    /**
     * Queries whether the current platform can play a source.
     */
    suspend fun queryCanPlaySource(source: MediaSourceSpec): Boolean = platformQueryCanPlaySource(source)
}

/**
 * Returns true when this codec is reported as supported by the current platform backend.
 */
suspend fun MediaCodec.isSupported(): Boolean = MediaSupport.queryIsCodecSupported(this)

internal expect suspend fun platformQuerySupportedAudioCodecs(): Set<MediaCodec>

internal expect suspend fun platformQuerySupportedVideoCodecs(): Set<MediaCodec>

internal expect fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean
