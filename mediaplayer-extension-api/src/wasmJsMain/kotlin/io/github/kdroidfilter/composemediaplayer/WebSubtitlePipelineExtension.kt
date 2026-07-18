package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.w3c.dom.HTMLVideoElement

/** Browser hook implemented by optional styled-subtitle companion artifacts. */
public interface WebSubtitlePipelineExtension : SubtitlePipelineExtension {
    @Composable
    public fun SubtitleOverlay(
        playerState: VideoPlayerState,
        videoElement: HTMLVideoElement?,
        modifier: Modifier,
        onActiveChanged: (Boolean) -> Unit,
    )
}
