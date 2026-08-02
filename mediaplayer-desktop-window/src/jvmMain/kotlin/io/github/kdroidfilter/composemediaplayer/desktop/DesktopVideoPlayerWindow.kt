package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.composemediaplayer.BackendVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlin.math.roundToInt

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
        undecorated = true,
        transparent = true,
        resizable = true,
        focusable = true,
        onKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                if (windowState.placement == WindowPlacement.Fullscreen) {
                    windowState.placement = WindowPlacement.Floating
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
            if (provider != null) {
                provider.RenderDesktopVideoWindowSurface(
                    window = window,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    overlay = { overlay(player) },
                    onSurfaceAttached = { onSurfaceAttached(player) },
                )
            } else {
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

            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(32.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .pointerInput(window) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                window.setLocation(
                                    window.x + dragAmount.x.roundToInt(),
                                    window.y + dragAmount.y.roundToInt(),
                                )
                            }
                        }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WindowControl(CLOSE_CONTROL_COLOR) {
                    player.pause()
                    onCloseRequest()
                }
                WindowControl(MINIMIZE_CONTROL_COLOR) {
                    windowState.isMinimized = true
                }
                WindowControl(FULLSCREEN_CONTROL_COLOR) {
                    val fullscreen = windowState.placement != WindowPlacement.Fullscreen
                    windowState.placement =
                        if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
                    player.isFullscreen = fullscreen
                }
            }
        }
    }
}

private const val CLOSE_CONTROL_COLOR_ARGB = 0xFFFF5F57L
private const val MINIMIZE_CONTROL_COLOR_ARGB = 0xFFFFBD2EL
private const val FULLSCREEN_CONTROL_COLOR_ARGB = 0xFF28C840L
private val CLOSE_CONTROL_COLOR = Color(CLOSE_CONTROL_COLOR_ARGB)
private val MINIMIZE_CONTROL_COLOR = Color(MINIMIZE_CONTROL_COLOR_ARGB)
private val FULLSCREEN_CONTROL_COLOR = Color(FULLSCREEN_CONTROL_COLOR_ARGB)

@Composable
private fun WindowControl(
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
