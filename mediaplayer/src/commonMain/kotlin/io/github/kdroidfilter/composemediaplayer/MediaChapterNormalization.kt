package io.github.kdroidfilter.composemediaplayer

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class RawMediaChapter(
    val startMs: Long,
    val endMs: Long? = null,
    val title: String? = null,
    val language: String? = null,
    val isHidden: Boolean = false,
)

internal data class MediaChapterLabel(
    val text: String,
    val language: String? = null,
)

/**
 * Converts backend chapter rows to one deterministic player-timeline snapshot.
 *
 * Invalid rows are ignored, explicit ends are clipped to the media duration, and absent ends are
 * inferred from the next distinct start or the media duration. The sort is stable so a backend's
 * declared ordering remains the final tie-breaker for overlapping entries.
 */
internal fun normalizeMediaChapters(
    rows: List<RawMediaChapter>,
    mediaDuration: Duration,
): List<MediaChapter> {
    val durationMs =
        mediaDuration
            .takeIf { it.isFinite() && it > Duration.ZERO }
            ?.inWholeMilliseconds
    val validated =
        rows
            .mapIndexedNotNull { sourceIndex, row ->
                if (row.startMs < 0L) return@mapIndexedNotNull null
                if (durationMs != null && row.startMs >= durationMs) return@mapIndexedNotNull null

                val explicitEnd =
                    row.endMs?.let { end ->
                        if (end <= row.startMs) return@mapIndexedNotNull null
                        durationMs?.let(end::coerceAtMost) ?: end
                    }
                if (explicitEnd != null && explicitEnd <= row.startMs) return@mapIndexedNotNull null

                IndexedRawChapter(
                    sourceIndex = sourceIndex,
                    startMs = row.startMs,
                    endMs = explicitEnd,
                    title = row.title?.trim()?.takeIf(String::isNotEmpty),
                    language = row.language?.trim()?.takeIf(String::isNotEmpty),
                    isHidden = row.isHidden,
                )
            }.distinctBy { chapter ->
                ChapterIdentity(
                    startMs = chapter.startMs,
                    endMs = chapter.endMs,
                    title = chapter.title,
                    language = chapter.language,
                    isHidden = chapter.isHidden,
                )
            }.sortedWith(compareBy<IndexedRawChapter> { it.startMs }.thenBy { it.sourceIndex })

    return validated.mapIndexedNotNull { index, chapter ->
        val nextStart =
            validated
                .asSequence()
                .drop(index + 1)
                .map(IndexedRawChapter::startMs)
                .firstOrNull { it > chapter.startMs }
        val effectiveEnd = chapter.endMs ?: nextStart ?: durationMs
        if (effectiveEnd != null && effectiveEnd <= chapter.startMs) return@mapIndexedNotNull null

        MediaChapter(
            start = chapter.startMs.milliseconds,
            end = effectiveEnd?.milliseconds,
            title = chapter.title,
            language = chapter.language,
            isHidden = chapter.isHidden,
        )
    }
}

internal fun selectPreferredChapterLabel(
    labels: List<MediaChapterLabel>,
    preferredLanguages: List<String>,
): MediaChapterLabel? {
    val usable = labels.filter { it.text.isNotBlank() }
    if (usable.isEmpty()) return null

    preferredLanguages
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { preferred ->
            usable.firstOrNull { label -> label.language.matchesLanguage(preferred, exact = true) }?.let { return it }
        }
    preferredLanguages
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { preferred ->
            usable.firstOrNull { label -> label.language.matchesLanguage(preferred, exact = false) }?.let { return it }
        }

    return usable.firstOrNull { it.language.isNullOrBlank() || it.language.equals("und", ignoreCase = true) }
        ?: usable.first()
}

private fun String?.matchesLanguage(
    preferred: String,
    exact: Boolean,
): Boolean {
    val candidate = this?.trim()?.takeIf(String::isNotEmpty) ?: return false
    if (candidate.equals(preferred, ignoreCase = true)) return true
    if (exact) return false
    return candidate.substringBefore('-').equals(preferred.substringBefore('-'), ignoreCase = true)
}

private data class IndexedRawChapter(
    val sourceIndex: Int,
    val startMs: Long,
    val endMs: Long?,
    val title: String?,
    val language: String?,
    val isHidden: Boolean,
)

private data class ChapterIdentity(
    val startMs: Long,
    val endMs: Long?,
    val title: String?,
    val language: String?,
    val isHidden: Boolean,
)
