package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.MAC_LIBVLC_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.MAC_LIBVLC_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import java.util.concurrent.TimeUnit

internal object MacLibVlcMediaProbe {
    fun probe(uri: String): MacLibVlcTrackInfo {
        probeWithBuiltInMatroskaReader(uri).takeIf { it.hasTracks() }?.let { return it }

        val ffprobe = MacFfmpegLocator.findFfprobe() ?: return probeWithBuiltInMatroskaReader(uri)
        val process =
            ProcessBuilder(
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "stream=index,codec_type,codec_name,width,height,channels,sample_rate,bit_rate:stream_tags=language,title:" +
                    "stream_disposition=default:format=duration",
                "-of",
                "flat",
                uri,
            ).redirectErrorStream(true)
                .start()

        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return probeWithBuiltInMatroskaReader(uri)
        }

        if (process.exitValue() != 0) return probeWithBuiltInMatroskaReader(uri)
        return parse(process.inputStream.bufferedReader().readText())
            .takeIf { it.audioStreams.isNotEmpty() || it.subtitleStreams.isNotEmpty() }
            ?: probeWithBuiltInMatroskaReader(uri)
    }

    private fun MacLibVlcTrackInfo.hasTracks(): Boolean =
        audioStreams.isNotEmpty() || subtitleStreams.isNotEmpty()

    private fun probeWithBuiltInMatroskaReader(uri: String): MacLibVlcTrackInfo =
        runCatching {
            MacMatroskaAssExtractor.probe(uri)?.toLibVlcTrackInfo()
        }.getOrNull() ?: MacLibVlcTrackInfo()

    private fun MacMatroskaProbeInfo.toLibVlcTrackInfo(): MacLibVlcTrackInfo {
        val audioTracks = tracks.filter { it.isAudio() }
        val subtitleTracks = tracks.filter { it.isSubtitle() }
        val videoTrack = tracks.firstOrNull { it.isVideo() }

        return MacLibVlcTrackInfo(
            durationSeconds = durationSeconds,
            videoWidth = videoTrack?.videoWidth,
            videoHeight = videoTrack?.videoHeight,
            audioStreams =
                audioTracks.mapIndexed { audioOrdinal, track ->
                    MacLibVlcAudioStream(
                        streamIndex = track.streamIndex,
                        ordinal = audioOrdinal,
                        track =
                            AudioTrack(
                                id = "$MAC_LIBVLC_AUDIO_TRACK_ID_PREFIX${track.streamIndex}",
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
                    MacLibVlcSubtitleStream(
                        streamIndex = track.streamIndex,
                        ordinal = subtitleOrdinal,
                        track =
                            SubtitleTrack(
                                id = "$MAC_LIBVLC_SUBTITLE_TRACK_ID_PREFIX${track.streamIndex}",
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

    private fun parse(output: String): MacLibVlcTrackInfo {
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
                    MacLibVlcAudioStream(
                        streamIndex = stream.index,
                        ordinal = audioOrdinal,
                        track =
                            AudioTrack(
                                id = "$MAC_LIBVLC_AUDIO_TRACK_ID_PREFIX${stream.index}",
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
                    MacLibVlcSubtitleStream(
                        streamIndex = stream.index,
                        ordinal = subtitleOrdinal,
                        track =
                            SubtitleTrack(
                                id = "$MAC_LIBVLC_SUBTITLE_TRACK_ID_PREFIX${stream.index}",
                                label = stream.displayLabel("Subtitles"),
                                language = stream.language,
                                src = "",
                                format = format,
                                isEmbedded = true,
                            ),
                    )
                }

        val videoStream = streams.firstOrNull { it.codecType == "video" }

        return MacLibVlcTrackInfo(
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

    private fun String.toFinitePositiveDoubleOrNull(): Double? =
        toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

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

internal data class MacLibVlcTrackInfo(
    val durationSeconds: Double? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val audioStreams: List<MacLibVlcAudioStream> = emptyList(),
    val subtitleStreams: List<MacLibVlcSubtitleStream> = emptyList(),
)

internal data class MacLibVlcAudioStream(
    val streamIndex: Int,
    val ordinal: Int,
    val track: AudioTrack,
)

internal data class MacLibVlcSubtitleStream(
    val streamIndex: Int,
    val ordinal: Int,
    val track: SubtitleTrack,
)
