@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

    val context = LocalContext.current
    val requestHdrWindow =
        playerState.requestsHdrWindow && !projectionUnsupported && !contentScaleUnsupported
    DisposableEffect(context, requestHdrWindow) {
        val window = context.findActivity()?.window
        val previousColorMode = window?.colorMode
        if (requestHdrWindow) window?.colorMode = ActivityInfo.COLOR_MODE_HDR
        onDispose {
            if (
                requestHdrWindow &&
                window != null &&
                previousColorMode != null &&
                window.colorMode == ActivityInfo.COLOR_MODE_HDR
            ) {
                window.colorMode = previousColorMode
            }
        }
    }

    val content: @Composable (Modifier) -> Unit = { targetModifier ->
        Box(modifier = targetModifier.background(Color.Black)) {
            if (!projectionUnsupported && !contentScaleUnsupported) {
                AndroidView(
                    factory = { context ->
                        AndroidLibVlcSurfaceHost(context, playerState)
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = AndroidLibVlcSurfaceHost::attachIfReady,
                    onRelease = AndroidLibVlcSurfaceHost::release,
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

private class AndroidLibVlcSurfaceHost(
    context: Context,
    private val playerState: AndroidLibVlcVideoPlayerState,
) : FrameLayout(context) {
    private val videoView = SurfaceView(context)
    private val subtitleView = SurfaceView(context)
    private val videoHolder = videoView.holder
    private val subtitleHolder = subtitleView.holder
    private val surfaceCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                attachIfReady()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                attachIfReady()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                playerState.detachSurface(holder.surface)
            }
        }

    init {
        videoHolder.setFormat(PixelFormat.OPAQUE)
        subtitleView.setZOrderMediaOverlay(true)
        subtitleHolder.setFormat(PixelFormat.TRANSLUCENT)
        videoHolder.addCallback(surfaceCallback)
        subtitleHolder.addCallback(surfaceCallback)
        val layout = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(videoView, layout)
        addView(subtitleView, layout)
    }

    fun attachIfReady() {
        val video = videoHolder.surface
        val subtitles = subtitleHolder.surface
        val frame = videoHolder.surfaceFrame
        if (video.isValid && subtitles.isValid && frame.width() > 0 && frame.height() > 0) {
            playerState.attachSurfaces(video, subtitles, frame.width(), frame.height())
        }
    }

    fun release() {
        val video = videoHolder.surface
        videoHolder.removeCallback(surfaceCallback)
        subtitleHolder.removeCallback(surfaceCallback)
        playerState.detachSurface(video)
        removeAllViews()
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
