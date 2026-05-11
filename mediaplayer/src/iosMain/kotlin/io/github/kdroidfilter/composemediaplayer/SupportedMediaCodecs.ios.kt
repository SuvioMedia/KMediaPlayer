package io.github.kdroidfilter.composemediaplayer

internal actual suspend fun platformQuerySupportedAudioCodecs(): Set<MediaCodec> = appleAudioCodecs

internal actual suspend fun platformQuerySupportedVideoCodecs(): Set<MediaCodec> = appleVideoCodecs

private val appleAudioCodecs =
    setOf(
        MediaCodec.AAC,
        MediaCodec.MP3,
        MediaCodec.ALAC,
        MediaCodec.PCM,
    )

private val appleVideoCodecs =
    setOf(
        MediaCodec.H264,
        MediaCodec.H265,
        MediaCodec.MPEG4,
        MediaCodec.H263,
    )
