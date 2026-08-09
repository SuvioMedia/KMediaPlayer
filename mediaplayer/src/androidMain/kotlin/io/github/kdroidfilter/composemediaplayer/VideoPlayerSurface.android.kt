package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@UnstableApi
@Composable
actual fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    VideoPlayerSurfaceInternal(
        playerState = playerState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        surfaceType = SurfaceType.Auto,
    )
}

@UnstableApi
@Composable
fun VideoPlayerSurface(
    playerState: VideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    surfaceType: SurfaceType = SurfaceType.Auto,
    overlay: @Composable () -> Unit = {},
) {
    VideoPlayerSurfaceInternal(
        playerState = playerState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        surfaceType = surfaceType,
    )
}

@UnstableApi
@Composable
fun VideoPlayerSurface(
    playerState: RenderableVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    surfaceType: SurfaceType = SurfaceType.Auto,
    overlay: @Composable () -> Unit = {},
) {
    VideoPlayerSurfaceInternal(
        playerState = playerState.platformState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        surfaceType = surfaceType,
    )
}

@UnstableApi
@Composable
private fun VideoPlayerSurfaceInternal(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
    overlay: @Composable () -> Unit,
) {
    val surfaceState = playerState.unwrapDelegatingState()
    if (LocalInspectionMode.current || surfaceState is PreviewableVideoPlayerState) {
        VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
        return
    }
    if (surfaceState is VideoPlayerSurfaceProvider) {
        surfaceState.RenderVideoPlayerSurface(
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
        return
    }
    require(surfaceState is DefaultVideoPlayerState) {
        "Unsupported renderable player state: ${playerState::class}"
    }

    // Single source of truth — no rememberSaveable, drive directly from playerState
    val isFullscreen = surfaceState.isFullscreen
    val isPipFullScreen = surfaceState.isPipFullScreen

    BindAndroidActivity(playerState = surfaceState)
    AutoPipEffect(playerState = surfaceState)

    // Exit fullscreen when returning from PiP
    LaunchedEffect(surfaceState.isPipActive) {
        if (!surfaceState.isPipActive && surfaceState.isPipFullScreen) {
            delay(300.milliseconds)
            surfaceState.togglePipFullScreen()
        }
    }

    DisposableEffect(surfaceState) {
        onDispose {
            try {
                // Detach the view from the player
                surfaceState.attachPlayerView(null)
            } catch (e: Exception) {
                androidVideoLogger.e { "Error detaching PlayerView on dispose: ${e.message}" }
            }
        }
    }

    if (isFullscreen || isPipFullScreen) {
        FullScreenLayout(
            modifier = Modifier,
            onDismissRequest = {
                // Call playerState.toggleFullscreen() to ensure proper cleanup
                surfaceState.toggleFullscreen()
            },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
            ) {
                VideoPlayerContent(
                    playerState = surfaceState,
                    modifier = Modifier.fillMaxHeight(),
                    overlay = overlay,
                    contentScale = contentScale,
                    surfaceType = surfaceType,
                )
            }
        }
    } else {
        VideoPlayerContent(
            playerState = surfaceState,
            modifier = modifier,
            overlay = overlay,
            contentScale = contentScale,
            surfaceType = surfaceType,
        )
    }
}

