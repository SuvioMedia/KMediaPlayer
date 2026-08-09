@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopColorManagedTextureVideoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Uses color-managed GPU textures on supported desktop hosts, with explicit CPU/SDR fallback. */
@Composable
internal fun MpvVideoPlayerSurface(
    playerState: MpvVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
) {
    MpvVideoSurfaceContent(
        playerState = playerState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        onSurfaceAttached = onSurfaceAttached,
    )
}

@Composable
private fun MpvVideoSurfaceContent(
    playerState: MpvVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    onSurfaceAttached: () -> Unit = {},
) {
    val latestOnSurfaceAttached by rememberUpdatedState(onSurfaceAttached)
    val videoModifier =
        contentScale.toMpvSurfaceModifier(
            aspectRatio = playerState.aspectRatio,
            width = playerState.metadata.width,
            height = playerState.metadata.height,
        )

    LaunchedEffect(playerState, contentScale, playerState.aspectRatio) {
        playerState.setContentScaleMode(contentScale)
    }
    LaunchedEffect(
        playerState,
        playerState.projection,
        playerState.projectionView,
        playerState.projectionTextureCrop,
    ) {
        playerState.updateNativeMacProjection()
    }
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (playerState.usesWindowsColorManagedTexture) {
            MpvWindowsTextureVideoSurface(
                playerState = playerState,
                contentScale = contentScale,
                overlay = overlay,
                onSurfaceAttached = { latestOnSurfaceAttached() },
            )
        } else if (playerState.usesMacColorManagedTexture) {
            MpvMacTextureVideoSurface(
                playerState = playerState,
                contentScale = contentScale,
                overlay = overlay,
                onSurfaceAttached = { latestOnSurfaceAttached() },
            )
        } else if (playerState.usesLinuxColorManagedTexture) {
            MpvLinuxTextureVideoSurface(
                playerState = playerState,
                contentScale = contentScale,
                overlay = overlay,
                onSurfaceAttached = { latestOnSurfaceAttached() },
            )
        } else {
            MpvSoftwareVideoPlayerSurface(playerState, videoModifier, overlay)
            DisposableEffect(playerState) {
                latestOnSurfaceAttached()
                onDispose { }
            }
        }
    }
}

/** Linux libmpv render API -> rotating fenced GBM DMA-BUF pool -> Nucleus EGL/Skia scene. */
@Composable
private fun MpvLinuxTextureVideoSurface(
    playerState: MpvVideoPlayerState,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    onSurfaceAttached: () -> Unit,
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val renderSize by rememberStableTextureRenderSize(surfaceSize)

    LaunchedEffect(playerState) {
        while (isActive) {
            withFrameNanos { }
            val currentRenderSize = renderSize
            if (currentRenderSize.width <= 0 || currentRenderSize.height <= 0) {
                delay(IDLE_REFRESH_INTERVAL_MS)
                continue
            }
            if (playerState.hasMedia) {
                val produced =
                    withContext(Dispatchers.Default) {
                        playerState.renderLinuxTextureFrame(currentRenderSize.width, currentRenderSize.height)
                    }
                if (!produced && !playerState.isPlaying && !playerState.isLoading && !playerState.isSeeking) {
                    delay(PAUSED_REFRESH_INTERVAL_MS)
                }
            } else {
                delay(IDLE_REFRESH_INTERVAL_MS)
            }
        }
    }

    DesktopColorManagedTextureVideoView(
        streamController = playerState.linuxTextureStreamController,
        modifier = Modifier.fillMaxSize().onSizeChanged { surfaceSize = it },
        contentScale = contentScale,
        onHostCapabilitiesChanged = playerState::onLinuxTextureHostCapabilitiesChanged,
        onSurfaceAttached = onSurfaceAttached,
        overlay = overlay,
    )
}

/** macOS libmpv render API -> rotating IOSurface pool -> Nucleus Metal/Skia scene. */
@Composable
private fun MpvMacTextureVideoSurface(
    playerState: MpvVideoPlayerState,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    onSurfaceAttached: () -> Unit,
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val renderSize by rememberStableTextureRenderSize(surfaceSize)

    LaunchedEffect(playerState) {
        while (isActive) {
            withFrameNanos { }
            val currentRenderSize = renderSize
            if (currentRenderSize.width <= 0 || currentRenderSize.height <= 0) {
                delay(IDLE_REFRESH_INTERVAL_MS)
                continue
            }
            if (playerState.hasMedia) {
                val produced =
                    withContext(Dispatchers.Default) {
                        playerState.renderMacTextureFrame(currentRenderSize.width, currentRenderSize.height)
                    }
                if (!produced && !playerState.isPlaying && !playerState.isLoading && !playerState.isSeeking) {
                    delay(PAUSED_REFRESH_INTERVAL_MS)
                }
            } else {
                delay(IDLE_REFRESH_INTERVAL_MS)
            }
        }
    }
    DisposableEffect(playerState) {
        onDispose(playerState::onMacTextureSurfaceDetached)
    }

    DesktopColorManagedTextureVideoView(
        streamController = playerState.macTextureStreamController,
        modifier = Modifier.fillMaxSize().onSizeChanged { surfaceSize = it },
        contentScale = contentScale,
        onHostCapabilitiesChanged = playerState::onMacTextureHostCapabilitiesChanged,
        onSurfaceAttached = onSurfaceAttached,
        overlay = overlay,
    )
}

