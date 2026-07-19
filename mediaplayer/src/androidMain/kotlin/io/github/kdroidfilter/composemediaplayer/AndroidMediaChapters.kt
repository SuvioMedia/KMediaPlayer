@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.Chapter
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.ChapterTocFrame

/**
 * Reads Media3's generic chapter metadata without depending on a container-specific frame type.
 *
 * Track metadata is relative to the current period. Public chapter positions are relative to the
 * current player window, so every row is translated by [Timeline.Period.positionInWindowUs].
 */
internal fun Player.media3ChapterRows(): List<RawMediaChapter> {
    val periodOffsetMs = currentPeriodPositionInWindowMs()
    val selectedRows =
        currentTracks.chapterRows(
            periodOffsetMs = periodOffsetMs,
            selectedOnly = true,
        )
    return if (selectedRows.isNotEmpty()) {
        selectedRows
    } else {
        currentTracks.chapterRows(
            periodOffsetMs = periodOffsetMs,
            selectedOnly = false,
        )
    }
}

internal fun Player.media3ChapterRows(metadata: Metadata): List<RawMediaChapter> =
    metadata.media3ChapterRows(currentPeriodPositionInWindowMs())

private fun Player.currentPeriodPositionInWindowMs(): Long {
    val timeline = currentTimeline
    val periodIndex = currentPeriodIndex
    if (timeline.isEmpty || periodIndex !in 0 until timeline.periodCount) return 0L
    return runCatching {
        timeline.getPeriod(periodIndex, Timeline.Period()).positionInWindowUs / MICROSECONDS_PER_MILLISECOND
    }.getOrDefault(0L)
}

private fun Tracks.chapterRows(
    periodOffsetMs: Long,
    selectedOnly: Boolean,
): List<RawMediaChapter> =
    buildList {
        groups.forEach { group ->
            for (trackIndex in 0 until group.length) {
                if (selectedOnly && !group.isTrackSelected(trackIndex)) continue
                group
                    .getTrackFormat(trackIndex)
                    .metadata
                    ?.media3ChapterRows(periodOffsetMs)
                    ?.let(::addAll)
            }
        }
    }

internal fun Metadata.media3ChapterRows(periodOffsetMs: Long = 0L): List<RawMediaChapter> =
    buildList {
        for (chapter in orderedMedia3Chapters()) {
            val startMs = chapter.startTimeMs.translatedBy(periodOffsetMs) ?: continue
            val translatedEndMs = chapter.endTimeMs.translatedBy(periodOffsetMs)
            add(
                RawMediaChapter(
                    startMs = startMs,
                    endMs = translatedEndMs?.takeIf { it > startMs },
                    title = chapter.title?.value,
                    language = chapter.title?.language,
                    isHidden = chapter.isHidden,
                ),
            )
        }
    }

private fun Metadata.orderedMedia3Chapters(): List<Chapter> {
    val chapters = getEntriesOfType(Chapter::class.java)
    val tables = getEntriesOfType(ChapterTocFrame::class.java)
    if (tables.isEmpty()) return chapters

    val chaptersById =
        chapters
            .filterIsInstance<ChapterFrame>()
            .associateBy(ChapterFrame::chapterId)
    val tablesById = tables.associateBy(ChapterTocFrame::elementId)
    val result = mutableListOf<Chapter>()
    val addedChapterIds = mutableSetOf<String>()
    val visitedTableIds = mutableSetOf<String>()

    fun visit(elementId: String) {
        chaptersById[elementId]?.let { chapter ->
            if (addedChapterIds.add(elementId)) result += chapter
            return
        }
        val table = tablesById[elementId] ?: return
        if (!visitedTableIds.add(elementId)) return
        table.children.forEach(::visit)
    }

    tables.filter(ChapterTocFrame::isRoot).forEach { table -> visit(table.elementId) }
    chapters.forEach { chapter ->
        if (chapter !is ChapterFrame || addedChapterIds.add(chapter.chapterId)) {
            result += chapter
        }
    }
    return result
}

private fun Long.translatedBy(offsetMs: Long): Long? {
    if (this == C.TIME_UNSET || this < 0L) return null
    return runCatching { Math.addExact(this, offsetMs) }.getOrNull()
}

private const val MICROSECONDS_PER_MILLISECOND = 1_000L
