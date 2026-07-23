@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.kdroidfilter.composemediaplayer.ass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.AssSubtitleCanvas
import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.WebSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.assSubtitleRendererUnavailableReason
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.JsAny

actual class AssSubtitleExtension actual constructor(
    config: AssSubtitleRendererConfig,
) : WebSubtitlePipelineExtension {
    private val rendererConfig = config

    actual override val id: String = ID
    actual override val availability: VideoPipelineExtensionAvailability
        get() {
            val unavailableReason = assSubtitleRendererUnavailableReason(rendererConfig.enabled)
            return if (unavailableReason.isEmpty()) {
                VideoPipelineExtensionAvailability.Available
            } else {
                VideoPipelineExtensionAvailability.unavailable(unavailableReason)
            }
        }
    actual override val supportedSubtitleFormats: Set<SubtitleFormat> =
        setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)

    override fun createMoviEmbeddedSubtitleRenderer(onError: (String) -> Unit): JsAny? =
        createMoviAssSubtitleRenderer(
            config = rendererConfig,
            onError = onError,
        )

    @Composable
    override fun SubtitleOverlay(
        playerState: VideoPlayerState,
        videoElement: HTMLVideoElement?,
        modifier: Modifier,
        onActiveChanged: (Boolean) -> Unit,
    ) {
        AssSubtitleCanvas(
            playerState = playerState,
            videoElement = videoElement,
            displayElement = videoElement,
            contentScale = ContentScale.Fit,
            modifier = modifier,
            config = rendererConfig,
            onActiveChanged = onActiveChanged,
        )
    }

    @Composable
    override fun SubtitleOverlay(
        playerState: VideoPlayerState,
        videoElement: HTMLVideoElement?,
        displayElement: HTMLElement?,
        contentScale: ContentScale,
        modifier: Modifier,
        onActiveChanged: (Boolean) -> Unit,
    ) {
        AssSubtitleCanvas(
            playerState = playerState,
            videoElement = videoElement,
            displayElement = displayElement,
            contentScale = contentScale,
            modifier = modifier,
            config = rendererConfig,
            onActiveChanged = onActiveChanged,
        )
    }

    private companion object {
        const val ID: String = "composemediaplayer-ass"
    }
}
