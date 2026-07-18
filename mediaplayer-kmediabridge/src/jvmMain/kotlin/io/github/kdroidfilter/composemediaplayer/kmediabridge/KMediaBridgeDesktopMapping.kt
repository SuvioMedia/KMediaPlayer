package io.github.kdroidfilter.composemediaplayer.kmediabridge

import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ContentLightLevelMetadata
import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.Hdr10PlusInfo
import io.github.kdroidfilter.composemediaplayer.MasteringDisplayMetadata
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.shusek.kmediabridge.AudioTrackInfo as BridgeAudioTrackInfo
import io.github.shusek.kmediabridge.ColorMatrix as BridgeColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries as BridgeColorPrimaries
import io.github.shusek.kmediabridge.ColorRange as BridgeColorRange
import io.github.shusek.kmediabridge.ColorTransfer as BridgeColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat as BridgeDynamicRangeFormat
import io.github.shusek.kmediabridge.SubtitleTrackInfo as BridgeSubtitleTrackInfo
import io.github.shusek.kmediabridge.VideoColorInfo as BridgeVideoColorInfo

internal fun BridgeVideoColorInfo.toPlayerVideoColorInfo(): VideoColorInfo =
    VideoColorInfo(
        dynamicRange = dynamicRange.toPlayerDynamicRange(),
        bitDepth = bitDepth,
        primaries = primaries.toPlayerPrimaries(),
        transfer = transfer.toPlayerTransfer(),
        matrix = matrix.toPlayerMatrix(),
        range = range.toPlayerRange(),
        masteringDisplay = masteringDisplay?.let(::toPlayerMasteringDisplay),
        contentLightLevel =
            contentLightLevel?.let { metadata ->
                ContentLightLevelMetadata(
                    maxContentLightLevelNits = metadata.maximumContentLightLevelNits,
                    maxFrameAverageLightLevelNits = metadata.maximumFrameAverageLightLevelNits,
                )
            },
        hdr10Plus = hasHdr10PlusMetadata.takeIf { it }?.let { Hdr10PlusInfo() },
        dolbyVision = dolbyVision?.let(::toPlayerDolbyVision),
    )

private fun BridgeDynamicRangeFormat.toPlayerDynamicRange(): VideoDynamicRange =
    when (this) {
        BridgeDynamicRangeFormat.UNKNOWN -> VideoDynamicRange.UNKNOWN
        BridgeDynamicRangeFormat.SDR -> VideoDynamicRange.SDR
        BridgeDynamicRangeFormat.HDR10 -> VideoDynamicRange.HDR10
        BridgeDynamicRangeFormat.HDR10_PLUS -> VideoDynamicRange.HDR10_PLUS
        BridgeDynamicRangeFormat.HLG -> VideoDynamicRange.HLG
        BridgeDynamicRangeFormat.DOLBY_VISION -> VideoDynamicRange.DOLBY_VISION
    }

private fun BridgeColorPrimaries.toPlayerPrimaries(): VideoColorPrimaries =
    when (this) {
        BridgeColorPrimaries.UNKNOWN -> VideoColorPrimaries.UNKNOWN
        BridgeColorPrimaries.BT709 -> VideoColorPrimaries.BT709
        BridgeColorPrimaries.BT2020 -> VideoColorPrimaries.BT2020
        BridgeColorPrimaries.DISPLAY_P3 -> VideoColorPrimaries.DISPLAY_P3
    }

private fun BridgeColorTransfer.toPlayerTransfer(): VideoColorTransfer =
    when (this) {
        BridgeColorTransfer.UNKNOWN -> VideoColorTransfer.UNKNOWN
        BridgeColorTransfer.BT709 -> VideoColorTransfer.SDR
        BridgeColorTransfer.SRGB -> VideoColorTransfer.SRGB
        BridgeColorTransfer.PQ -> VideoColorTransfer.PQ
        BridgeColorTransfer.HLG -> VideoColorTransfer.HLG
        BridgeColorTransfer.LINEAR -> VideoColorTransfer.LINEAR
    }

