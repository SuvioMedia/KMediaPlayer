package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import dev.nucleusframework.application.DecoratedWindow
import dev.nucleusframework.window.tao.LocalTaoWindow
import io.github.kdroidfilter.composemediaplayer.BackendVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

/** Opens playback in an independent, fully native Tao window. */
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
    val applicationScope =
        checkNotNull(LocalDesktopVideoApplicationScope.current) {
            "DesktopVideoPlayerWindow requires nucleusApplication and " +
                "ProvideDesktopVideoApplicationScope."
        }
    val player = playerState

    applicationScope.DecoratedWindow(
        onCloseRequest = {
            player.pause()
            onCloseRequest()
        },
        state = windowState,
        visible = true,
        title = title,
        resizable = true,
        focusable = true,
        nativePopupLayers = true,
        onKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                if (player.isFullscreen) {
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
        val taoWindow = LocalTaoWindow.current
        var pendingFullscreen by remember(taoWindow) { mutableStateOf<Boolean?>(null) }

        // Do not pre-write WindowState.placement here. On macOS Nucleus observes an intermediate
        // resize before AppKit marks the window fullscreen and otherwise cancels its own request.
        // The native window owns the transition; WindowState is only the confirmed echo.
        LaunchedEffect(player.isFullscreen, taoWindow) {
            val window = taoWindow ?: return@LaunchedEffect
            val requested = player.isFullscreen
            if (window.isFullscreen != requested) {
                pendingFullscreen = requested
                window.setFullscreen(requested)
            } else {
                pendingFullscreen = null
            }
        }
        LaunchedEffect(windowState.placement, taoWindow) {
            val nativeFullscreen = windowState.placement == WindowPlacement.Fullscreen
            when (val pending = pendingFullscreen) {
                nativeFullscreen -> pendingFullscreen = null
                null -> if (player.isFullscreen != nativeFullscreen) player.isFullscreen = nativeFullscreen
                else -> Unit
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val provider = player as? DesktopVideoWindowSurfaceProvider
            if (provider != null) {
                provider.RenderDesktopVideoWindowSurface(
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    overlay = {
                        // NativeView overlays are hit-test transparent unless their interactive
                        // regions opt in. A video player's Compose controls own the whole viewport;
                        // native decoders do not need direct pointer input.
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .consumeNativeVideoOverlayPointerEvents(),
                        ) {
                            overlay(player)
                        }
                    },
                    onSurfaceAttached = { onSurfaceAttached(player) },
                )
            } else {
                BackendVideoPlayerSurface(
                    playerState = player,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                    overlay = { overlay(player) },
                )
                DisposableEffect(player) {
                    onSurfaceAttached(player)
                    onDispose { }
                }
            }
        }
    }
}