@UnstableApi
@Composable
private fun VideoPlayerContent(
    playerState: DefaultVideoPlayerState,
    modifier: Modifier,
    overlay: @Composable () -> Unit,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (playerState.hasMedia) {
            // Recompose when the planner learns that the initially attached native surface has no safe color route.
            val colorPipelineStatus by playerState.colorPipelineStatus.collectAsState()
            val requestedSurfaceType =
                surfaceType.resolveFor(
                    projection = playerState.projection,
                    textureCrop = playerState.projectionTextureCrop,
                )
            val resolvedSurfaceType =
                if (
                    colorPipelineStatus.source.isHdr &&
                    playerState.shouldUseControlledColorFallback() &&
                    requestedSurfaceType != SurfaceType.ProjectedGlSurfaceView &&
                    requestedSurfaceType != SurfaceType.SphericalGlSurfaceView
                ) {
                    SurfaceType.ProjectedGlSurfaceView
                } else {
                    requestedSurfaceType
                }
            key(resolvedSurfaceType) {
                if (
                    resolvedSurfaceType == SurfaceType.ProjectedGlSurfaceView ||
                    resolvedSurfaceType == SurfaceType.SphericalGlSurfaceView
                ) {
                    AndroidProjectionSurface(
                        playerState = playerState,
                        contentScale = contentScale,
                    )
                } else {
                    AndroidPlayerViewSurface(
                        playerState = playerState,
                        contentScale = contentScale,
                        resolvedSurfaceType = resolvedSurfaceType,
                    )
                }
            }

            val styledSubtitleBackendActive =
                playerState.currentSubtitleTrack
                    ?.let { track -> playerState.androidSubtitleBackend?.supports(track) } == true
            if (playerState.subtitlesEnabled && styledSubtitleBackendActive) {
                playerState.androidSubtitleBackend?.Overlay(
                    modifier =
                        contentScale.toCanvasModifier(
                            playerState.aspectRatio,
                            playerState.metadata.width,
                            playerState.metadata.height,
                        ),
                    cropToFill =
                        contentScale == ContentScale.Crop &&
                            resolvedSurfaceType != SurfaceType.ProjectedGlSurfaceView &&
                            resolvedSurfaceType != SurfaceType.SphericalGlSurfaceView,
                    videoAspectRatio = playerState.aspectRatio,
                )
            }

            // Add a Compose-based subtitle layer
            if (playerState.subtitlesEnabled &&
                playerState.currentSubtitleTrack != null &&
                playerState.currentSubtitleTrack?.isEmbedded != true &&
                !styledSubtitleBackendActive
            ) {
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
                    subtitleTrack = playerState.currentSubtitleTrack,
                    subtitlesEnabled = playerState.subtitlesEnabled,
                    textStyle = playerState.subtitleTextStyle,
                    backgroundColor = playerState.subtitleBackgroundColor,
                )
            }
        }

        // Render the overlay content above the video with the fillMaxSize modifier
        // to ensure it takes the full height of the parent Box
        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }
}

