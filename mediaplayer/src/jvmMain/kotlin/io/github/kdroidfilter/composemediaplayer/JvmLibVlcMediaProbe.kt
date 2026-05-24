@file:Suppress("MagicNumber", "LoopWithTooManyJumpStatements")

package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.mac.MacMatroskaAssExtractor
import io.github.kdroidfilter.composemediaplayer.mac.MacMatroskaProbeInfo
import io.github.kdroidfilter.composemediaplayer.mac.MacMatroskaTrack
import java.util.concurrent.TimeUnit

internal object JvmLibVlcMediaProbe {
    fun probe(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): JvmLibVlcTrackInfo {
        val headers = requestHeaders.sanitizedRequestHeaders()
        probeWithBuiltInMatroskaReader(uri, headers).takeIf { it.hasTracks() }?.let { return it }

        val ffprobe = ExternalFfmpegLocator.findFfprobe() ?: return probeWithBuiltInMatroskaReader(uri, headers)
        val command =
            mutableListOf(
                ffprobe,
                "-v",
                "error",
            )
        headers.requestHeadersLineString().takeIf { it.isNotBlank() }?.let { headerLines ->
            command += listOf("-headers", headerLines)
        }
        command +=
            listOf(
                "-show_entries",
                "stream=index,codec_type,codec_name,width,height,channels,sample_rate,bit_rate:" +
                    "stream_tags=language,title:" +
                    "stream_disposition=default:format=duration",
                "-of",
                "flat",
                uri,
            )
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return probeWithBuiltInMatroskaReader(uri, headers)
        }

        if (process.exitValue() != 0) return probeWithBuiltInMatroskaReader(uri, headers)
        return parse(process.inputStream.bufferedReader().readText())
            .takeIf { it.audioStreams.isNotEmpty() || it.subtitleStreams.isNotEmpty() }
            ?: probeWithBuiltInMatroskaReader(uri, headers)
    }

    private fun JvmLibVlcTrackInfo.hasTracks(): Boolean = audioStreams.isNotEmpty() || subtitleStreams.isNotEmpty()

    private fun probeWithBuiltInMatroskaReader(
        uri: String,
        requestHeaders: Map<String, String>,
    ): JvmLibVlcTrackInfo =
        runCatching {
            MacMatroskaAssExtractor.probe(uri, requestHeaders)?.toLibVlcTrackInfo()
        }.getOrNull() ?: JvmLibVlcTrackInfo()

    private fun MacMatroskaProbeInfo.toLibVlcTrackInfo(): JvmLibVlcTrackInfo {
        val audioTracks = tracks.filter { it.isAudio() }
        val subtitleTracks = tracks.filter { it.isSubtitle() }
        val videoTrack = tracks.firstOrNull { it.isVideo() }

        return JvmLibVlcTrackInfo(
            durationSeconds = durationSeconds,
            videoWidth = videoTrack?.videoWidth,
            videoHeight = videoTrack?.videoHeight,
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
        )
    }

    private fun parse(output: String): JvmLibVlcTrackInfo {
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
            width = values["width"]?.toIntOrNull(),
            height = values["height"]?.toIntOrNull(),
            channels = values["channels"]?.toIntOrNull(),
            sampleRate = values["sample_rate"]?.toIntOrNull(),
            bitRate = values["bit_rate"]?.toIntOrNull(),
            language = values["tags.language"].orEmpty(),
            title = values["tags.title"].orEmpty(),
            isDefault = values["disposition.default"] == "1",
        )
    }

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
        val width: Int?,
        val height: Int?,
        val channels: Int?,
        val sampleRate: Int?,
        val bitRate: Int?,
        val language: String,
        val title: String,
        val isDefault: Boolean,
    )
}

internal data class JvmLibVlcTrackInfo(
    val durationSeconds: Double? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val audioStreams: List<JvmLibVlcAudioStream> = emptyList(),
    val subtitleStreams: List<JvmLibVlcSubtitleStream> = emptyList(),
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
