package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** iOS hook implemented by an optional authored-subtitle overlay artifact. */
public interface IosSubtitlePipelineExtension : SubtitlePipelineExtension {
    /**
     * Draws a transparent subtitle overlay for an external track.
     *
     * [onRendererActiveChanged] must report true only after the authored
     * renderer has loaded the track. The core keeps its dialogue fallback
     * visible while the extension is loading or has failed.
     */
    @Composable
    public fun SubtitleOverlay(
        track: SubtitleTrack,
        positionMs: Long,
        isPlaying: Boolean,
        modifier: Modifier,
        onRendererActiveChanged: (Boolean) -> Unit,
    )
}
