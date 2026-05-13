package io.github.kdroidfilter.composemediaplayer.mac

import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.MAC_LIBVLC_AUDIO_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.MAC_LIBVLC_SUBTITLE_TRACK_ID_PREFIX
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import java.util.concurrent.TimeUnit

internal object MacLibVlcMediaProbe {
    fun probe(uri: String): MacLibVlcTrackInfo {
        val ffprobe = MacFfmpegLocator.findFfprobe() ?: return MacLibVlcTrackInfo()
        val process =
            ProcessBuilder(
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "stream=index,codec_type,codec_name,channels,sample_rate,bit_rate:stream_tags=language,title:" +
                    "stream_disposition=default:format=duration",
                "-of",
                "flat",
                uri,
            ).redirectErrorStream(true)
                .start()

        if (!process.waitFor(12, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return MacLibVlcTrackInfo()
        }

        if (process.exitValue() != 0) return MacLibVlcTrackInfo()
        return parse(process.inputStream.bufferedReader().readText())
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

        return MacLibVlcTrackInfo(
            durationSeconds = durationSeconds,
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

    private val STREAM_FIELD_REGEX = Regex("""streams\.stream\.(\d+)\.(.+)""")

    private data class ProbeStream(
        val index: Int,
        val codecType: String?,
        val codecName: String?,
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
