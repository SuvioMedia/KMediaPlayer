package io.github.kdroidfilter.composemediaplayer.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Parser for WebVTT subtitle files.
 */
object WebVttParser {
    private const val WEBVTT_HEADER = "WEBVTT"
    private const val BYTE_ORDER_MARK = '\uFEFF'
    private const val NOTE_BLOCK = "NOTE"
    private const val STYLE_BLOCK = "STYLE"
    private const val REGION_BLOCK = "REGION"
    private const val TIMESTAMP_PATTERN = "(?:\\d{2,}:)?\\d{2}:\\d{2}\\.\\d{3}"
    private const val MAX_MINUTE_OR_SECOND = 59L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val MILLIS_PER_SECOND = 1_000L

    private val cueTimingPattern =
        Regex(
            "^\\s*($TIMESTAMP_PATTERN)\\s*-->\\s*($TIMESTAMP_PATTERN)(?:[ \\t]+.*)?$",
        )
    private val timestampPattern =
        Regex("^(?:(\\d{2,}):)?(\\d{2}):(\\d{2})\\.(\\d{3})$")

    /**
     * Parses a WebVTT file content into a SubtitleCueList.
     *
     * @param content The WebVTT file content as a string
     * @return A SubtitleCueList containing the parsed subtitle cues
     */
    fun parse(content: String): SubtitleCueList {
        val lines = content.removePrefix(BYTE_ORDER_MARK.toString()).lines()
        val header = lines.firstOrNull()?.trimEnd() ?: return SubtitleCueList()
        if (!header.isValidWebVttHeader()) return SubtitleCueList()

        val cues = mutableListOf<SubtitleCue>()
        var lineIndex = 1

        // The header may contain metadata. Cue and metadata blocks begin after its blank separator.
        while (lineIndex < lines.size && lines[lineIndex].isNotBlank()) lineIndex++

        while (lineIndex < lines.size) {
            while (lineIndex < lines.size && lines[lineIndex].isBlank()) lineIndex++
            if (lineIndex >= lines.size) break

            val blockStart = lineIndex
            while (lineIndex < lines.size && lines[lineIndex].isNotBlank()) lineIndex++
            parseBlock(lines.subList(blockStart, lineIndex))?.let(cues::add)
        }

        return SubtitleCueList(cues)
    }

    @Suppress("ReturnCount")
    private fun parseBlock(block: List<String>): SubtitleCue? {
        val firstLine = block.firstOrNull()?.trim() ?: return null
        if (firstLine.isMetadataBlockStart()) return null

        val timingIndex =
            when {
                cueTimingPattern.matches(firstLine) -> 0
                block.size > 1 && cueTimingPattern.matches(block[1]) -> 1
                else -> return null
            }
        val timingMatch = cueTimingPattern.matchEntire(block[timingIndex]) ?: return null
        val startTime = parseTimeToMillis(timingMatch.groupValues[1]) ?: return null
        val endTime = parseTimeToMillis(timingMatch.groupValues[2]) ?: return null
        if (endTime <= startTime) return null

        val text = block.drop(timingIndex + 1).joinToString("\n").trim()
        if (text.isEmpty()) return null
        return SubtitleCue(startTime = startTime, endTime = endTime, text = text)
    }

    private fun String.isValidWebVttHeader(): Boolean =
        this == WEBVTT_HEADER ||
            (startsWith(WEBVTT_HEADER) && getOrNull(WEBVTT_HEADER.length) in setOf(' ', '\t'))

    private fun String.isMetadataBlockStart(): Boolean =
        this == STYLE_BLOCK ||
            this == REGION_BLOCK ||
            this == NOTE_BLOCK ||
            startsWith("$NOTE_BLOCK ") ||
            startsWith("$NOTE_BLOCK\t")

    /**
     * Parses a time string in the format "00:00:00.000" or "00:00.000" to milliseconds.
     *
     * @param timeStr The time string to parse
     * @return The time in milliseconds
     */
    private fun parseTimeToMillis(timeStr: String): Long? {
        val match = timestampPattern.matchEntire(timeStr) ?: return null
        val hours = match.groupValues[1].takeIf(String::isNotEmpty)?.toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millis = match.groupValues[4].toLongOrNull() ?: return null
        if (minutes > MAX_MINUTE_OR_SECOND || seconds > MAX_MINUTE_OR_SECOND) return null
        val totalSeconds =
            hours * MINUTES_PER_HOUR * SECONDS_PER_MINUTE +
                minutes * SECONDS_PER_MINUTE +
                seconds
        return totalSeconds * MILLIS_PER_SECOND + millis
    }

    /**
     * Loads and parses a WebVTT file from a URL.
     *
     * @param url The URL of the WebVTT file
     * @return A SubtitleCueList containing the parsed subtitle cues
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun loadFromUrl(url: String): SubtitleCueList =
        withContext(Dispatchers.Default) {
            try {
                // Use the platform-specific loadSubtitleContent function to fetch the content
                val content = loadSubtitleContent(url)
                parse(content)
            } catch (e: Exception) {
                SubtitleCueList() // Return empty list on error
            }
        }
}
