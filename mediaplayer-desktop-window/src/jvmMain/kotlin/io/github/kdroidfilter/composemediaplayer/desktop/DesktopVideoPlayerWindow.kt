package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.composemediaplayer.BackendVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Window as AwtWindow

/**
 * Opens the full-size player in an explicit independent desktop window.
 * The catalog/application window remains untouched and no surface API opens a hidden window.
 */
@Composable
public fun DesktopVideoPlayerWindow(
    session: DesktopPlaybackSession,
    visible: Boolean,
    onCloseRequest: () -> Unit,
    title: String = "Compose Media Player",
    windowState: WindowState = rememberWindowState(width = 960.dp, height = 540.dp),
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable (VideoPlayerState) -> Unit = {},
) {
    val playerState by session.playerState.collectAsState()
    if (!visible || playerState == null) return
    val player = checkNotNull(playerState)

    DesktopVideoPlayerWindow(
        playerState = player,
        visible = true,
        onCloseRequest = {
            session.close()
            onCloseRequest()
        },
        title = title,
        windowState = windowState,
        contentScale = contentScale,
        overlay = overlay,
        onSurfaceAttached = { session.notifySurfaceAttached(player) },
    )
}

/** Explicit dedicated window for callers that already own a single backend state. */
@Suppress("CyclomaticComplexMethod")
@Composable
public fun DesktopVideoPlayerWindow(
    playerState: VideoPlayerState,
    visible: Boolean,
    onCloseRequest: () -> Unit,
    title: String = "Compose Media Player",
    windowState: WindowState = rememberWindowState(width = 960.dp, height = 540.dp),
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable (VideoPlayerState) -> Unit = {},
    onSurfaceAttached: (VideoPlayerState) -> Unit = {},
) {
    if (!visible) return
    val player = playerState
    // Native macOS renderers resolve the caller-owned NSWindow through its AWT title. Keep the
    // underlying identifier unique even when the catalog and player use the same visible title.
    val nativeWindowTitle =
        remember(player, title) {
            "$title — native-player-${System.identityHashCode(player).toUInt().toString(16)}"
        }

    Window(
        onCloseRequest = {
            player.pause()
            onCloseRequest()
        },
        state = windowState,
        title = nativeWindowTitle,
        // Compose Desktop only permits a transparent Skia surface on an undecorated AWT frame.
        // The native provider promotes the underlying NSWindow to ordinary AppKit chrome before
        // the video layer is attached, preserving both native controls and transparent overlays.
        undecorated = true,
        transparent = true,
        resizable = true,
        focusable = true,
        onKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                if (player.isFullscreen || windowState.placement == WindowPlacement.Fullscreen) {
                    player.isFullscreen = false
                } else {
                    player.pause()
                    onCloseRequest()
                }
                true
            } else {
                false
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val provider = player as? DesktopVideoWindowSurfaceProvider
            val nativeWindowReady = synchronizeNativeWindow(player, provider, window, windowState)

            if (provider != null && nativeWindowReady) {
                provider.RenderDesktopVideoWindowSurface(
                    window = window,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    overlay = { overlay(player) },
                    onSurfaceAttached = { onSurfaceAttached(player) },
                )
            } else if (provider == null) {
                BackendVideoPlayerSurface(
                    playerState = player,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    overlay = { overlay(player) },
                )
                androidx.compose.runtime.DisposableEffect(player, window) {
                    onSurfaceAttached(player)
                    onDispose { }
                }
            }
        }
    }
}

@Composable
private fun synchronizeNativeWindow(
    player: VideoPlayerState,
    provider: DesktopVideoWindowSurfaceProvider?,
    window: AwtWindow,
    windowState: WindowState,
): Boolean {
    val fullscreen = player.isFullscreen
    var nativeTransitionTarget by
        remember(provider, window) { mutableStateOf<Boolean?>(fullscreen) }
    var nativeTransitionDeadlineNanos by
        remember(provider, window) {
            mutableStateOf(System.nanoTime() + NATIVE_WINDOW_TRANSITION_TIMEOUT_NANOS)
        }
    var nativeWindowReady by remember(provider, window) { mutableStateOf(provider == null) }

    LaunchedEffect(provider, window) {
        // AppKit owns the native NSWindow and can be busy inside a live-resize/Zoom animation.
        // Never synchronously wait for its main queue from Compose's UI dispatcher: AppKit may
        // itself be waiting for JBR to lay out the content view.
        withContext(Dispatchers.IO) {
            provider?.configureNativeWindow(window)
        }
        nativeWindowReady = true
        while (true) {
            val nativeFullscreen =
                withContext(Dispatchers.IO) {
                    provider?.nativeWindowFullscreenState(window)
                }
            if (nativeFullscreen != null) {
                val transitionTarget = nativeTransitionTarget
                if (transitionTarget != null) {
                    if (nativeFullscreen == transitionTarget) {
                        nativeTransitionTarget = null
                    } else if (System.nanoTime() >= nativeTransitionDeadlineNanos) {
                        nativeTransitionTarget = null
                        player.isFullscreen = nativeFullscreen
                    }
                } else if (nativeFullscreen != player.isFullscreen) {
                    player.isFullscreen = nativeFullscreen
                }
            }
            delay(NATIVE_WINDOW_STATE_POLL_MILLIS)
        }
    }
    LaunchedEffect(provider, window, fullscreen, nativeWindowReady) {
        if (provider != null && !nativeWindowReady) return@LaunchedEffect
        nativeTransitionTarget = fullscreen
        nativeTransitionDeadlineNanos = System.nanoTime() + NATIVE_WINDOW_TRANSITION_TIMEOUT_NANOS
        val nativeWindowOwnsTransition =
            withContext(Dispatchers.IO) {
                provider?.requestWindowFullscreen(window, fullscreen) == true
            }
        if (!nativeWindowOwnsTransition) {
            nativeTransitionTarget = null
            windowState.placement =
                if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        } else if (!fullscreen && windowState.placement != WindowPlacement.Floating) {
            // Do not leave a stale Compose full-screen placement after a native exit.
            windowState.placement = WindowPlacement.Floating
        }
    }
    return nativeWindowReady
}

private const val NATIVE_WINDOW_STATE_POLL_MILLIS = 100L
private const val NATIVE_WINDOW_TRANSITION_TIMEOUT_NANOS = 3_000_000_000L
