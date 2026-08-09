package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.BackendVideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.desktop.tao.consumeTaoVideoOverlayPointerEvents
import io.github.kdroidfilter.composemediaplayer.unwrapDelegatingState

/**
 * Renders a desktop playback session inside the caller's current Tao window.
 *
 * The application owns window creation and fullscreen. Native renderers are mounted as child views
 * at this composable's bounds, with [overlay] kept in Nucleus' Compose overlay scene.
 */
@Composable
public fun DesktopPlaybackSurface(
    session: DesktopPlaybackSession,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable (VideoPlayerState) -> Unit = {},
) {
    DesktopPlaybackSessionSurface(
        session = session,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        onSurfaceAttached = {},
    )
}

/**
 * Renders a desktop playback session and reports when its active native surface is attached.
 * The session still owns the attachment handshake and backend retirement.
 */
@Composable
public fun DesktopPlaybackSurface(
    session: DesktopPlaybackSession,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable (VideoPlayerState) -> Unit = {},
    onSurfaceAttached: (VideoPlayerState) -> Unit,
) {
    DesktopPlaybackSessionSurface(
        session = session,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        onSurfaceAttached = onSurfaceAttached,
    )
}

@Composable
private fun DesktopPlaybackSessionSurface(
    session: DesktopPlaybackSession,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable (VideoPlayerState) -> Unit,
    onSurfaceAttached: (VideoPlayerState) -> Unit,
) {
    val playerState by session.playerState.collectAsState()
    playerState?.let { player ->
        DesktopBackendPlaybackSurface(
            playerState = player,
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
            onSurfaceAttached = {
                session.notifySurfaceAttached(player)
                onSurfaceAttached(player)
            },
        )
    }
}

/**
 * Renders one desktop backend state inside the caller's current Tao window.
 *
 * Prefer [DesktopPlaybackSurface] with a [DesktopPlaybackSession] for full-size desktop playback.
 * Use `VideoPlayerSurface(playerState)` for an unmanaged individual state or preview.
 */
@Composable
@OptIn(ExperimentalComposeMediaPlayerBackendApi::class)
@Deprecated(
    message =
        "Pass DesktopPlaybackSession to DesktopPlaybackSurface for managed desktop playback, " +
            "or use VideoPlayerSurface for an individual player state.",
)
public fun DesktopPlaybackSurface(
    playerState: VideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable (VideoPlayerState) -> Unit = {},
    onSurfaceAttached: (VideoPlayerState) -> Unit = {},
) {
    DesktopBackendPlaybackSurface(
        playerState = playerState,
        modifier = modifier,
        contentScale = contentScale,
        overlay = overlay,
        onSurfaceAttached = onSurfaceAttached,
    )
}

@Composable
@OptIn(ExperimentalComposeMediaPlayerBackendApi::class)
private fun DesktopBackendPlaybackSurface(
    playerState: VideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable (VideoPlayerState) -> Unit,
    onSurfaceAttached: (VideoPlayerState) -> Unit,
) {
    val provider = playerState.unwrapDelegatingState() as? TaoPlaybackSurfaceProvider
    if (provider != null) {
        provider.RenderTaoPlaybackSurface(
            modifier = modifier,
            contentScale = contentScale,
            overlay = {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .consumeTaoVideoOverlayPointerEvents(),
                ) {
                    overlay(playerState)
                }
            },
            onSurfaceAttached = { onSurfaceAttached(playerState) },
        )
    } else {
        BackendVideoPlayerSurface(
            playerState = playerState,
            modifier = modifier,
            contentScale = contentScale,
            overlay = { overlay(playerState) },
        )
        DisposableEffect(playerState) {
            onSurfaceAttached(playerState)
            onDispose { }
        }
    }
}

/** Backend SPI used by [DesktopPlaybackSurface] to observe native child attachment. */
@Stable
@ExperimentalComposeMediaPlayerBackendApi
public interface TaoPlaybackSurfaceProvider {
    @Composable
    public fun RenderTaoPlaybackSurface(
        modifier: Modifier = Modifier,
        contentScale: ContentScale = ContentScale.Fit,
        overlay: @Composable () -> Unit = {},
        onSurfaceAttached: () -> Unit = {},
    )
}
