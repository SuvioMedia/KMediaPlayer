@file:OptIn(ExperimentalComposeUiApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.HtmlElementView
import org.w3c.dom.HTMLVideoElement

@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    if (playerState.hasMedia) {
        var videoElement by remember { mutableStateOf<HTMLVideoElement?>(null) }
        var videoRatio by remember { mutableStateOf<Float?>(null) }
        val usesProjectionRenderer =
            playerState.projection.usesWebProjectionRenderer(playerState.projectionTextureCrop)
        val sourceKind =
            (playerState as? DefaultVideoPlayerState)
                ?.sourceUri
                ?.toWebMediaSourceKind()
                ?: WebMediaSourceKind.EMPTY
        var useCors by remember(sourceKind) { mutableStateOf(sourceKind.shouldUseCors) }
        val scope = rememberCoroutineScope()

        WebProjectionDeviceMotionEffect(playerState = playerState, enabled = usesProjectionRenderer)

        // State for CORS mode changes
        var lastPosition by remember { mutableStateOf(0.0) }
        var wasPlaying by remember { mutableStateOf(false) }

        // Shared effects
        VideoPlayerEffects(
            playerState = playerState,
            videoElement = videoElement,
            scope = scope,
            useCors = useCors,
            onLastPositionChange = { lastPosition = it },
            onWasPlayingChange = { wasPlaying = it },
            lastPosition = lastPosition,
            wasPlaying = wasPlaying,
        )

        VideoVolumeAndSpeedEffects(
            playerState = playerState,
            videoElement = videoElement,
        )

        VideoMediaTrackEffects(
            playerState = playerState,
            videoElement = videoElement,
            scope = scope,
        )

        // Video content layout with HtmlElementView
        VideoContentLayout(
            playerState = playerState,
            modifier = modifier,
            videoRatio = videoRatio,
            contentScale = contentScale,
            overlay = overlay,
        ) {
            key(sourceKind, useCors) {
                HtmlElementView(
                    factory = {
                        createVideoElement(useCors).apply {
                            setupMetadataListener(playerState) { ratio ->
                                videoRatio = ratio
                            }
                            setupVideoElement(
                                video = this,
                                playerState = playerState,
                                scope = scope,
                                useCors = useCors,
                                allowCorsRetry = sourceKind.allowsCorsRetry,
                                onCorsError = {
                                    if (sourceKind.allowsCorsRetry) {
                                        useCors = false
                                    }
                                },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { video ->
                        videoElement = video
                        video.applyInteropBehindCanvas(hiddenForProjection = usesProjectionRenderer)
                        video.applyContentScale(contentScale, videoRatio, hiddenForProjection = usesProjectionRenderer)
                    },
                    onRelease = { video ->
                        if (!video.currentTime.isNaN() && video.currentTime > 0.0) {
                            lastPosition = video.currentTime
                        }
                        wasPlaying = playerState.isPlaying || !video.paused
                        video.stopPlaybackQualityDiagnostics()
                        video.safePause()
                        video.destroyHlsController()
                        video.destroyMkvSidecarTracks()
                        videoElement = null
                    },
                )
                WebProjectionCanvas(
                    playerState = playerState,
                    videoElement = videoElement,
                    modifier = Modifier.fillMaxSize(),
                )
                AssSubtitleCanvas(
                    playerState = playerState,
                    videoElement = videoElement,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun WebProjectionCanvas(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?,
    modifier: Modifier,
) {
    if (!playerState.projection.usesWebProjectionRenderer(playerState.projectionTextureCrop)) return

    HtmlElementView(
        factory = { createWebProjectionCanvasElement() },
        modifier = modifier,
        update = { canvas ->
            canvas.applyWebProjectionCanvasStyle()
            val video = videoElement
            if (video != null) {
                canvas.configureWebProjectionRenderer(
                    video = video,
                    projection = playerState.projection,
                    projectionView = playerState.projectionView,
                    textureCrop = playerState.projectionTextureCrop,
                    onError = { message ->
                        if (playerState is DefaultVideoPlayerState) {
                            playerState.renderingInfo.update(
                                videoRenderer = "HTMLVideoElement + browser compositor",
                                notes = message,
                                videoProjection = playerState.projection.renderingInfoLabel(),
                            )
                        }
                    },
                )
                if (playerState is DefaultVideoPlayerState) {
                    playerState.renderingInfo.update(
                        videoRenderer = "HTMLVideoElement -> WebGL projection canvas",
                        videoProjection = playerState.projection.renderingInfoLabel(),
                    )
                }
            }
        },
        onRelease = { canvas ->
            canvas.disposeWebProjectionRenderer()
        },
    )
}