private fun BridgeColorMatrix.toPlayerMatrix(): VideoColorMatrix =
    when (this) {
        BridgeColorMatrix.UNKNOWN -> VideoColorMatrix.UNKNOWN
        BridgeColorMatrix.BT709 -> VideoColorMatrix.BT709
        BridgeColorMatrix.BT2020_NCL -> VideoColorMatrix.BT2020_NCL
        BridgeColorMatrix.BT2020_CL -> VideoColorMatrix.BT2020_CL
        BridgeColorMatrix.IDENTITY -> VideoColorMatrix.RGB
    }

private fun BridgeColorRange.toPlayerRange(): VideoColorRange =
    when (this) {
        BridgeColorRange.UNKNOWN -> VideoColorRange.UNKNOWN
        BridgeColorRange.LIMITED -> VideoColorRange.LIMITED
        BridgeColorRange.FULL -> VideoColorRange.FULL
    }

private fun toPlayerMasteringDisplay(
    metadata: io.github.shusek.kmediabridge.MasteringDisplayInfo,
): MasteringDisplayMetadata =
    MasteringDisplayMetadata(
        redX = metadata.red.x.toFloat(),
        redY = metadata.red.y.toFloat(),
        greenX = metadata.green.x.toFloat(),
        greenY = metadata.green.y.toFloat(),
        blueX = metadata.blue.x.toFloat(),
        blueY = metadata.blue.y.toFloat(),
        whiteX = metadata.whitePoint.x.toFloat(),
        whiteY = metadata.whitePoint.y.toFloat(),
        minLuminanceNits = metadata.minimumLuminanceNits.toFloat(),
        maxLuminanceNits = metadata.maximumLuminanceNits.toFloat(),
    )

private fun toPlayerDolbyVision(metadata: io.github.shusek.kmediabridge.DolbyVisionInfo): DolbyVisionInfo =
    DolbyVisionInfo(
        profile = metadata.profile,
        level = metadata.level,
        hasRpu = metadata.hasRpu,
        enhancementLayer =
            if (metadata.hasEnhancementLayer) {
                DolbyVisionEnhancementLayer.UNKNOWN
            } else {
                DolbyVisionEnhancementLayer.NONE
            },
    )

internal fun toAudioTrack(track: BridgeAudioTrackInfo): AudioTrack =
    AudioTrack(
        id = "$BRIDGE_AUDIO_TRACK_ID_PREFIX${track.id}",
        label = track.displayLabel("Audio ${track.id + 1}"),
        language = track.language.orEmpty(),
        channels = track.channels,
        sampleRate = track.sampleRateHz,
        bitrate = track.bitrate,
        isDefault = track.isDefault,
    )

internal fun toSubtitleTrack(track: BridgeSubtitleTrackInfo): SubtitleTrack? {
    val format =
        when (track.codecName.lowercase()) {
            "ass" -> SubtitleFormat.ASS
            "ssa" -> SubtitleFormat.SSA
            "subrip", "srt", "mov_text", "movtext" -> SubtitleFormat.SRT
            "webvtt" -> SubtitleFormat.WEBVTT
            else -> return null
        }
    return SubtitleTrack(
        id = "$BRIDGE_SUBTITLE_TRACK_ID_PREFIX${track.id}",
        label = track.displayLabel("Subtitles ${track.id + 1}"),
        language = track.language.orEmpty(),
        src = "kmediabridge-track:${track.id}",
        format = format,
        isEmbedded = true,
    )
}

private fun BridgeAudioTrackInfo.displayLabel(fallback: String): String =
    title?.takeIf(String::isNotBlank)
        ?: buildTrackLabel(fallback, language, codecName)

private fun BridgeSubtitleTrackInfo.displayLabel(fallback: String): String =
    title?.takeIf(String::isNotBlank)
        ?: buildTrackLabel(fallback, language, codecName)

private fun buildTrackLabel(
    fallback: String,
    language: String?,
    codecName: String,
): String =
    buildString {
        append(fallback)
        language?.takeIf(String::isNotBlank)?.let { append(" ($it)") }
        codecName.takeIf(String::isNotBlank)?.let { append(" / $it") }
    }

private const val BRIDGE_AUDIO_TRACK_ID_PREFIX = "external-ffmpeg:audio:"
private const val BRIDGE_SUBTITLE_TRACK_ID_PREFIX = "external-ffmpeg:subtitle:"
