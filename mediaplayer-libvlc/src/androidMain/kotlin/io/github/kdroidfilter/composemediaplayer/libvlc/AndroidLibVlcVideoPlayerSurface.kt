@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import io.github.kdroidfilter.composemediaplayer.util.FullScreenLayout

@Composable
internal fun AndroidLibVlcVideoPlayerSurface(
    playerState: AndroidLibVlcVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    val projection = playerState.projection
    val textureCrop = playerState.projectionTextureCrop
    val projectionUnsupported = playerState.projectionUnsupported
    val contentScaleUnsupported = !contentScale.isSupportedAndroidLibVlcContentScale()
    LaunchedEffect(playerState, projection, textureCrop, contentScale) {
        playerState.updateSurfaceRenderingRoute(contentScale)
    }

    val callback =
        remember(playerState) {
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) {
                    if (holder.surface.isValid && width > 0 && height > 0) {
                        playerState.attachSurface(holder.surface, width, height)
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    playerState.detachSurface(holder.surface)
                }
            }
        }

    val content: @Composable (Modifier) -> Unit = { targetModifier ->
        Box(modifier = targetModifier.background(Color.Black)) {
            if (!projectionUnsupported && !contentScaleUnsupported) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).also { view ->
                            view.holder.addCallback(callback)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view ->
                        val surface = view.holder.surface
                        if (surface.isValid && view.width > 0 && view.height > 0) {
                            playerState.attachSurface(surface, view.width, view.height)
                        }
                    },
                    onRelease = { view ->
                        val releasedSurface = view.holder.surface
                        view.holder.removeCallback(callback)
                        playerState.detachSurface(releasedSurface)
                    },
                )
            }
            overlay()
        }
    }

    if (playerState.isFullscreen) {
        FullScreenLayout(
            modifier = Modifier,
            onDismissRequest = { playerState.isFullscreen = false },
        ) {
            content(Modifier.fillMaxSize())
        }
    } else {
        content(modifier)
    }
}

internal fun ContentScale.isSupportedAndroidLibVlcContentScale(): Boolean = this == ContentScale.Fit
