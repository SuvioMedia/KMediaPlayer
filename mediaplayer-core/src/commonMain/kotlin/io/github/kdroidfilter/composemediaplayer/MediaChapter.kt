package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import kotlin.time.Duration

/**
 * A navigable chapter associated with the currently loaded media source.
 *
 * [end] is exclusive. Backends should infer a missing end from the next chapter start or the
 * media duration when either value is known. It may remain `null` for an open-ended final chapter.
 *
 * [title] is the best label selected by the backend for the platform's preferred languages.
 * [language] is its BCP 47 language tag when the source provides one. Hidden chapters remain
 * available to callers so applications can decide whether to display them.
 */
@Stable
data class MediaChapter(
    val start: Duration,
    val end: Duration? = null,
    val title: String? = null,
    val language: String? = null,
    val isHidden: Boolean = false,
) {
    init {
        require(start.isFinite()) { "A chapter start must be finite." }
        require(start >= Duration.ZERO) { "A chapter start cannot be negative." }
        require(end == null || end.isFinite()) { "A chapter end must be finite when present." }
        require(end == null || end > start) { "A chapter end must be greater than its start." }
    }

    /** Chapter start in milliseconds. */
    val startMs: Long
        get() = start.inWholeMilliseconds

    /** Exclusive chapter end in milliseconds, or `null` when it is not known. */
    val endMs: Long?
        get() = end?.inWholeMilliseconds

    /** Chapter duration, or `null` when [end] is not known. */
    val duration: Duration?
        get() = end?.minus(start)

    /** Chapter duration in milliseconds, or `null` when [end] is not known. */
    val durationMs: Long?
        get() = duration?.inWholeMilliseconds
}
