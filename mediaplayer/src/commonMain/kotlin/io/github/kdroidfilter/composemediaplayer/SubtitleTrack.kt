package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable

enum class SubtitleFormat {
    AUTO,
    SRT,
    WEBVTT,
    ASS,
    SSA,
    ;

    val isAssFamily: Boolean
        get() = this == ASS || this == SSA

    companion object {
        fun fromSource(
            src: String,
            label: String = "",
        ): SubtitleFormat =
            sequenceOf(src, label)
                .mapNotNull { it.subtitleExtension() }
                .mapNotNull { extension ->
                    when (extension) {
                        "srt" -> SRT
                        "vtt", "webvtt" -> WEBVTT
                        "ass" -> ASS
                        "ssa" -> SSA
                        else -> null
                    }
                }.firstOrNull() ?: AUTO

        fun fromContent(content: String): SubtitleFormat {
            val trimmed = content.trimStart()
            if (trimmed.startsWith("WEBVTT", ignoreCase = true)) return WEBVTT
            if (trimmed.contains("[Script Info]", ignoreCase = true) ||
                trimmed.contains("[V4+ Styles]", ignoreCase = true)
            ) {
                return ASS
            }
            if (trimmed.contains("[V4 Styles]", ignoreCase = true)) return SSA

            val lines = trimmed.lines()
            val looksLikeSrt =
                lines.size >= 2 &&
                    lines[0].trim().toIntOrNull() != null &&
                    lines[1].contains("-->") &&
                    lines[1].contains(",")
            return if (looksLikeSrt) SRT else AUTO
        }
    }
}

@Stable
data class SubtitleTrack(
    val label: String,
    val language: String,
    val src: String,
    val format: SubtitleFormat = SubtitleFormat.AUTO,
    val id: String = src,
    val isEmbedded: Boolean = false,
    val kind: String = "subtitles",
) {
    val isExternal: Boolean
        get() = !isEmbedded

    fun resolvedFormat(): SubtitleFormat =
        if (format == SubtitleFormat.AUTO) {
            SubtitleFormat.fromSource(src = src, label = label)
        } else {
            format
        }
}

private fun String.subtitleExtension(): String? {
    val candidate =
        substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('\\')
    val extension = candidate.substringAfterLast('.', missingDelimiterValue = "")
    return extension.lowercase().takeIf { it.isNotBlank() && it != candidate }
}
