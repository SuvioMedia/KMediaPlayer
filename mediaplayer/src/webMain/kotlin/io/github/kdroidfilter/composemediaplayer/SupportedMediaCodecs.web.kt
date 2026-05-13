@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import kotlin.js.ExperimentalWasmJsInterop

internal actual suspend fun platformQuerySupportedAudioCodecs(): Set<MediaCodec> = webAudioCodecs

internal actual suspend fun platformQuerySupportedVideoCodecs(): Set<MediaCodec> = webVideoCodecs

private val webAudioCodecs: Set<MediaCodec> by lazy {
    codecsOfType(MediaCodecType.AUDIO)
}

private val webVideoCodecs: Set<MediaCodec> by lazy {
    codecsOfType(MediaCodecType.VIDEO)
}

private fun codecsOfType(type: MediaCodecType): Set<MediaCodec> =
    MediaCodec.entries
        .asSequence()
        .filter { it.type == type }
        .filter { codec -> codec.webCanPlayTypes.any(::canPlayType) }
        .toSet()

@Suppress("UNUSED_PARAMETER")
private fun canPlayType(mediaType: String): Boolean =
    js("document.createElement('video').canPlayType(mediaType) !== ''")

private val MediaCodec.webCanPlayTypes: Set<String>
    get() =
        when (this) {
            MediaCodec.H264 ->
                setOf(
                    "video/mp4; codecs=\"avc1.42E01E\"",
                    "video/mp4; codecs=\"avc1.4D401E\"",
                )
            MediaCodec.H265 ->
                setOf(
                    "video/mp4; codecs=\"hvc1.1.6.L93.B0\"",
                    "video/mp4; codecs=\"hev1.1.6.L93.B0\"",
                )
            MediaCodec.AV1 ->
                setOf(
                    "video/mp4; codecs=\"av01.0.05M.08\"",
                    "video/webm; codecs=\"av01.0.05M.08\"",
                )
            MediaCodec.VP8 -> setOf("video/webm; codecs=\"vp8\"")
            MediaCodec.VP9 ->
                setOf(
                    "video/webm; codecs=\"vp9\"",
                    "video/webm; codecs=\"vp09.00.10.08\"",
                )
            MediaCodec.MPEG4 -> setOf("video/mp4; codecs=\"mp4v.20.8\"")
            MediaCodec.MPEG2 -> emptySet()
            MediaCodec.THEORA -> setOf("video/ogg; codecs=\"theora\"")
            MediaCodec.H263 -> emptySet()
            MediaCodec.WMV -> emptySet()
            MediaCodec.AAC ->
                setOf(
                    "audio/mp4; codecs=\"mp4a.40.2\"",
                    "audio/aac",
                )
            MediaCodec.MP3 -> setOf("audio/mpeg")
            MediaCodec.OPUS ->
                setOf(
                    "audio/ogg; codecs=\"opus\"",
                    "audio/webm; codecs=\"opus\"",
                )
            MediaCodec.VORBIS ->
                setOf(
                    "audio/ogg; codecs=\"vorbis\"",
                    "audio/webm; codecs=\"vorbis\"",
                )
            MediaCodec.FLAC ->
                setOf(
                    "audio/flac",
                    "audio/ogg; codecs=\"flac\"",
                )
            MediaCodec.AC3 -> setOf("audio/mp4; codecs=\"ac-3\"")
            MediaCodec.EAC3 -> setOf("audio/mp4; codecs=\"ec-3\"")
            MediaCodec.ALAC -> setOf("audio/mp4; codecs=\"alac\"")
            MediaCodec.PCM ->
                setOf(
                    "audio/wav; codecs=\"1\"",
                    "audio/wave",
                )
            MediaCodec.AMR_NB -> emptySet()
            MediaCodec.AMR_WB -> emptySet()
            MediaCodec.WMA -> emptySet()
        }
