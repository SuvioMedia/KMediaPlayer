@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.time.Duration

public data class WebSubtitleRendererConfiguration(
    public val codec: String,
    public val codecPrivate: ByteArray?,
    public val width: Int,
    public val height: Int,
    public val attachments: List<WebSubtitleFontAttachment>,
)

public data class WebSubtitleFontAttachment(
    public val name: String,
    public val mimeType: String,
    public val data: ByteArray,
)

public data class WebSubtitlePacket(
    public val data: ByteArray,
    public val presentationTime: Duration,
    public val duration: Duration,
)

/** KMedia-owned boundary for an optional embedded subtitle renderer. */
public interface WebEmbeddedSubtitleRenderer {
    public suspend fun configure(
        configuration: WebSubtitleRendererConfiguration,
        overlay: HTMLElement,
    )

    public suspend fun pushPacket(packet: WebSubtitlePacket)

    public fun render(position: Duration)

    public fun setDelay(delay: Duration)

    public fun clear()

    public suspend fun close()
}

/** Browser hook implemented by optional styled-subtitle companion artifacts. */
public interface WebSubtitlePipelineExtension : SubtitlePipelineExtension {
    /**
     * Optional typed renderer consumed directly by the Wasm engine's embedded-subtitle pipeline.
     * Returning null leaves the track on the engine's built-in subtitle renderer.
     */
    public fun createWasmMediaEmbeddedSubtitleRenderer(onError: (String) -> Unit): WebEmbeddedSubtitleRenderer? = null

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
