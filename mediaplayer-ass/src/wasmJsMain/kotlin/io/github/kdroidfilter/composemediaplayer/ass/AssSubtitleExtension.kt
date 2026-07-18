package io.github.kdroidfilter.composemediaplayer.ass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.AssSubtitleCanvas
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.WebSubtitlePipelineExtension
import org.w3c.dom.HTMLVideoElement

actual class AssSubtitleExtension actual constructor() : WebSubtitlePipelineExtension {
    actual override val id: String = ID
    actual override val availability: VideoPipelineExtensionAvailability =
        VideoPipelineExtensionAvailability.Available
    actual override val supportedSubtitleFormats: Set<SubtitleFormat> =
        setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)

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
            modifier = modifier,
            onActiveChanged = onActiveChanged,
        )
    }

    private companion object {
        const val ID: String = "composemediaplayer-ass"
    }
}
