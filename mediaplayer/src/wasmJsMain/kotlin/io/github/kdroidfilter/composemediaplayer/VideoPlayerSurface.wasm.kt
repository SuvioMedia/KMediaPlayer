@file:OptIn(ExperimentalComposeUiApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.HtmlElementView
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    if (playerState is PreviewableVideoPlayerState) {
        VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
        return
    }
    if (playerState is VideoPlayerSurfaceProvider) {
        playerState.RenderVideoPlayerSurface(
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
        return
    }
    require(playerState is DefaultVideoPlayerState) {
        "Unsupported video player state: ${playerState::class}"
    }

    if (playerState.hasMedia) {
        val surfaceMediaSessionId = playerState.mediaSessionId
        val subtitleTrack = playerState.currentSubtitleTrack
        val subtitleExtension =
            playerState.webSubtitlePipelineExtensions.firstOrNull { extension ->
                subtitleTrack?.resolvedFormat()?.let(extension::supportsSubtitleFormat) == true
            }
        var styledSubtitleActive by
            remember(surfaceMediaSessionId, subtitleTrack?.id, subtitleExtension?.id) {
                mutableStateOf(false)
            }
        var videoElement by remember(surfaceMediaSessionId) { mutableStateOf<HTMLVideoElement?>(null) }
        var projectionElement by remember(surfaceMediaSessionId) { mutableStateOf<HTMLElement?>(null) }
        var videoRatio by remember(surfaceMediaSessionId) { mutableStateOf<Float?>(null) }
        val colorPipelineStatus by playerState.colorPipelineStatus.collectAsState()
        val usesProjectionRenderer =
            playerState.projection.usesWebProjectionRenderer(playerState.projectionTextureCrop)
        val usesControlledColorRenderer =
            shouldUseWebControlledColorRenderer(
                status = colorPipelineStatus,
                usesProjectionRenderer = usesProjectionRenderer,
            )
        val sourceKind = playerState.sourceUri?.toWebMediaSourceKind() ?: WebMediaSourceKind.EMPTY
        var useCors by remember(sourceKind, surfaceMediaSessionId) { mutableStateOf(sourceKind.shouldUseCors) }
        val scope = rememberCoroutineScope()

        DisposableEffect(playerState, surfaceMediaSessionId, usesControlledColorRenderer, usesProjectionRenderer) {
            playerState.bindWebColorSurface(
                usesControlledColorRenderer = usesControlledColorRenderer,
                isProjection = usesProjectionRenderer,
            )
            onDispose { playerState.unbindWebColorSurface() }
        }

        WebProjectionDeviceMotionEffect(playerState = playerState, enabled = usesProjectionRenderer)

        // State for CORS mode changes
        var lastPosition by remember(surfaceMediaSessionId) { mutableStateOf(0.0) }
        var wasPlaying by remember(surfaceMediaSessionId) { mutableStateOf(false) }

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
            suppressComposeAss = styledSubtitleActive,
            overlay = overlay,
        ) {
            key(sourceKind, useCors, surfaceMediaSessionId) {
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
                        video.applyInteropBehindCanvas(hiddenForProjection = usesControlledColorRenderer)
                        video.applyContentScale(
                            contentScale,
                            videoRatio,
                            hiddenForProjection = usesControlledColorRenderer,
                        )
                    },
                    onRelease = { video ->
                        if (!video.currentTime.isNaN() && video.currentTime > 0.0) {
                            lastPosition = video.currentTime
                        }
                        wasPlaying = playerState.isPlaying || !video.paused
                        video.cleanupWebVideoElement()
                        videoElement = null
                    },
                )
                WebProjectionCanvas(
                    playerState = playerState,
                    videoElement = videoElement,
                    enabled = usesControlledColorRenderer,
                    isProjection = usesProjectionRenderer,
                    modifier = Modifier.fillMaxSize(),
                    onElementChanged = { element -> projectionElement = element },
                    onElementReleased = { element ->
                        if (projectionElement === element) {
                            projectionElement = null
                        }
                    },
                )
                val displayElement: HTMLElement? =
                    if (usesControlledColorRenderer) projectionElement else videoElement
                subtitleExtension?.SubtitleOverlay(
                    playerState = playerState,
                    videoElement = videoElement,
                    displayElement = displayElement,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                    onActiveChanged = { active -> styledSubtitleActive = active },
                )
            }
        }
    }
}

