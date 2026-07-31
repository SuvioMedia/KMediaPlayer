package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger

internal val webVideoLogger = TaggedLogger("WebVideoPlayerSurface")

/** Keeps Compose overlays aligned with the engine-owned browser surface. */
public fun Modifier.videoRatioClip(
    videoRatio: Float?,
    contentScale: ContentScale = ContentScale.Fit,
): Modifier = drawBehind { videoRatio?.let { drawVideoRatioRect(it, contentScale) } }

private fun DrawScope.drawVideoRatioRect(
    ratio: Float,
    contentScale: ContentScale,
) {
    if (ratio <= 0f || !ratio.isFinite()) return
    val containerWidth = size.width
    val containerHeight = size.height
    val containerRatio = containerWidth / containerHeight
    val (rectWidth, rectHeight) =
        when (contentScale) {
            ContentScale.Crop ->
                if (containerRatio < ratio) {
                    containerHeight * ratio to containerHeight
                } else {
                    containerWidth to containerWidth / ratio
                }
            ContentScale.FillWidth -> containerWidth to containerWidth / ratio
            ContentScale.FillHeight -> containerHeight * ratio to containerHeight
            ContentScale.FillBounds -> containerWidth to containerHeight
            else ->
                if (containerRatio > ratio) {
                    containerHeight * ratio to containerHeight
                } else {
                    containerWidth to containerWidth / ratio
                }
        }
    drawRect(
        color = Color.Transparent,
        blendMode = BlendMode.Clear,
        topLeft = Offset((containerWidth - rectWidth) / 2f, (containerHeight - rectHeight) / 2f),
        size = Size(rectWidth, rectHeight),
    )
}

@Composable
internal fun SubtitleOverlay(
    playerState: VideoPlayerState,
    suppressComposeAss: Boolean,
) {
    if (!playerState.subtitlesEnabled) return
    val subtitleTrack = playerState.currentSubtitleTrack ?: return
    if (subtitleTrack.isEmbedded) return
    if (suppressComposeAss && subtitleTrack.resolvedFormat().isAssFamily) return

    val currentTime =
        if (playerState.userDragging) {
            playerState.duration *
                (playerState.sliderPos / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
        } else {
            playerState.preciseCurrentTime
        } + playerState.subtitleOffset

    ComposeSubtitleLayer(
        currentTime = currentTime,
        duration = playerState.duration,
        isPlaying = playerState.isPlaying,
        subtitleTrack = subtitleTrack,
        subtitlesEnabled = true,
        textStyle = playerState.subtitleTextStyle,
        backgroundColor = playerState.subtitleBackgroundColor,
    )
}

@Composable
internal fun VideoBox(
    playerState: VideoPlayerState,
    videoRatio: Float?,
    contentScale: ContentScale,
    isFullscreenMode: Boolean,
    suppressComposeAss: Boolean,
    overlay: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(if (isFullscreenMode) Color.Black else Color.Transparent)
                .videoRatioClip(videoRatio, contentScale),
    ) {
        SubtitleOverlay(playerState, suppressComposeAss)
        Box(modifier = Modifier.fillMaxSize()) { overlay() }
    }
}

@Composable
internal fun VideoContentLayout(
    playerState: VideoPlayerState,
    modifier: Modifier,
    videoRatio: Float?,
    contentScale: ContentScale,
    suppressComposeAss: Boolean,
    overlay: @Composable () -> Unit,
    videoElementContent: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(if (playerState.isFullscreen) Color.Black else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        VideoBox(
            playerState = playerState,
            videoRatio = videoRatio,
            contentScale = contentScale,
            isFullscreenMode = playerState.isFullscreen,
            suppressComposeAss = suppressComposeAss,
            overlay = overlay,
        )
        videoElementContent()
    }
}
