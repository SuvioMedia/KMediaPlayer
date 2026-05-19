package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.compose.runtime.Immutable

/**
 * Represents a single subtitle cue with timing information and text content.
 *
 * @property startTime The start time of the subtitle in milliseconds
 * @property endTime The end time of the subtitle in milliseconds
 * @property text The text content of the subtitle
 */
@Immutable
data class SubtitleCue(
    val startTime: Long,
    val endTime: Long,
    val text: String,
) {
    /**
     * Checks if this subtitle cue should be displayed at the given time.
     *
     * @param currentTimeMs The current playback time in milliseconds
     * @return True if the cue should be displayed, false otherwise
     */
    fun isActive(currentTimeMs: Long): Boolean = currentTimeMs >= startTime && currentTimeMs < endTime
}

/**
 * Represents a collection of subtitle cues for a specific track.
 *
 * @property cues The list of subtitle cues
 */
@Immutable
data class SubtitleCueList(
    val cues: List<SubtitleCue> = emptyList(),
) {
    private val cuesByStart = cues.sortedWith(compareBy<SubtitleCue> { it.startTime }.thenBy { it.endTime })
    private val maxCueDurationMs = cues.maxOfOrNull { (it.endTime - it.startTime).coerceAtLeast(0L) } ?: 0L

    /**
     * Gets the active subtitle cues at the given time.
     *
     * @param currentTimeMs The current playback time in milliseconds
     * @return The list of active subtitle cues
     */
    fun getActiveCues(currentTimeMs: Long): List<SubtitleCue> {
        val lastStartedExclusive = firstCueStartingAfter(currentTimeMs)
        if (lastStartedExclusive == 0) return emptyList()

        val earliestPossibleStart = currentTimeMs - maxCueDurationMs
        val activeCues = mutableListOf<SubtitleCue>()
        var index = lastStartedExclusive - 1
        while (index >= 0 && cuesByStart[index].startTime >= earliestPossibleStart) {
            val cue = cuesByStart[index]
            if (cue.isActive(currentTimeMs)) {
                activeCues += cue
            }
            index--
        }
        activeCues.reverse()
        return activeCues
    }

    /**
     * Finds the next cue start or active cue end after the given time.
     */
    fun nextBoundaryAfter(currentTimeMs: Long): Long? {
        val nextStart = cuesByStart.getOrNull(firstCueStartingAfter(currentTimeMs))?.startTime
        val nextEnd =
            getActiveCues(currentTimeMs)
                .asSequence()
                .map { it.endTime }
                .filter { it > currentTimeMs }
                .minOrNull()

        return when {
            nextStart == null -> nextEnd
            nextEnd == null -> nextStart
            else -> minOf(nextStart, nextEnd)
        }
    }

    private fun firstCueStartingAfter(currentTimeMs: Long): Int {
        var low = 0
        var high = cuesByStart.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cuesByStart[middle].startTime <= currentTimeMs) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }
}
