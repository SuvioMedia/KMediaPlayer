package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.os.Build
import android.view.LayoutInflater
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
import androidx.compose.runtime.key
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
    if (LocalInspectionMode.current || playerState is PreviewableVideoPlayerState) {
        VideoPlayerSurfacePreview(modifier = modifier, overlay = overlay)
        return
    }
    require(playerState is DefaultVideoPlayerState) {
        "Unsupported renderable player state: ${playerState::class}"
    }

    // Single source of truth — no rememberSaveable, drive directly from playerState
    val isFullscreen = playerState.isFullscreen
    val isPipFullScreen = playerState.isPipFullScreen

    BindAndroidActivity(playerState = playerState)
    AutoPipEffect(playerState = playerState)

    // Exit fullscreen when returning from PiP
    LaunchedEffect(playerState.isPipActive) {
        if (!playerState.isPipActive && playerState.isPipFullScreen) {
            delay(300.milliseconds)
            playerState.togglePipFullScreen()
        }
    }

    DisposableEffect(playerState) {
        onDispose {
            try {
                // Detach the view from the player
                playerState.attachPlayerView(null)
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
                playerState.toggleFullscreen()
            },
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black),
            ) {
                VideoPlayerContent(
                    playerState = playerState,
                    modifier = Modifier.fillMaxHeight(),
                    overlay = overlay,
                    contentScale = contentScale,
                    surfaceType = surfaceType,
                )
            }
        }
    } else {
        VideoPlayerContent(
            playerState = playerState,
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
            val resolvedSurfaceType =
                surfaceType.resolveFor(
                    projection = playerState.projection,
                    textureCrop = playerState.projectionTextureCrop,
                )
            key(resolvedSurfaceType) {
                if (resolvedSurfaceType == SurfaceType.ProjectedGlSurfaceView) {
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

            // Add a Compose-based subtitle layer
            if (playerState.subtitlesEnabled &&
                playerState.currentSubtitleTrack != null &&
                playerState.currentSubtitleTrack?.isEmbedded != true
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

    AndroidView(
        modifier =
            contentScale.toCanvasModifier(
                playerState.aspectRatio,
                playerState.metadata.width,
                playerState.metadata.height,
            ),
        factory = { context ->
            AndroidProjectionGlSurfaceView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                callback =
                    object : AndroidProjectionGlSurfaceView.Callback {
                        override fun onVideoSurfaceCreated(surface: android.view.Surface) {
                            playerState.attachProjectionVideoSurface(surface)
                        }

                        override fun onProjectionRendererError(message: String) {
                            androidVideoLogger.e { message }
                        }
                    }
                configure(
                    projection = playerState.projection,
                    projectionView = playerState.projectionView,
                    textureCrop = playerState.projectionTextureCrop,
                )
            }
        },
        update = { projectionView ->
            playerState.attachPlayerView(null)
            projectionView.videoSurface?.let(playerState::attachProjectionVideoSurface)
            projectionView.onResume()
            projectionView.configure(
                projection = playerState.projection,
                projectionView = playerState.projectionView,
                textureCrop = playerState.projectionTextureCrop,
            )
        },
        onReset = { projectionView ->
            playerState.attachProjectionVideoSurface(null)
            projectionView.onPause()
        },
        onRelease = { projectionView ->
            playerState.attachProjectionVideoSurface(null)
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
                    playerState.attachProjectionVideoSurface(null)
                    // Attach this view to the player state
                    playerState.attachPlayerView(this)

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
                if (playerState.exoPlayer != null) {
                    playerState.attachProjectionVideoSurface(null)
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
                // Clean up resources when the view is recycled in a LazyList
                playerView.player = null
                playerView.onPause()
            } catch (e: Exception) {
                androidVideoLogger.e { "Error resetting PlayerView: ${e.message}" }
            }
        },
        onRelease = { playerView ->
            try {
                // Fully clean up the view on release
                playerView.player = null
            } catch (e: Exception) {
                androidVideoLogger.e { "Error releasing PlayerView: ${e.message}" }
            }
        },
    )
}

private fun VideoPlayerState.nativeSubtitleVisibility(): Int =
    if (subtitlesEnabled && currentSubtitleTrack?.isEmbedded == true) {
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
                SurfaceType.Auto -> R.layout.player_view_texture
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
                    SurfaceType.Auto,
                    SurfaceType.TextureView,
                    -> {
                        // Use TextureView if available
                        videoSurfaceView?.let { view ->
                            if (view is TextureView) {
                                androidVideoLogger.d { "Using TextureView" }
                            }
                        }
                    }

                    SurfaceType.SurfaceView -> {
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

private fun SurfaceType.resolveFor(
    projection: VideoProjectionSettings,
    textureCrop: VideoTextureCrop,
): SurfaceType =
    when (this) {
        SurfaceType.Auto -> {
            when {
                projection.usesAndroidCustomProjectionRenderer(textureCrop) -> SurfaceType.ProjectedGlSurfaceView
                projection.usesMedia3SphericalProjection(textureCrop) -> SurfaceType.SphericalGlSurfaceView
                else -> SurfaceType.TextureView
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