@Composable
private fun WebProjectionCanvas(
    playerState: VideoPlayerState,
    videoElement: HTMLVideoElement?,
    enabled: Boolean,
    isProjection: Boolean,
    modifier: Modifier,
    onElementChanged: (HTMLElement?) -> Unit,
    onElementReleased: (HTMLElement) -> Unit,
) {
    val colorPipelineStatus by playerState.colorPipelineStatus.collectAsState()
    val sourceColorInfo = colorPipelineStatus.source
    val plannedOutput = colorPipelineStatus.plannedOutputDynamicRange
    val videoProjectionLabel = if (isProjection) playerState.projection.renderingInfoLabel() else null
    if (
        !enabled ||
        sourceColorInfo.dynamicRange == VideoDynamicRange.UNKNOWN ||
        plannedOutput == VideoDynamicRange.UNKNOWN
    ) {
        SideEffect { onElementChanged(null) }
        return
    }

    key(plannedOutput) {
        HtmlElementView(
            factory = {
                createWebProjectionCanvasElement().also(onElementChanged)
            },
            modifier = modifier,
            update = { canvas ->
                onElementChanged(canvas)
                canvas.applyWebProjectionCanvasStyle()
                val video = videoElement
                if (video != null) {
                    canvas.configureWebProjectionRenderer(
                        video = video,
                        projection = playerState.projection,
                        projectionView = playerState.projectionView,
                        textureCrop = playerState.projectionTextureCrop,
                        sourceColorInfo = sourceColorInfo,
                        outputDynamicRange = plannedOutput,
                        onConfigured = { outputRange, surfaceKind ->
                            if (playerState is DefaultVideoPlayerState) {
                                playerState.onWebColorRendererConfigured(outputRange, surfaceKind)
                                playerState.renderingInfo.update(
                                    videoRenderer =
                                        if (
                                            surfaceKind == VideoSurfaceKind.WEB_GPU_CANVAS &&
                                            outputRange != VideoDynamicRange.SDR &&
                                            outputRange != VideoDynamicRange.UNKNOWN
                                        ) {
                                            "HTMLVideoElement -> WebGPU FP16 HDR projection canvas"
                                        } else if (surfaceKind == VideoSurfaceKind.WEB_GPU_CANVAS) {
                                            "HTMLVideoElement -> WebGPU FP16 controlled SDR canvas"
                                        } else if (isProjection) {
                                            "HTMLVideoElement -> WebGL color-managed SDR projection canvas"
                                        } else {
                                            "HTMLVideoElement -> WebGL color-managed SDR canvas"
                                        },
                                    notes = null,
                                    videoProjection = videoProjectionLabel,
                                )
                            }
                        },
                        onHdrUnavailable = { message ->
                            if (playerState is DefaultVideoPlayerState) {
                                playerState.onWebHdrRendererUnavailable(message)
                                playerState.renderingInfo.update(
                                    videoRenderer =
                                        if (isProjection) {
                                            "HTMLVideoElement -> controlled SDR projection fallback"
                                        } else {
                                            "HTMLVideoElement -> controlled SDR fallback"
                                        },
                                    notes = message,
                                    videoProjection = videoProjectionLabel,
                                )
                            }
                        },
                        onError = { message ->
                            if (playerState is DefaultVideoPlayerState) {
                                playerState.onWebColorRendererFailed(message)
                                playerState.renderingInfo.update(
                                    videoRenderer = "HTMLVideoElement + browser compositor",
                                    notes = message,
                                    videoProjection = videoProjectionLabel,
                                )
                            }
                        },
                    )
                }
            },
            onRelease = { canvas ->
                canvas.disposeWebProjectionRenderer()
                onElementReleased(canvas)
            },
        )
    }
}

internal fun shouldUseWebControlledColorRenderer(
    status: VideoColorPipelineStatus,
    usesProjectionRenderer: Boolean,
): Boolean =
    usesProjectionRenderer ||
        (
            status.requestedDynamicRangePolicy == DynamicRangePolicy.FORCE_SDR &&
                status.source.dynamicRange != VideoDynamicRange.SDR
        )