@Composable
private fun AndroidProjectionSurface(
    playerState: DefaultVideoPlayerState,
    contentScale: ContentScale,
) {
    AndroidProjectionDeviceMotionEffect(playerState)
    val colorPipelineStatus by playerState.colorPipelineStatus.collectAsState()
    val sourceColorInfo = colorPipelineStatus.source
    val isScreenWakeRequested = playerState.isPlaying || playerState.isLoading

    AndroidView(
        modifier =
            contentScale.toCanvasModifier(
                playerState.aspectRatio,
                playerState.metadata.width,
                playerState.metadata.height,
            ),
        factory = { context ->
            AndroidProjectionGlSurfaceView(
                context = context,
                effectController = playerState.androidProjectionEffectController,
            ).apply {
                keepScreenOn = isScreenWakeRequested
                setBackgroundColor(android.graphics.Color.BLACK)
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    playerState.updateVideoOutputSurface(SurfaceType.ProjectedGlSurfaceView, view.display)
                }
                callback =
                    object : AndroidProjectionGlSurfaceView.Callback {
                        override fun onVideoEffectConfigured(
                            outputDynamicRange: VideoDynamicRange,
                            requireHdr: Boolean,
                        ) {
                            playerState.activateControlledColorRenderer(outputDynamicRange, requireHdr)
                        }

                        override fun onColorRendererConfigured(outputDynamicRange: VideoDynamicRange) {
                            playerState.updateControlledColorRendererConfigured(outputDynamicRange)
                        }

                        override fun onHdrRendererUnavailable(
                            outputDynamicRange: VideoDynamicRange,
                            message: String,
                        ) {
                            playerState.updateControlledHdrRendererUnavailable(outputDynamicRange, message)
                        }

                        override fun onProjectionRendererError(message: String) {
                            playerState.updateControlledColorRendererFailed(message)
                            androidVideoLogger.e { message }
                        }
                    }
                playerState.attachProjectionVideoSurfaceView(this)
                playerState.attachHdr10PlusMetadataConsumer(
                    consumer = ::updateHdr10PlusMetadata,
                    reset = ::clearHdr10PlusMetadata,
                )
                configure(
                    projection = playerState.projection,
                    projectionView = playerState.projectionView,
                    textureCrop = playerState.projectionTextureCrop,
                    sourceColorInfo = sourceColorInfo,
                    sourceFormat = playerState.projectionInputFormat(),
                    plannedOutputDynamicRange = colorPipelineStatus.plannedOutputDynamicRange,
                    plannedMetadataHandling = colorPipelineStatus.plannedMetadataHandling,
                    dynamicRangePolicy = colorPipelineStatus.requestedDynamicRangePolicy,
                    displayPeakLuminanceNits = colorPipelineStatus.display.maxLuminanceNits,
                )
            }
        },
        update = { projectionView ->
            projectionView.keepScreenOn = isScreenWakeRequested
            playerState.updateVideoOutputSurface(SurfaceType.ProjectedGlSurfaceView, projectionView.display)
            playerState.attachPlayerView(null)
            playerState.attachProjectionVideoSurfaceView(projectionView)
            projectionView.onResume()
            playerState.attachHdr10PlusMetadataConsumer(
                consumer = projectionView::updateHdr10PlusMetadata,
                reset = projectionView::clearHdr10PlusMetadata,
            )
            projectionView.configure(
                projection = playerState.projection,
                projectionView = playerState.projectionView,
                textureCrop = playerState.projectionTextureCrop,
                sourceColorInfo = sourceColorInfo,
                sourceFormat = playerState.projectionInputFormat(),
                plannedOutputDynamicRange = colorPipelineStatus.plannedOutputDynamicRange,
                plannedMetadataHandling = colorPipelineStatus.plannedMetadataHandling,
                dynamicRangePolicy = colorPipelineStatus.requestedDynamicRangePolicy,
                displayPeakLuminanceNits = colorPipelineStatus.display.maxLuminanceNits,
            )
        },
        onReset = { projectionView ->
            projectionView.keepScreenOn = false
            playerState.updateControlledColorRendererConfigured(false)
            playerState.updateVideoOutputSurface(null, null)
            playerState.attachProjectionVideoSurfaceView(null)
            playerState.attachHdr10PlusMetadataConsumer(null)
            projectionView.onPause()
        },
        onRelease = { projectionView ->
            projectionView.keepScreenOn = false
            playerState.updateControlledColorRendererConfigured(false)
            playerState.updateVideoOutputSurface(null, null)
            playerState.attachProjectionVideoSurfaceView(null)
            playerState.attachHdr10PlusMetadataConsumer(null)
            projectionView.releaseRenderer()
        },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun AndroidPlayerViewSurface(
    playerState: DefaultVideoPlayerState,
    contentScale: ContentScale,
    resolvedSurfaceType: SurfaceType,
) {
    val isScreenWakeRequested = playerState.isPlaying || playerState.isLoading
    val colorPipelineStatus by playerState.colorPipelineStatus.collectAsState()
    val nativeSurfaceColorConfigurator =
        remember(playerState) {
            AndroidNativeSurfaceColorConfigurator(
                onConfigured = playerState::updateNativeSurfaceDataSpaceConfigured,
                onConfigurationFailed = playerState::updateNativeSurfaceDataSpaceConfigurationFailed,
            )
        }
    DisposableEffect(nativeSurfaceColorConfigurator) {
        onDispose(nativeSurfaceColorConfigurator::detach)
    }
    AndroidView(
        modifier =
            contentScale.toCanvasModifier(
                playerState.aspectRatio,
                playerState.metadata.width,
                playerState.metadata.height,
            ),
        factory = { context ->
            try {
                // Create PlayerView with the appropriate surface type

                createPlayerViewWithSurfaceType(context, resolvedSurfaceType).apply {
                    keepScreenOn = isScreenWakeRequested
                    addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                        playerState.updateVideoOutputSurface(resolvedSurfaceType, view.display)
                    }
                    playerState.deactivateControlledColorRendererIfUnused()
                    playerState.attachProjectionVideoSurfaceView(null)
                    // Attach this view to the player state
                    playerState.attachPlayerView(this)
                    nativeSurfaceColorConfigurator.attach(videoSurfaceView as? SurfaceView)
                    nativeSurfaceColorConfigurator.configure(
                        dynamicRange = colorPipelineStatus.plannedOutputDynamicRange,
                        hasRenderedFirstFrame = playerState.hasRenderedFirstVideoFrameForColorVerification(),
                    )

                    if (playerState.exoPlayer != null) {
                        // Attach the player from the state
                        player = playerState.exoPlayer
                    }

                    useController = false
                    defaultArtwork = null
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)

                    // Map ContentScale to ExoPlayer resize modes
                    resizeMode = mapContentScaleToResizeMode(contentScale)
                    applyProjectionDefaults(
                        projection = playerState.projection,
                        controlMode = playerState.projectionViewControlMode,
                        textureCrop = playerState.projectionTextureCrop,
                    )

                    subtitleView?.visibility = playerState.nativeSubtitleVisibility()
                }
            } catch (e: Exception) {
                androidVideoLogger.e { "Error creating PlayerView: ${e.message}" }
                // Return an empty view in case of error
                PlayerView(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
            }
        },
        update = { playerView ->
            try {
                playerView.keepScreenOn = isScreenWakeRequested
                playerState.updateVideoOutputSurface(resolvedSurfaceType, playerView.display)
                nativeSurfaceColorConfigurator.attach(playerView.videoSurfaceView as? SurfaceView)
                nativeSurfaceColorConfigurator.configure(
                    dynamicRange = colorPipelineStatus.plannedOutputDynamicRange,
                    hasRenderedFirstFrame = playerState.hasRenderedFirstVideoFrameForColorVerification(),
                )
                if (playerState.exoPlayer != null) {
                    playerState.deactivateControlledColorRendererIfUnused()
                    playerState.attachProjectionVideoSurfaceView(null)
                    // Re-attach after LazyList recycle: onReset nulls playerView.player
                    // and calls onPause(). Without this, the surface stays blank until
                    // the user navigates away and back.
                    if (playerView.player == null) {
                        playerState.attachPlayerView(playerView)
                        playerView.onResume()
                    }
                    playerView.resizeMode = mapContentScaleToResizeMode(contentScale)
                    playerView.applyProjectionDefaults(
                        projection = playerState.projection,
                        controlMode = playerState.projectionViewControlMode,
                        textureCrop = playerState.projectionTextureCrop,
                    )
                    playerView.subtitleView?.visibility = playerState.nativeSubtitleVisibility()
                }
            } catch (e: Exception) {
                androidVideoLogger.e { "Error updating PlayerView: ${e.message}" }
            }
        },
        onReset = { playerView ->
            try {
                playerView.keepScreenOn = false
                nativeSurfaceColorConfigurator.detach(playerView.videoSurfaceView as? SurfaceView)
                playerState.updateVideoOutputSurface(null, null)
                // Clean up resources when the view is recycled in a LazyList
                playerView.player = null
                playerView.onPause()
            } catch (e: Exception) {
                androidVideoLogger.e { "Error resetting PlayerView: ${e.message}" }
            }
        },
        onRelease = { playerView ->
            try {
                playerView.keepScreenOn = false
                nativeSurfaceColorConfigurator.detach(playerView.videoSurfaceView as? SurfaceView)
                playerState.updateVideoOutputSurface(null, null)
                // Fully clean up the view on release
                playerView.player = null
            } catch (e: Exception) {
                androidVideoLogger.e { "Error releasing PlayerView: ${e.message}" }
            }
        },
    )
}

