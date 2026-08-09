@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import dev.nucleusframework.window.tao.currentTextureViewHostCapabilities
import io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopColorManagedTextureVideoView

@Composable
internal fun LibVlcVideoPlayerSurface(
    playerState: LibVlcVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
) {
    val host = currentTextureViewHostCapabilities()
    LaunchedEffect(playerState, host) {
        playerState.updateHostCapabilities(host)
    }
    DisposableEffect(playerState) {
        onDispose(playerState::detachSurface)
    }

    Box(
        modifier =
            modifier
                .background(Color.Black)
                .onSizeChanged { size -> playerState.updateSurfaceSize(size.width, size.height) },
        contentAlignment = Alignment.Center,
    ) {
        if (playerState.usesGpuTexture) {
            DesktopColorManagedTextureVideoView(
                streamController = playerState.streamController,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onHostCapabilitiesChanged = playerState::updateHostCapabilities,
                overlay = overlay,
            )
        } else {
            val frame by remember(playerState) { playerState.currentCpuFrame }
            frame?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            }
            Box(Modifier.fillMaxSize()) { overlay() }
        }
    }
}
