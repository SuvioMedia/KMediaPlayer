@file:OptIn(ExperimentalForeignApi::class)
@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.UIKitView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.cValue
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRect
import platform.Foundation.NSCoder
import platform.UIKit.UIColor
import platform.UIKit.UIScreen
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta

private val iosSurfaceLogger = TaggedLogger("iOSVideoPlayerSurface")

@OptIn(ExperimentalForeignApi::class)
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

    // Set pauseOnDispose to false to prevent pausing during screen rotation
    VideoPlayerSurfaceImpl(
        playerState,
        modifier,
        contentScale,
        overlay,
        isInFullscreenView = false,
        pauseOnDispose = false,
    )
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("CyclomaticComplexMethod")
@Composable
fun VideoPlayerSurfaceImpl(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    isInFullscreenView: Boolean = false,
    pauseOnDispose: Boolean = true,
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
    DefaultVideoPlayerSurfaceImpl(
        playerState = playerState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        isInFullscreenView = isInFullscreenView,
        pauseOnDispose = pauseOnDispose,
    )
}

@OptIn(ExperimentalForeignApi::class)
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
private fun DefaultVideoPlayerSurfaceImpl(
    playerState: DefaultVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    isInFullscreenView: Boolean = false,
    pauseOnDispose: Boolean = true,
) {
    // Cleanup when deleting the view
    DisposableEffect(Unit) {
        onDispose {
            iosSurfaceLogger.d { "[VideoPlayerSurface] Disposing" }
            // Only pause if pauseOnDispose is true (prevents pausing during rotation or fullscreen transitions)
            if (pauseOnDispose) {
                iosSurfaceLogger.d { "[VideoPlayerSurface] Pausing on dispose" }
                playerState.pause()
            } else {
                iosSurfaceLogger.d { "[VideoPlayerSurface] Not pausing on dispose (rotation or fullscreen transition)" }
            }
        }
    }

    val currentPlayer = playerState.player
    val colorPipelineStatus by playerState.colorPipelineStatus.collectAsState()
    val usesProjectionRenderer =
        playerState.projection.usesIosSceneKitProjectionRenderer(playerState.projectionTextureCrop)
    val nativeLayerUsesEdr =
        colorPipelineStatus.requestedDynamicRangePolicy != DynamicRangePolicy.FORCE_SDR
    IosProjectionDeviceMotionEffect(
        playerState = playerState,
        enabled = usesProjectionRenderer && playerState.hasMedia,
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (playerState.hasMedia) {
            key(usesProjectionRenderer) {
                UIKitView(
                    modifier =
                        contentScale.toCanvasModifier(
                            aspectRatio = playerState.aspectRatio,
                            width = playerState.metadata.width,
                            height = playerState.metadata.height,
                        ),
                    factory = {
                        if (usesProjectionRenderer) {
                            ProjectionPlayerUIView(frame = cValue<CGRect>()).apply {
                                configure(
                                    player = currentPlayer,
                                    projection = playerState.projection,
                                    projectionView = playerState.projectionView,
                                    textureCrop = playerState.projectionTextureCrop,
                                    sourceColorInfo = colorPipelineStatus.source,
                                    outputDynamicRange = colorPipelineStatus.plannedOutputDynamicRange,
                                    plannedMetadataHandling = colorPipelineStatus.plannedMetadataHandling,
                                    displayPeakLuminanceNits = colorPipelineStatus.display.maxLuminanceNits,
                                    onConfigured = { output ->
                                        playerState.onAppleProjectionRendererConfigured(this, output)
                                    },
                                    onHdrUnavailable = { output, detail ->
                                        playerState.onAppleProjectionHdrUnavailable(this, output, detail)
                                    },
                                    onHdr10PlusObserved = { info ->
                                        playerState.onAppleProjectionHdr10PlusObserved(this, info)
                                    },
                                    onError = { detail ->
                                        playerState.onAppleProjectionRendererFailed(this, detail)
                                    },
                                )
                                playerState.bindProjectionView(
                                    view = this,
                                    isFullscreen = isInFullscreenView,
                                    isProjection = usesProjectionRenderer,
                                )
                            }
                        } else {
                            PlayerUIView(frame = cValue<CGRect>()).apply {
                                player = currentPlayer
                                backgroundColor = UIColor.blackColor
                                clipsToBounds = true

                                val videoPlayerLayer = layer as? AVPlayerLayer
                                if (videoPlayerLayer != null) {
                                    videoPlayerLayer.configureAppleDynamicRange(hdr = nativeLayerUsesEdr)
                                    playerState.bindPlayerLayer(
                                        videoPlayerLayer,
                                        isInFullscreenView,
                                        window?.screen ?: UIScreen.mainScreen,
                                    )
                                }
                            }
                        }
                    },
                    update = { view ->
                        when (view) {
                            is ProjectionPlayerUIView -> {
                                view.configure(
                                    player = currentPlayer,
                                    projection = playerState.projection,
                                    projectionView = playerState.projectionView,
                                    textureCrop = playerState.projectionTextureCrop,
                                    sourceColorInfo = colorPipelineStatus.source,
                                    outputDynamicRange = colorPipelineStatus.plannedOutputDynamicRange,
                                    plannedMetadataHandling = colorPipelineStatus.plannedMetadataHandling,
                                    displayPeakLuminanceNits = colorPipelineStatus.display.maxLuminanceNits,
                                    onConfigured = { output ->
                                        playerState.onAppleProjectionRendererConfigured(view, output)
                                    },
                                    onHdrUnavailable = { output, detail ->
                                        playerState.onAppleProjectionHdrUnavailable(view, output, detail)
                                    },
                                    onHdr10PlusObserved = { info ->
                                        playerState.onAppleProjectionHdr10PlusObserved(view, info)
                                    },
                                    onError = { detail ->
                                        playerState.onAppleProjectionRendererFailed(view, detail)
                                    },
                                )
                                playerState.bindProjectionView(
                                    view = view,
                                    isFullscreen = isInFullscreenView,
                                    isProjection = usesProjectionRenderer,
                                )
                                view.hidden = !playerState.hasMedia
                            }
                            is PlayerUIView -> {
                                view.player = currentPlayer
                                (view.layer as? AVPlayerLayer)?.let { layer ->
                                    layer.configureAppleDynamicRange(hdr = nativeLayerUsesEdr)
                                    playerState.bindPlayerLayer(
                                        layer,
                                        isInFullscreenView,
                                        view.window?.screen ?: UIScreen.mainScreen,
                                    )
                                }

                                // Hide or show the view depending on the presence of media
                                view.hidden = !playerState.hasMedia

                                // Update the videoGravity when contentScale changes
                                val videoGravity =
                                    when (contentScale) {
                                        ContentScale.Crop,
                                        ContentScale.FillHeight,
                                        -> AVLayerVideoGravityResizeAspectFill
                                        ContentScale.FillWidth -> AVLayerVideoGravityResizeAspectFill
                                        ContentScale.FillBounds -> AVLayerVideoGravityResize // no aspect-ratio
                                        ContentScale.Fit,
                                        ContentScale.Inside,
                                        -> AVLayerVideoGravityResizeAspect

                                        else -> AVLayerVideoGravityResizeAspect
                                    }
                                view.videoGravity = videoGravity

                                iosSurfaceLogger.d {
                                    "View configured with contentScale: $contentScale, videoGravity: $videoGravity"
                                }
                            }
                        }
                    },
                    onRelease = { view ->
                        when (view) {
                            is ProjectionPlayerUIView -> {
                                playerState.releaseProjectionView(view)
                                view.releaseRenderer()
                            }
                            is PlayerUIView -> {
                                (view.layer as? AVPlayerLayer)?.let(playerState::releasePlayerLayer)
                                view.player = null
                            }
                        }
                    },
                )
            }

            // Add Compose-based subtitle layer
            if (playerState.subtitlesEnabled &&
                playerState.currentSubtitleTrack != null &&
                playerState.currentSubtitleTrack?.isEmbedded != true
            ) {
                val subtitleTrack = requireNotNull(playerState.currentSubtitleTrack)
                val currentTime =
                    if (playerState.userDragging) {
                        playerState.duration *
                            (playerState.sliderPos / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
                    } else {
                        playerState.preciseCurrentTime
                    } + playerState.subtitleOffset
                val subtitleExtension = playerState.subtitlePipelineExtensionFor(subtitleTrack)
                var extensionActive by
                    remember(subtitleTrack.id, subtitleExtension?.id) {
                        mutableStateOf(false)
                    }

                if (!extensionActive) {
                    ComposeSubtitleLayer(
                        currentTime = currentTime,
                        duration = playerState.duration,
                        isPlaying = playerState.isPlaying,
                        subtitleTrack = subtitleTrack,
                        subtitlesEnabled = playerState.subtitlesEnabled,
                        textStyle = playerState.subtitleTextStyle,
                        backgroundColor = playerState.subtitleBackgroundColor,
                    )
                }
                subtitleExtension?.SubtitleOverlay(
                    track = subtitleTrack,
                    positionMs = currentTime.inWholeMilliseconds.coerceAtLeast(0L),
                    isPlaying = playerState.isPlaying,
                    modifier =
                        contentScale.toCanvasModifier(
                            aspectRatio = playerState.aspectRatio,
                            width = playerState.metadata.width,
                            height = playerState.metadata.height,
                        ),
                    onRendererActiveChanged = { active -> extensionActive = active },
                )
            }
        }

        // Render the overlay content on top of the video with fillMaxSize modifier
        // to ensure it takes the full height of the parent Box
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }

    // Handle fullscreen mode
    if (playerState.isFullscreen && !isInFullscreenView) {
        openFullscreenView(playerState) { state, mod, inFullscreen ->
            // Set pauseOnDispose to false to prevent pausing during fullscreen transitions
            VideoPlayerSurfaceImpl(state, mod, contentScale, overlay, inFullscreen, pauseOnDispose = false)
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class PlayerUIView : UIView {
    companion object : UIViewMeta() {
        override fun layerClass(): ObjCClass = AVPlayerLayer
    }

    constructor(frame: CValue<CGRect>) : super(frame)
    constructor(coder: NSCoder) : super(coder)

    var player: AVPlayer?
        get() = (layer as? AVPlayerLayer)?.player
        set(value) {
            (layer as? AVPlayerLayer)?.player = value
        }

    var videoGravity: String?
        get() = (layer as? AVPlayerLayer)?.videoGravity
        set(value) {
            (layer as? AVPlayerLayer)?.videoGravity = value
        }
}

@OptIn(ExperimentalForeignApi::class)
private class ProjectionPlayerUIView : UIView {
    private val rendererCreation = AppleMetalProjectionRenderer.create()
    private val renderer = rendererCreation.renderer
    private val contentView = renderer?.view ?: UIView(frame = cValue<CGRect>())
    private var reportedInitializationFailureFor = VideoDynamicRange.UNKNOWN

    constructor(frame: CValue<CGRect>) : super(frame) {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        addSubview(contentView)
    }

    constructor(coder: NSCoder) : super(coder)

    @Suppress("LongParameterList")
    fun configure(
        player: AVPlayer?,
        projection: VideoProjectionSettings,
        projectionView: VideoProjectionViewSettings,
        textureCrop: VideoTextureCrop,
        sourceColorInfo: VideoColorInfo,
        outputDynamicRange: VideoDynamicRange,
        plannedMetadataHandling: DynamicMetadataHandling,
        displayPeakLuminanceNits: Float?,
        onConfigured: (VideoDynamicRange) -> Unit,
        onHdrUnavailable: (VideoDynamicRange, String) -> Unit,
        onHdr10PlusObserved: (Hdr10PlusInfo) -> Unit,
        onError: (String) -> Unit,
    ) {
        val activeRenderer = renderer
        if (activeRenderer == null) {
            if (
                outputDynamicRange != VideoDynamicRange.UNKNOWN &&
                reportedInitializationFailureFor != outputDynamicRange
            ) {
                reportedInitializationFailureFor = outputDynamicRange
                val detail = rendererCreation.failureDetail ?: "The iOS Metal projection renderer is unavailable."
                if (outputDynamicRange == VideoDynamicRange.HDR10 || outputDynamicRange == VideoDynamicRange.HLG) {
                    onHdrUnavailable(outputDynamicRange, detail)
                } else {
                    onError(detail)
                }
            }
            return
        }
        activeRenderer.configure(
            player = player,
            projection = projection,
            projectionView = projectionView,
            textureCrop = textureCrop,
            sourceColorInfo = sourceColorInfo,
            outputDynamicRange = outputDynamicRange,
            plannedMetadataHandling = plannedMetadataHandling,
            displayPeakLuminanceNits = displayPeakLuminanceNits,
            onConfigured = onConfigured,
            onHdrUnavailable = onHdrUnavailable,
            onHdr10PlusObserved = onHdr10PlusObserved,
            onError = onError,
        )
    }

    fun releaseRenderer() = renderer?.release() ?: Unit

    override fun layoutSubviews() {
        super.layoutSubviews()
        contentView.setFrame(bounds)
    }
}