private fun DefaultVideoPlayerState.nativeSubtitleVisibility(): Int =
    if (
        subtitlesEnabled &&
        currentSubtitleTrack?.isEmbedded == true &&
        currentSubtitleTrack?.let { track -> androidSubtitleBackend?.supports(track) } != true
    ) {
        View.VISIBLE
    } else {
        View.GONE
    }

@OptIn(UnstableApi::class)
private fun mapContentScaleToResizeMode(contentScale: ContentScale): Int =
    when (contentScale) {
        ContentScale.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        ContentScale.FillBounds -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        ContentScale.Fit, ContentScale.Inside -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        ContentScale.FillWidth -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        ContentScale.FillHeight -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

@OptIn(UnstableApi::class)
private fun createPlayerViewWithSurfaceType(
    context: Context,
    surfaceType: SurfaceType,
): PlayerView =
    try {
        // First try to inflate the custom layouts
        val layoutId =
            when (surfaceType) {
                SurfaceType.Auto -> R.layout.player_view_surface
                SurfaceType.SurfaceView -> R.layout.player_view_surface
                SurfaceType.TextureView -> R.layout.player_view_texture
                SurfaceType.SphericalGlSurfaceView -> R.layout.player_view_spherical
                SurfaceType.ProjectedGlSurfaceView -> R.layout.player_view_texture
            }

        LayoutInflater.from(context).inflate(layoutId, null) as PlayerView
    } catch (e: Exception) {
        androidVideoLogger.e { "Error inflating PlayerView layout: ${e.message}, creating programmatically" }

        // Create PlayerView programmatically to avoid missing resource issues
        try {
            PlayerView(context).apply {
                // Fully disable controls to avoid inflating the controls layout
                useController = false

                // Configure the surface type programmatically
                when (surfaceType) {
                    SurfaceType.TextureView -> {
                        // Use TextureView if available
                        videoSurfaceView?.let { view ->
                            if (view is TextureView) {
                                androidVideoLogger.d { "Using TextureView" }
                            }
                        }
                    }

                    SurfaceType.Auto,
                    SurfaceType.SurfaceView,
                    -> {
                        // SurfaceView is the default
                        androidVideoLogger.d { "Using SurfaceView" }
                    }

                    SurfaceType.SphericalGlSurfaceView -> {
                        androidVideoLogger.d { "Using spherical GLSurfaceView" }
                    }

                    SurfaceType.ProjectedGlSurfaceView -> {
                        androidVideoLogger.d { "Using projection GLSurfaceView" }
                    }
                }

                // Disable features that could cause issues
                controllerAutoShow = false
                controllerHideOnTouch = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
        } catch (e2: Exception) {
            androidVideoLogger.e { "Error creating PlayerView programmatically: ${e2.message}" }
            // Last resort: create an empty view to avoid crashing
            throw e2
        }
    }

internal fun SurfaceType.resolveFor(
    projection: VideoProjectionSettings,
    textureCrop: VideoTextureCrop,
): SurfaceType =
    when (this) {
        SurfaceType.Auto -> {
            when {
                projection.requiresProjectionRenderer || !textureCrop.isDefaultTextureCrop ->
                    SurfaceType.ProjectedGlSurfaceView
                else -> SurfaceType.SurfaceView
            }
        }
        SurfaceType.TextureView,
        SurfaceType.SurfaceView,
        SurfaceType.SphericalGlSurfaceView,
        SurfaceType.ProjectedGlSurfaceView,
        -> this
    }

private fun PlayerView.applyProjectionDefaults(
    projection: VideoProjectionSettings,
    controlMode: VideoProjectionViewControlMode,
    textureCrop: VideoTextureCrop,
) {
    val sphericalSurface = videoSurfaceView as? SphericalGLSurfaceView ?: return
    sphericalSurface.setDefaultStereoMode(projection.toMedia3StereoMode())
    sphericalSurface.setUseSensorRotation(
        projection.usesMedia3SphericalProjection(textureCrop) &&
            controlMode.usesDeviceMotionFor(projection),
    )
}

private fun VideoProjectionSettings.toMedia3StereoMode(): Int =
    when (stereoLayout) {
        VideoStereoLayout.Mono -> C.STEREO_MODE_MONO
        VideoStereoLayout.SideBySide -> C.STEREO_MODE_LEFT_RIGHT
        VideoStereoLayout.OverUnder -> C.STEREO_MODE_TOP_BOTTOM
    }

@Composable
private fun BindAndroidActivity(playerState: DefaultVideoPlayerState) {
    val activity = LocalActivity.current as? ComponentActivity

    DisposableEffect(playerState, activity) {
        if (activity == null) {
            return@DisposableEffect onDispose { }
        }

        val listener =
            Consumer<PictureInPictureModeChangedInfo> { info ->
                playerState.onPictureInPictureModeChanged(info.isInPictureInPictureMode)
            }
        playerState.attachActivity(activity)
        activity.addOnPictureInPictureModeChangedListener(listener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            playerState.onPictureInPictureModeChanged(activity.isInPictureInPictureMode)
        }

        onDispose {
            activity.removeOnPictureInPictureModeChangedListener(listener)
            playerState.detachActivity(activity)
        }
    }
}

@Composable
fun AutoPipEffect(playerState: VideoPlayerState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalActivity.current as? ComponentActivity
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner, activity, playerState) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE && playerState.isPipEnabled) {
                    scope.coroutineContext[MonotonicFrameClock]?.let { monoticClock ->
                        activity?.lifecycleScope?.launch(context = Dispatchers.Main + monoticClock) {
                            playerState.enterPip()
                        }
                    }
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
