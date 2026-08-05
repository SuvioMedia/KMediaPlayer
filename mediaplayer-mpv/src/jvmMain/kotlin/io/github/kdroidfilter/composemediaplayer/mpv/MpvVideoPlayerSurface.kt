@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoSurface
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Uses a Tao-hosted native macOS OpenGL/EDR view, with Skia as the portable fallback. */
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
    var nativeAttachFailed by remember(playerState) { mutableStateOf(false) }
    val useNativeMacSurface =
        playerState.hasMedia && playerState.canUseNativeMacSurface && !nativeAttachFailed

    LaunchedEffect(playerState, contentScale) {
        playerState.setCropMode(contentScale == ContentScale.Crop)
    }
    LaunchedEffect(playerState.hasMedia) {
        if (playerState.hasMedia) nativeAttachFailed = false
    }

    if (useNativeMacSurface) {
        val surface =
            remember(playerState, useNativeMacSurface) {
                TaoNativeVideoSurface(
                    kind = TaoNativeVideoSurfaceKind.MACOS_NS_VIEW,
                    createHandle = playerState::createNativeMacView,
                    disposeHandle = playerState::disposeNativeMacView,
                )
            }
        TaoNativeVideoView(
            surface = surface,
            modifier = modifier.background(Color.Black),
            overlay = overlay,
            onAttached = { latestOnSurfaceAttached() },
            onUnavailable = { nativeAttachFailed = true },
        )
    } else {
        MpvSoftwareVideoPlayerSurface(playerState, modifier, overlay)
        DisposableEffect(playerState) {
            latestOnSurfaceAttached()
            onDispose { }
        }
    }
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
