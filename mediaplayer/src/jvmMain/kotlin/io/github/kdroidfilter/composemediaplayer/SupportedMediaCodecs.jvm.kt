package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform

internal actual suspend fun platformQuerySupportedAudioCodecs(): Set<MediaCodec> =
    when (CurrentPlatform.os) {
        CurrentPlatform.OS.WINDOWS -> windowsAudioCodecs
        CurrentPlatform.OS.MAC -> appleAudioCodecs
        CurrentPlatform.OS.LINUX -> gstreamerAudioCodecs
    }

internal actual suspend fun platformQuerySupportedVideoCodecs(): Set<MediaCodec> =
    when (CurrentPlatform.os) {
        CurrentPlatform.OS.WINDOWS -> windowsVideoCodecs
        CurrentPlatform.OS.MAC -> appleVideoCodecs
        CurrentPlatform.OS.LINUX -> gstreamerVideoCodecs
    }

private val windowsAudioCodecs =
    setOf(
        MediaCodec.AAC,
        MediaCodec.MP3,
        MediaCodec.FLAC,
        MediaCodec.PCM,
        MediaCodec.WMA,
    )

private val windowsVideoCodecs =
    setOf(
        MediaCodec.H264,
        MediaCodec.MPEG4,
        MediaCodec.H263,
        MediaCodec.WMV,
    )

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

private val gstreamerAudioCodecs =
    setOf(
        MediaCodec.AAC,
        MediaCodec.MP3,
        MediaCodec.OPUS,
        MediaCodec.VORBIS,
        MediaCodec.FLAC,
        MediaCodec.AC3,
        MediaCodec.EAC3,
        MediaCodec.ALAC,
        MediaCodec.PCM,
        MediaCodec.AMR_NB,
        MediaCodec.AMR_WB,
        MediaCodec.WMA,
    )

private val gstreamerVideoCodecs =
    setOf(
        MediaCodec.H264,
        MediaCodec.H265,
        MediaCodec.AV1,
        MediaCodec.VP8,
        MediaCodec.VP9,
        MediaCodec.MPEG4,
        MediaCodec.MPEG2,
        MediaCodec.THEORA,
        MediaCodec.H263,
        MediaCodec.WMV,
    )
