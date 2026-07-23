@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.JsAny

/** Browser hook implemented by optional styled-subtitle companion artifacts. */
public interface WebSubtitlePipelineExtension : SubtitlePipelineExtension {
    /**
     * Optional low-level renderer consumed directly by Movi's pluggable embedded-subtitle
     * pipeline. The returned JavaScript object must implement Movi's `SubtitleRenderer`
     * contract. Returning null leaves the track on Movi's built-in subtitle renderer.
     *
     * The value is intentionally opaque outside the Wasm adapter: applications keep using
     * [SubtitlePipelineExtension] and [VideoPlayerState], while optional renderer artifacts
     * can integrate without making the core player depend on their JavaScript runtime.
     */
    public fun createMoviEmbeddedSubtitleRenderer(onError: (String) -> Unit): JsAny? = null

    @Composable
    public fun SubtitleOverlay(
        playerState: VideoPlayerState,
        videoElement: HTMLVideoElement?,
        modifier: Modifier,
        onActiveChanged: (Boolean) -> Unit,
    )

    /**
     * Browser hook with the element that is actually visible to the user.
     *
     * [displayElement] is the video in the direct rendering path and the controlled-renderer
     * canvas when video frames are presented through a projection or color-managed surface.
     * [videoElement] is null for canvas-only playback engines; extensions can then synchronize
     * against [VideoPlayerState.preciseCurrentTime]. The default implementation preserves
     * compatibility with extensions implementing the original hook.
     */
    @Composable
    public fun SubtitleOverlay(
        playerState: VideoPlayerState,
        videoElement: HTMLVideoElement?,
        displayElement: HTMLElement?,
        contentScale: ContentScale,
        modifier: Modifier,
        onActiveChanged: (Boolean) -> Unit,
    ) {
        SubtitleOverlay(
            playerState = playerState,
            videoElement = videoElement,
            modifier = modifier,
            onActiveChanged = onActiveChanged,
        )
    }
}
