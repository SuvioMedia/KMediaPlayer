package io.github.kdroidfilter.composemediaplayer.subtitle

/**
 * Minimal ASS/SSA parser for platforms that render subtitles through Compose.
 *
 * The parser preserves timing and readable dialogue text. ASS styling, vector
 * drawing, karaoke timing and font attachments are intentionally not rendered
 * here; browser targets still use libass/JASSUB for full styled ASS rendering.
 */
object AssParser {
    private val defaultEventFormat =
        listOf(
            "Layer",
            "Start",
            "End",
            "Style",
            "Name",
            "MarginL",
            "MarginR",
            "MarginV",
            "Effect",
            "Text",
        )

    fun parse(content: String): SubtitleCueList {
        val cues = mutableListOf<SubtitleCue>()
        var inEventsSection = false
        var eventFormat = defaultEventFormat

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trimEnd()
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inEventsSection = trimmed.equals("[Events]", ignoreCase = true)
                continue
            }

            if (!inEventsSection) continue

            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                eventFormat =
                    trimmed
                        .substringAfter(':')
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .ifEmpty { defaultEventFormat }
                continue
            }

            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue

            val fields = splitDialogueFields(trimmed.substringAfter(':'), eventFormat.size)
            val startIndex = eventFormat.indexOfField("Start")
            val endIndex = eventFormat.indexOfField("End")
            val textIndex = eventFormat.indexOfField("Text")

            if (startIndex !in fields.indices || endIndex !in fields.indices || textIndex !in fields.indices) {
                continue
            }

            val startTime = parseAssTime(fields[startIndex]) ?: continue
            val endTime = parseAssTime(fields[endIndex]) ?: continue
            if (endTime <= startTime) continue

            val text = cleanAssText(fields[textIndex])
            if (text.isBlank()) continue

            cues += SubtitleCue(startTime = startTime, endTime = endTime, text = text)
        }

        return SubtitleCueList(cues.sortedBy { it.startTime })
    }

    private fun splitDialogueFields(
        payload: String,
        expectedFieldCount: Int,
    ): List<String> {
        val limit = expectedFieldCount.takeIf { it > 0 } ?: defaultEventFormat.size
        return payload.trimStart().split(',', limit = limit)
    }

    private fun List<String>.indexOfField(name: String): Int = indexOfFirst { it.equals(name, ignoreCase = true) }

    private fun parseAssTime(value: String): Long? {
        val parts = value.trim().split(':')
        if (parts.size != 3) return null

        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val secondsPart = parts[2]
        val seconds = secondsPart.substringBefore('.').substringBefore(',').toLongOrNull() ?: return null
        val fraction =
            secondsPart
                .substringAfter('.', secondsPart.substringAfter(',', ""))
                .takeIf { it.isNotBlank() }
                .orEmpty()
        val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0L

        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun cleanAssText(text: String): String =
        text
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .replace(Regex("\\{[^}]*}"), "")
            .replace(Regex("</?[^>]+>"), "")
            .lines()
            .joinToString("\n") { it.trim() }
            .trim()
}