/** Windows libmpv render API -> shared keyed D3D11 RGBA8/FP16 texture -> Nucleus scene. */
@Composable
private fun MpvWindowsTextureVideoSurface(
    playerState: MpvVideoPlayerState,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    onSurfaceAttached: () -> Unit,
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val sourceWidth = playerState.metadata.width ?: 0
    val sourceHeight = playerState.metadata.height ?: 0
    val renderSize by
        rememberUpdatedState(
            if (sourceWidth > 0 && sourceHeight > 0) {
                IntSize(sourceWidth, sourceHeight)
            } else {
                surfaceSize
            },
        )

    LaunchedEffect(playerState) {
        // Windows enters a modal WM_ENTERSIZEMOVE loop while the user drags a
        // window edge. Compose keeps drawing there, but UI-dispatcher coroutine
        // continuations are not guaranteed to run at video cadence. Keep mpv's
        // producer on a background dispatcher; TextureViewStreamController is
        // explicitly thread-safe and wakes the Tao scene for every new frame.
        withContext(Dispatchers.Default) {
            while (isActive) {
                val currentRenderSize = renderSize
                if (currentRenderSize.width <= 0 || currentRenderSize.height <= 0) {
                    delay(IDLE_REFRESH_INTERVAL_MS)
                    continue
                }
                if (playerState.hasMedia) {
                    val renderResult =
                        playerState.renderWindowsTextureFrame(currentRenderSize.width, currentRenderSize.height)
                    delay(
                        if (!playerState.isPlaying && !playerState.isLoading && !playerState.isSeeking) {
                            PAUSED_REFRESH_INTERVAL_MS
                        } else {
                            when (renderResult) {
                                MpvWindowsTextureRenderResult.RETRY -> CONTENDED_TEXTURE_RETRY_INTERVAL_MS
                                else -> ACTIVE_TEXTURE_REFRESH_INTERVAL_MS
                            }
                        },
                    )
                } else {
                    delay(IDLE_REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    DesktopColorManagedTextureVideoView(
        streamController = playerState.windowsTextureStreamController,
        modifier = Modifier.fillMaxSize().onSizeChanged { surfaceSize = it },
        contentScale = contentScale,
        onHostCapabilitiesChanged = playerState::onWindowsTextureHostCapabilitiesChanged,
        onSurfaceAttached = onSurfaceAttached,
        overlay = overlay,
    )
}

/** Renders libmpv's software BGR0 target into Skia when native GPU output is unavailable. */
@Composable
private fun MpvSoftwareVideoPlayerSurface(
    playerState: MpvVideoPlayerState,
    modifier: Modifier,
    overlay: @Composable () -> Unit,
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val frame by playerState.currentFrame

    LaunchedEffect(playerState, surfaceSize) {
        if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            if (playerState.hasMedia) {
                withContext(Dispatchers.Default) {
                    playerState.renderFrame(surfaceSize.width, surfaceSize.height)
                }
                if (!playerState.isPlaying && !playerState.isLoading && !playerState.isSeeking) {
                    delay(PAUSED_REFRESH_INTERVAL_MS)
                }
            } else {
                delay(IDLE_REFRESH_INTERVAL_MS)
            }
        }
    }

    Box(
        modifier =
            modifier
                .background(Color.Black)
                .onSizeChanged { surfaceSize = it },
    ) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
            )
        }
        overlay()
    }
}

private const val PAUSED_REFRESH_INTERVAL_MS = 250L
private const val IDLE_REFRESH_INTERVAL_MS = 100L
private const val ACTIVE_TEXTURE_REFRESH_INTERVAL_MS = 8L
private const val CONTENDED_TEXTURE_RETRY_INTERVAL_MS = 1L
private const val TEXTURE_RESIZE_SETTLE_INTERVAL_MS = 200L
@Composable
private fun rememberStableTextureRenderSize(surfaceSize: IntSize): State<IntSize> {
    val stableSize = remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(surfaceSize) {
        if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return@LaunchedEffect
        if (stableSize.value != IntSize.Zero) {
            delay(TEXTURE_RESIZE_SETTLE_INTERVAL_MS)
        }
        stableSize.value = surfaceSize
    }
    return stableSize
}

@Composable
private fun ContentScale.toMpvSurfaceModifier(
    aspectRatio: Float,
    width: Int?,
    height: Int?,
): Modifier =
    when (this) {
        ContentScale.Fit,
        ContentScale.Inside,
        -> Modifier.fillMaxHeight().aspectRatio(aspectRatio)

        ContentScale.FillWidth -> Modifier.fillMaxWidth().aspectRatio(aspectRatio)
        ContentScale.FillHeight -> Modifier.fillMaxHeight().aspectRatio(aspectRatio)
        ContentScale.Crop,
        ContentScale.FillBounds,
        -> Modifier.fillMaxSize()

        ContentScale.None -> Modifier.width((width ?: 0).dp).height((height ?: 0).dp)
        else -> Modifier
    }
