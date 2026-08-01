@file:Suppress(
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
)

package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.mac.MacMatroskaAssExtractor
import io.github.kdroidfilter.composemediaplayer.mac.MacMatroskaProbeInfo
import io.github.kdroidfilter.composemediaplayer.mac.MacMatroskaTrack
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object JvmLibVlcMediaProbe {
    fun probe(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): JvmLibVlcTrackInfo = probeWithBuiltInMatroskaReader(uri, requestHeaders.sanitizedRequestHeaders())

    internal fun parseFirstVideoFrameProbeOutput(output: String): VideoColorInfo? {
        if (!output.lineSequence().any { it.startsWith(FIRST_FRAME_FLAT_PREFIX) }) return null
        val syntheticStreamOutput =
            buildString {
                appendLine("streams.stream.0.index=0")
                appendLine("streams.stream.0.codec_type=\"video\"")
                output.lineSequence().forEach { line ->
                    if (line.startsWith(FIRST_FRAME_FLAT_PREFIX)) {
                        appendLine(line.replaceFirst(FIRST_FRAME_FLAT_PREFIX, "streams.stream.0."))
                    }
                }
            }
        return parseProbeOutput(syntheticStreamOutput).videoColorInfo
    }

    private fun VideoColorInfo.mergeFrameColorInfo(frame: VideoColorInfo): VideoColorInfo =
        copy(
            dynamicRange =
                when {
                    dynamicRange == VideoDynamicRange.UNKNOWN -> frame.dynamicRange
                    dynamicRange == VideoDynamicRange.HDR10 &&
                        frame.dynamicRange == VideoDynamicRange.HDR10_PLUS -> VideoDynamicRange.HDR10_PLUS
                    else -> dynamicRange
                },
            bitDepth = bitDepth ?: frame.bitDepth,
            primaries = primaries.takeUnless { it == VideoColorPrimaries.UNKNOWN } ?: frame.primaries,
            transfer = transfer.takeUnless { it == VideoColorTransfer.UNKNOWN } ?: frame.transfer,
            matrix = matrix.takeUnless { it == VideoColorMatrix.UNKNOWN } ?: frame.matrix,
            range = range.takeUnless { it == VideoColorRange.UNKNOWN } ?: frame.range,
            masteringDisplay = masteringDisplay ?: frame.masteringDisplay,
            contentLightLevel = contentLightLevel ?: frame.contentLightLevel,
            hdr10Plus = hdr10Plus ?: frame.hdr10Plus,
            dolbyVision = dolbyVision ?: frame.dolbyVision,
        )

    private fun probeWithBuiltInMatroskaReader(
        uri: String,
        requestHeaders: Map<String, String>,
    ): JvmLibVlcTrackInfo {
        val containerInfo =
            runCatching {
                JvmLegacyVideoContainerSupport.probe(uri, requestHeaders)
            }.getOrNull()
                ?: runCatching {
                    MacMatroskaAssExtractor.probe(uri, requestHeaders)?.toLibVlcTrackInfo()
                }.getOrNull()
                ?: JvmLibVlcTrackInfo()
        val fallback =
            runCatching {
                JvmMediaChapterProbe.probe(uri, requestHeaders)
            }.getOrNull() ?: return containerInfo

        val durationSeconds =
            containerInfo.durationSeconds
                ?: fallback.durationMs?.div(1_000.0)
        if (fallback.rows.isEmpty()) {
            return containerInfo.copy(durationSeconds = durationSeconds)
        }
        val fallbackChapters =
            normalizeMediaChapters(
                rows = fallback.rows,
                mediaDuration =
                    durationSeconds
                        ?.takeIf { it.isFinite() && it > 0.0 }
                        ?.seconds ?: Duration.ZERO,
            )
        return containerInfo.copy(
            durationSeconds = durationSeconds,
            chapters = containerInfo.chapters.ifEmpty { fallbackChapters },
        )
    }

    private fun MacMatroskaProbeInfo.toLibVlcTrackInfo(): JvmLibVlcTrackInfo {
        val audioTracks = tracks.filter { it.isAudio() }
        val subtitleTracks = tracks.filter { it.isSubtitle() }
        val videoTrack = tracks.firstOrNull { it.isVideo() }

        return JvmLibVlcTrackInfo(
            durationSeconds = durationSeconds,
            videoWidth = videoTrack?.videoWidth,
            videoHeight = videoTrack?.videoHeight,
            videoCodecName = videoTrack?.codecId?.takeIf(String::isNotBlank),
            audioStreams =
                audioTracks.mapIndexed { audioOrdinal, track ->
                    JvmLibVlcAudioStream(
                        streamIndex = track.streamIndex,
                        ordinal = audioOrdinal,
                        track =
                            AudioTrack(
                                id = "$LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX${track.streamIndex}",
                                label = track.displayLabel("Audio ${audioOrdinal + 1}"),
                                language = track.language,
                                channels = track.audioChannels,
                                sampleRate = track.audioSampleRate,
                                isDefault = track.isDefault,
                            ),
                    )
                },
            subtitleStreams =
                subtitleTracks.mapIndexedNotNull { subtitleOrdinal, track ->
                    val format = subtitleFormatForMatroskaTrack(track) ?: return@mapIndexedNotNull null
                    JvmLibVlcSubtitleStream(
                        streamIndex = track.streamIndex,
                        ordinal = subtitleOrdinal,
                        track =
                            SubtitleTrack(
                                id = "$LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX${track.streamIndex}",
                                label = track.displayLabel("Subtitles ${subtitleOrdinal + 1}"),
                                language = track.language,
                                src = "matroska-track:${track.trackNumber}",
                                format = format,
                                isEmbedded = true,
                            ),
                    )
                },
            chapters =
                normalizeMediaChapters(
                    rows =
                        chapters.map { chapter ->
                            RawMediaChapter(
                                startMs = chapter.startMs,
                                endMs = chapter.endMs,
                                title = chapter.title,
                                language = chapter.language,
                                isHidden = chapter.isHidden,
                            )
                        },
                    mediaDuration = durationSeconds?.seconds ?: Duration.ZERO,
                ),
        )
    }

    internal fun parseProbeOutput(output: String): JvmLibVlcTrackInfo {
        val streamValues = linkedMapOf<Int, MutableMap<String, String>>()
        var durationSeconds: Double? = null

        for (line in output.lineSequence()) {
            val key = line.substringBefore('=', missingDelimiterValue = "").trim()
            val value = cleanProbeValue(line.substringAfter('=', missingDelimiterValue = "").trim())
            if (key == "format.duration") {
                durationSeconds = value.toFinitePositiveDoubleOrNull()
                continue
            }

            val match = STREAM_FIELD_REGEX.matchEntire(key) ?: continue
            val streamOrdinal = match.groupValues[1].toIntOrNull() ?: continue
            val field = match.groupValues[2]
            streamValues.getOrPut(streamOrdinal) { linkedMapOf() }[field] = value
        }

        val streams =
            streamValues.values
                .mapNotNull(::probeStreamFromValues)
                .sortedBy { it.index }

        val audioStreams =
            streams
                .filter { it.codecType == "audio" }
                .mapIndexed { audioOrdinal, stream ->
                    JvmLibVlcAudioStream(
                        streamIndex = stream.index,
                        ordinal = audioOrdinal,
                        track =
                            AudioTrack(
                                id = "$LIBVLC_CANVAS_AUDIO_TRACK_ID_PREFIX${stream.index}",
                                label = stream.displayLabel("Audio ${audioOrdinal + 1}"),
                                language = stream.language,
                                channels = stream.channels,
                                sampleRate = stream.sampleRate,
                                bitrate = stream.bitRate,
                                isDefault = stream.isDefault,
                            ),
                    )
                }

        val subtitleStreams =
            streams
                .filter { it.codecType == "subtitle" }
                .mapIndexedNotNull { subtitleOrdinal, stream ->
                    val format = subtitleFormatForCodec(stream.codecName) ?: return@mapIndexedNotNull null
                    JvmLibVlcSubtitleStream(
                        streamIndex = stream.index,
                        ordinal = subtitleOrdinal,
                        track =
                            SubtitleTrack(
                                id = "$LIBVLC_CANVAS_SUBTITLE_TRACK_ID_PREFIX${stream.index}",
                                label = stream.displayLabel("Subtitles"),
                                language = stream.language,
                                src = "",
                                format = format,
                                isEmbedded = true,
                            ),
                    )
                }

        val videoStream = streams.firstOrNull { it.codecType == "video" }

        return JvmLibVlcTrackInfo(
            durationSeconds = durationSeconds,
            videoWidth = videoStream?.width,
            videoHeight = videoStream?.height,
            videoCodecName = videoStream?.codecName,
            videoColorInfo = videoStream?.toVideoColorInfo() ?: VideoColorInfo(),
            audioStreams = audioStreams,
            subtitleStreams = subtitleStreams,
        )
    }

    private fun probeStreamFromValues(values: Map<String, String>): ProbeStream? {
        val index = values["index"]?.toIntOrNull() ?: return null
        return ProbeStream(
            index = index,
            codecType = values["codec_type"]?.lowercase(),
            codecName = values["codec_name"]?.lowercase(),
            codecTag = values["codec_tag_string"]?.lowercase(),
            profile = values["profile"],
            pixelFormat = values["pix_fmt"]?.lowercase(),
            bitsPerRawSample = values["bits_per_raw_sample"]?.toIntOrNull(),
            width = values["width"]?.toIntOrNull(),
            height = values["height"]?.toIntOrNull(),
            channels = values["channels"]?.toIntOrNull(),
            sampleRate = values["sample_rate"]?.toIntOrNull(),
            bitRate = values["bit_rate"]?.toIntOrNull(),
            language = values["tags.language"].orEmpty(),
            title = values["tags.title"].orEmpty(),
            isDefault = values["disposition.default"] == "1",
            colorRange = values["color_range"]?.lowercase(),
            colorSpace = values["color_space"]?.lowercase(),
            colorTransfer = values["color_transfer"]?.lowercase(),
            colorPrimaries = values["color_primaries"]?.lowercase(),
            rawValues = values,
        )
    }

    private fun ProbeStream.toVideoColorInfo(): VideoColorInfo {
        val sideDataTypes =
            rawValues
                .filterKeys { it.contains("side_data", ignoreCase = true) && it.endsWith("side_data_type") }
                .values
        val hasDolbyVision =
            codecTag.isDolbyVisionCodecTag() ||
                profile.orEmpty().contains("dolby vision", ignoreCase = true) ||
                sideDataTypes.any { it.contains("DOVI", ignoreCase = true) || it.contains("Dolby Vision", true) }
        val hasHdr10Plus =
            sideDataTypes.any {
                it.contains("SMPTE2094-40", ignoreCase = true) || it.contains("HDR10+", ignoreCase = true)
            }
        val transfer =
            when (colorTransfer) {
                "smpte2084", "smpte-st-2084" -> VideoColorTransfer.PQ
                "arib-std-b67", "hlg" -> VideoColorTransfer.HLG
                "iec61966-2-1", "srgb" -> VideoColorTransfer.SRGB
                "bt709", "bt470bg", "gamma22", "gamma28", "smpte170m" -> VideoColorTransfer.SDR
                "linear" -> VideoColorTransfer.LINEAR
                else -> VideoColorTransfer.UNKNOWN
            }
        val dynamicRange =
            when {
                hasDolbyVision -> VideoDynamicRange.DOLBY_VISION
                hasHdr10Plus -> VideoDynamicRange.HDR10_PLUS
                transfer == VideoColorTransfer.PQ -> VideoDynamicRange.HDR10
                transfer == VideoColorTransfer.HLG -> VideoDynamicRange.HLG
                transfer == VideoColorTransfer.SDR || transfer == VideoColorTransfer.SRGB -> VideoDynamicRange.SDR
                else -> VideoDynamicRange.UNKNOWN
            }
        val dvProfile = rawValues.fieldEndingIn("dv_profile")?.toIntOrNull()
        val enhancementPresent = rawValues.fieldEndingIn("el_present_flag") == "1"
        val baseLayerPresent = rawValues.fieldEndingIn("bl_present_flag") == "1"
        val compatibilityId = rawValues.fieldEndingIn("dv_bl_signal_compatibility_id")?.toIntOrNull()

        return VideoColorInfo(
            dynamicRange = dynamicRange,
            bitDepth = bitsPerRawSample?.takeIf { it > 0 } ?: pixelFormat.inferredBitDepth(),
            primaries =
                when (colorPrimaries) {
                    "bt2020" -> VideoColorPrimaries.BT2020
                    "bt709" -> VideoColorPrimaries.BT709
                    "smpte432", "display-p3" -> VideoColorPrimaries.DISPLAY_P3
                    "smpte170m" -> VideoColorPrimaries.BT601_525
                    "bt470bg" -> VideoColorPrimaries.BT601_625
                    else -> VideoColorPrimaries.UNKNOWN
                },
            transfer = transfer,
            matrix =
                when (colorSpace) {
                    "bt2020nc", "bt2020_ncl" -> VideoColorMatrix.BT2020_NCL
                    "bt2020c", "bt2020_cl" -> VideoColorMatrix.BT2020_CL
                    "bt709" -> VideoColorMatrix.BT709
                    "smpte170m", "bt470bg" -> VideoColorMatrix.BT601
                    "ictcp" -> VideoColorMatrix.ICTCP
                    "gbr", "rgb" -> VideoColorMatrix.RGB
                    else -> VideoColorMatrix.UNKNOWN
                },
            range =
                when (colorRange) {
                    "tv", "mpeg", "limited" -> VideoColorRange.LIMITED
                    "pc", "jpeg", "full" -> VideoColorRange.FULL
                    else -> VideoColorRange.UNKNOWN
                },
            masteringDisplay = rawValues.masteringDisplayOrNull(),
            contentLightLevel = rawValues.contentLightLevelOrNull(),
            hdr10Plus = Hdr10PlusInfo().takeIf { hasHdr10Plus },
            dolbyVision =
                DolbyVisionInfo(
                    profile = dvProfile,
                    level = rawValues.fieldEndingIn("dv_level")?.toIntOrNull(),
                    hasRpu = rawValues.fieldEndingIn("rpu_present_flag") == "1",
                    enhancementLayer =
                        when {
                            !enhancementPresent -> DolbyVisionEnhancementLayer.NONE
                            dvProfile == 7 -> DolbyVisionEnhancementLayer.UNKNOWN
                            else -> DolbyVisionEnhancementLayer.UNKNOWN
                        },
                    hasHdr10CompatibleBaseLayer =
                        baseLayerPresent &&
                            (
                                dvProfile == DOLBY_VISION_PROFILE_7 ||
                                    compatibilityId == HDR10_DOVI_COMPATIBILITY_ID
                            ),
                ).takeIf { hasDolbyVision },
        )
    }

    private fun Map<String, String>.masteringDisplayOrNull(): MasteringDisplayMetadata? {
        val redX = fieldEndingIn("red_x").probeRationalOrNull() ?: return null
        val redY = fieldEndingIn("red_y").probeRationalOrNull() ?: return null
        val greenX = fieldEndingIn("green_x").probeRationalOrNull() ?: return null
        val greenY = fieldEndingIn("green_y").probeRationalOrNull() ?: return null
        val blueX = fieldEndingIn("blue_x").probeRationalOrNull() ?: return null
        val blueY = fieldEndingIn("blue_y").probeRationalOrNull() ?: return null
        val whiteX = fieldEndingIn("white_point_x").probeRationalOrNull() ?: return null
        val whiteY = fieldEndingIn("white_point_y").probeRationalOrNull() ?: return null
        val minLuminance = fieldEndingIn("min_luminance").probeRationalOrNull() ?: return null
        val maxLuminance = fieldEndingIn("max_luminance").probeRationalOrNull() ?: return null
        return MasteringDisplayMetadata(
            redX = redX.toFloat(),
            redY = redY.toFloat(),
            greenX = greenX.toFloat(),
            greenY = greenY.toFloat(),
            blueX = blueX.toFloat(),
            blueY = blueY.toFloat(),
            whiteX = whiteX.toFloat(),
            whiteY = whiteY.toFloat(),
            minLuminanceNits = minLuminance.toFloat(),
            maxLuminanceNits = maxLuminance.toFloat(),
        )
    }

    private fun Map<String, String>.contentLightLevelOrNull(): ContentLightLevelMetadata? {
        val maxCll = fieldEndingIn("max_content")?.toIntOrNull()?.takeIf { it > 0 }
        val maxFall = fieldEndingIn("max_average")?.toIntOrNull()?.takeIf { it > 0 }
        return ContentLightLevelMetadata(maxCll, maxFall).takeIf { maxCll != null || maxFall != null }
    }

    private fun Map<String, String>.fieldEndingIn(name: String): String? =
        entries.firstOrNull { (key, _) -> key == name || key.endsWith(".$name") }?.value?.takeIf(String::isNotBlank)

    private fun String?.probeRationalOrNull(): Double? {
        val value = this ?: return null
        val numerator = value.substringBefore('/').toDoubleOrNull() ?: return null
        val denominator = value.substringAfter('/', "1").toDoubleOrNull() ?: return null
        if (denominator == 0.0) return null
        return (numerator / denominator).takeIf { it.isFinite() }
    }

    private fun String?.inferredBitDepth(): Int? {
        val format = this ?: return null
        return PIXEL_FORMAT_BIT_DEPTH
            .find(format)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: when {
                format.startsWith("p010") -> 10
                format.startsWith("p012") -> 12
                format.startsWith("p016") -> 16
                format.startsWith("yuv") || format.startsWith("nv12") -> 8
                else -> null
            }
    }

    private fun String?.isDolbyVisionCodecTag(): Boolean =
        this?.let { tag -> DOLBY_VISION_CODEC_TAGS.any(tag::startsWith) } == true

    private fun ProbeStream.displayLabel(fallbackBase: String): String =
        title.takeIf { it.isNotBlank() }
            ?: buildString {
                append(fallbackBase)
                if (language.isNotBlank()) append(" ($language)")
                codecName?.takeIf { it.isNotBlank() }?.let { append(" / $it") }
            }

    private fun cleanProbeValue(value: String): String {
        if (value == "N/A") return ""
        return value
            .removeSurrounding("\"")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun String.toFinitePositiveDoubleOrNull(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    private fun subtitleFormatForCodec(codecName: String?): SubtitleFormat? =
        when (codecName?.lowercase()) {
            "ass" -> SubtitleFormat.ASS
            "ssa" -> SubtitleFormat.SSA
            "subrip" -> SubtitleFormat.SRT
            "webvtt" -> SubtitleFormat.WEBVTT
            else -> null
        }

    private fun subtitleFormatForMatroskaTrack(track: MacMatroskaTrack): SubtitleFormat? {
        val codec = track.codecId.uppercase()
        return when {
            codec.contains("ASS") -> SubtitleFormat.ASS
            codec.contains("SSA") -> SubtitleFormat.SSA
            codec.contains("UTF8") || codec.contains("SRT") -> SubtitleFormat.SRT
            codec.contains("WEBVTT") -> SubtitleFormat.WEBVTT
            else -> null
        }
    }

    private fun MacMatroskaTrack.displayLabel(fallbackBase: String): String =
        name.takeIf { it.isNotBlank() }
            ?: buildString {
                append(fallbackBase)
                if (language.isNotBlank()) append(" ($language)")
                codecId.takeIf { it.isNotBlank() }?.let { append(" / $it") }
            }

    private val STREAM_FIELD_REGEX = Regex("""streams\.stream\.(\d+)\.(.+)""")

    private data class ProbeStream(
        val index: Int,
        val codecType: String?,
        val codecName: String?,
        val codecTag: String?,
        val profile: String?,
        val pixelFormat: String?,
        val bitsPerRawSample: Int?,
        val width: Int?,
        val height: Int?,
        val channels: Int?,
        val sampleRate: Int?,
        val bitRate: Int?,
        val language: String,
        val title: String,
        val isDefault: Boolean,
        val colorRange: String?,
        val colorSpace: String?,
        val colorTransfer: String?,
        val colorPrimaries: String?,
        val rawValues: Map<String, String>,
    )

    private val DOLBY_VISION_CODEC_TAGS = setOf("dvh1", "dvhe", "dva1", "dvav")
    private const val DOLBY_VISION_PROFILE_7 = 7
    private const val HDR10_DOVI_COMPATIBILITY_ID = 1
    private const val FIRST_FRAME_FLAT_PREFIX = "frames.frame.0."
    private val PIXEL_FORMAT_BIT_DEPTH = Regex("(?:p|yuv\\d*p)(10|12|14|16)(?:le|be)?")
}

internal data class JvmLibVlcTrackInfo(
    val durationSeconds: Double? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoCodecName: String? = null,
    val videoColorInfo: VideoColorInfo = VideoColorInfo(),
    val audioStreams: List<JvmLibVlcAudioStream> = emptyList(),
    val subtitleStreams: List<JvmLibVlcSubtitleStream> = emptyList(),
    val chapters: List<MediaChapter> = emptyList(),
)

internal data class JvmLibVlcAudioStream(
    val streamIndex: Int,
    val ordinal: Int,
    val track: AudioTrack,
)

internal data class JvmLibVlcSubtitleStream(
    val streamIndex: Int,
    val ordinal: Int,
    val track: SubtitleTrack,
)
