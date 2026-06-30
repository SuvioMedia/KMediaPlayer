package io.github.kdroidfilter.composemediaplayer

sealed class TrackSelectionResult {
    data object Auto : TrackSelectionResult()

    data object Disabled : TrackSelectionResult()

    data class Selected(
        val trackId: String,
    ) : TrackSelectionResult()

    data class NotFound(
        val trackId: String,
    ) : TrackSelectionResult()

    data object NotSupported : TrackSelectionResult()

    data class Failed(
        val message: String,
    ) : TrackSelectionResult()

    val isApplied: Boolean
        get() =
            when (this) {
                Auto,
                Disabled,
                is Selected,
                -> true

                is NotFound,
                NotSupported,
                is Failed,
                -> false
            }
}

internal fun AudioTrack?.audioTrackSelectionResult(): TrackSelectionResult =
    this?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Auto

internal fun SubtitleTrack?.subtitleTrackSelectionResult(): TrackSelectionResult =
    this?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Disabled
