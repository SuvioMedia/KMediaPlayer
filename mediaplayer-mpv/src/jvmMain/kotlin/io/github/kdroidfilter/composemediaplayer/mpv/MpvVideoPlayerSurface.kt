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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.Window as AwtWindow

/**
 * Uses a native macOS OpenGL/EDR layer whenever both the bridge and libmpv GPU renderer are
 * available. The existing BGR0/Skia renderer remains the automatic fallback on every platform.
 */
@Composable
internal fun MpvVideoPlayerSurface(
    playerState: MpvVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
) {
    LaunchedEffect(playerState, contentScale) {
        playerState.setCropMode(contentScale == ContentScale.Crop)
    }
    MpvSoftwareVideoPlayerSurface(
        playerState = playerState,
        modifier = modifier,
        overlay = overlay,
    )
}

/** Uses the caller-owned player window for native MPV output instead of opening one implicitly. */
@Composable
internal fun MpvVideoPlayerWindowSurface(
    playerState: MpvVideoPlayerState,
    window: AwtWindow,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
) {
    var nativeAttachFailed by remember(playerState, window) { mutableStateOf(false) }
    val useNativeMacSurface =
        playerState.hasMedia && playerState.canUseNativeMacSurface && !nativeAttachFailed

    LaunchedEffect(playerState, contentScale) {
        playerState.setCropMode(contentScale == ContentScale.Crop)
    }
    if (!useNativeMacSurface) {
        MpvSoftwareVideoPlayerSurface(playerState, modifier, overlay)
        DisposableEffect(playerState, window) {
            onSurfaceAttached()
            onDispose { }
        }
        return
    }

    DisposableEffect(playerState, window) {
        val attachment =
            MpvNativeMacWindowAttachment(
                playerState = playerState,
                window = window,
                onAttachFailed = { nativeAttachFailed = true },
                onAttached = onSurfaceAttached,
            )
        attachment.start()
        onDispose(attachment::close)
    }
    Box(modifier = modifier) {
        overlay()
    }
}

private class MpvNativeMacWindowAttachment(
    private val playerState: MpvVideoPlayerState,
    private val window: AwtWindow,
    private val onAttachFailed: () -> Unit,
    private val onAttached: () -> Unit = {},
) : ComponentAdapter() {
    private var disposed = false
    private var attached = false
    private var attachScheduled = false
    private var attachAttempts = 0
    private var retryTimer: Timer? = null

    fun start() {
        window.addComponentListener(this)
        scheduleAttach()
    }

    fun close() {
        disposed = true
        retryTimer?.stop()
        retryTimer = null
        window.removeComponentListener(this)
        detachNativeWindow()
    }

    override fun componentShown(event: ComponentEvent?) {
        attachAttempts = 0
        scheduleAttach()
    }

    override fun componentHidden(event: ComponentEvent?) = detachNativeWindow()

    private fun scheduleAttach() {
        if (disposed || attached || attachScheduled) return
        attachScheduled = true
        SwingUtilities.invokeLater {
            attachScheduled = false
            if (disposed || attached || !window.isDisplayable || !window.isShowing) return@invokeLater
            attached = playerState.attachNativeMacWindow(window)
            if (attached) onAttached()
            if (!attached && !disposed) {
                attachAttempts++
                if (attachAttempts >= NATIVE_ATTACH_MAX_ATTEMPTS) {
                    onAttachFailed()
                } else {
                    retryTimer?.stop()
                    retryTimer =
                        Timer(NATIVE_ATTACH_RETRY_MS) {
                            retryTimer = null
                            scheduleAttach()
                        }.apply {
                            isRepeats = false
                            start()
                        }
                }
            }
        }
    }

    private fun detachNativeWindow() {
        if (!attached) return
        playerState.detachNativeMacWindow()
        attached = false
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
private const val NATIVE_ATTACH_MAX_ATTEMPTS = 4
private const val NATIVE_ATTACH_RETRY_MS = 75
