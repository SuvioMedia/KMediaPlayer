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
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoSurface
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.desktop.tao.TaoNativeVideoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Uses a Tao-hosted native macOS Metal or OpenGL/EDR view, with Skia as the portable fallback. */
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
        playerState.canUseNativeMacSurface &&
            !nativeAttachFailed
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
    LaunchedEffect(playerState.hasMedia) {
        if (playerState.hasMedia) nativeAttachFailed = false
    }
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
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
                // Keep the Nucleus native-view host and its sibling Compose scene on the complete
                // player viewport. An NSView mounted only at the media's aspect-ratio rectangle
                // would sit above the root Skia scene and hide every control intersecting video.
                // Ordinary video lets libmpv apply keep-aspect/panscan in this framebuffer. A
                // projected source fills the intermediate texture instead, so the native GPU
                // projection pass owns the only aspect-ratio transform.
                modifier = Modifier.fillMaxSize().background(Color.Black),
                overlay = { Box(modifier = Modifier.fillMaxSize()) { overlay() } },
                onAttached = {
                    playerState.onNativeMacSurfaceAttached()
                    latestOnSurfaceAttached()
                },
                onUnavailable = { nativeAttachFailed = true },
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
