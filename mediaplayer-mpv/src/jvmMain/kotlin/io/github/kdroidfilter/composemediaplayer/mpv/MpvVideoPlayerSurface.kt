package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Frame
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
    var nativeAttachFailed by remember(playerState) { mutableStateOf(false) }
    val useNativeMacSurface =
        playerState.hasMedia && playerState.canUseNativeMacSurface && !nativeAttachFailed

    LaunchedEffect(playerState, contentScale) {
        playerState.setCropMode(contentScale == ContentScale.Crop)
    }

    if (useNativeMacSurface) {
        MpvDedicatedNativeMacWindow(
            playerState = playerState,
            modifier = modifier,
            overlay = overlay,
            onAttachFailed = { nativeAttachFailed = true },
        )
    } else {
        MpvSoftwareVideoPlayerSurface(
            playerState = playerState,
            modifier = modifier,
            overlay = overlay,
        )
    }
}

@Composable
private fun MpvDedicatedNativeMacWindow(
    playerState: MpvVideoPlayerState,
    modifier: Modifier,
    overlay: @Composable () -> Unit,
    onAttachFailed: () -> Unit,
) {
    var visible by remember(playerState) { mutableStateOf(true) }
    val windowState =
        rememberWindowState(
            position = WindowPosition.PlatformDefault,
            width = NATIVE_WINDOW_WIDTH_DP.dp,
            height = NATIVE_WINDOW_HEIGHT_DP.dp,
        )

    LaunchedEffect(playerState.isPlaying) {
        if (playerState.isPlaying) visible = true
    }

    Box(modifier = modifier.background(Color.Black)) {
        if (!visible) overlay()
    }
    if (!visible) return

    val closeWindow = {
        playerState.isFullscreen = false
        playerState.pause()
        visible = false
    }
    Window(
        onCloseRequest = closeWindow,
        state = windowState,
        title = "Compose Media Player MPV ${System.identityHashCode(playerState)}",
        undecorated = true,
        transparent = true,
        resizable = true,
        focusable = true,
        alwaysOnTop = false,
        onKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && playerState.isFullscreen) {
                playerState.isFullscreen = false
                true
            } else {
                false
            }
        },
    ) {
        val nativeWindow = window
        LaunchedEffect(playerState, nativeWindow) {
            synchronizeNativeFullscreen(
                requestedFullscreen = { playerState.isFullscreen },
                publishFullscreen = { playerState.isFullscreen = it },
                setNativeFullscreen = {
                    MpvMacNativeBridge.nSetWindowFullscreen(nativeWindow, it)
                },
                isNativeFullscreen = {
                    MpvMacNativeBridge.nIsWindowFullscreen(nativeWindow)
                },
            )
        }
        DisposableEffect(playerState, nativeWindow) {
            nativeWindow.minimumSize = Dimension(NATIVE_WINDOW_MIN_WIDTH, NATIVE_WINDOW_MIN_HEIGHT)
            val attachment =
                MpvNativeMacWindowAttachment(
                    playerState = playerState,
                    window = nativeWindow,
                    onAttachFailed = onAttachFailed,
                )
            attachment.start()
            onDispose(attachment::close)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
            MpvNativeWindowChrome(
                window = nativeWindow,
                fullscreen = playerState.isFullscreen,
                onClose = closeWindow,
                onToggleFullscreen = { playerState.isFullscreen = !playerState.isFullscreen },
            )
        }
    }
}

@Composable
private fun MpvNativeWindowChrome(
    window: AwtWindow,
    fullscreen: Boolean,
    onClose: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    if (fullscreen) return
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(NATIVE_WINDOW_DRAG_HEIGHT_DP.dp)
                .background(Color.Black)
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MpvWindowControlDot(Color(0xFFFF5F57), onClose)
        MpvWindowControlDot(Color(0xFFFFBD2E)) {
            (window as? Frame)?.extendedState = Frame.ICONIFIED
        }
        MpvWindowControlDot(Color(0xFF28C840), onToggleFullscreen)
    }
}

private suspend fun synchronizeNativeFullscreen(
    requestedFullscreen: () -> Boolean,
    publishFullscreen: (Boolean) -> Unit,
    setNativeFullscreen: (Boolean) -> Boolean,
    isNativeFullscreen: () -> Boolean,
) {
    var submittedRequest: Boolean? = null
    var transitionDeadline = 0L
    while (currentCoroutineContext().isActive) {
        val requested = requestedFullscreen()
        if (submittedRequest != requested) {
            if (runCatching { setNativeFullscreen(requested) }.getOrDefault(false)) {
                submittedRequest = requested
                transitionDeadline =
                    System.nanoTime() + NATIVE_FULLSCREEN_TRANSITION_TIMEOUT_NANOS
            }
        }
        val actual = runCatching(isNativeFullscreen).getOrNull()
        when {
            actual == requested -> {
                transitionDeadline = 0L
            }
            actual != null &&
                submittedRequest == requested &&
                (transitionDeadline == 0L || System.nanoTime() >= transitionDeadline) -> {
                // Escape and the native macOS full-screen controls can end full screen without a
                // Compose key event. Keep the public player state aligned with the actual NSWindow.
                publishFullscreen(actual)
                submittedRequest = actual
                transitionDeadline = 0L
            }
        }
        delay(NATIVE_FULLSCREEN_POLL_INTERVAL_MS)
    }
}

@Composable
private fun MpvWindowControlDot(
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .padding(end = 8.dp)
                .size(12.dp)
                .background(color, CircleShape)
                .clickable(onClick = onClick),
    )
}

private class MpvNativeMacWindowAttachment(
    private val playerState: MpvVideoPlayerState,
    private val window: AwtWindow,
    private val onAttachFailed: () -> Unit,
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
private const val NATIVE_WINDOW_WIDTH_DP = 960
private const val NATIVE_WINDOW_HEIGHT_DP = 540
private const val NATIVE_WINDOW_MIN_WIDTH = 480
private const val NATIVE_WINDOW_MIN_HEIGHT = 270
private const val NATIVE_WINDOW_DRAG_HEIGHT_DP = 34
private const val NATIVE_ATTACH_MAX_ATTEMPTS = 4
private const val NATIVE_ATTACH_RETRY_MS = 75
private const val NATIVE_FULLSCREEN_POLL_INTERVAL_MS = 100L
private const val NATIVE_FULLSCREEN_TRANSITION_TIMEOUT_NANOS = 3_000_000_000L
