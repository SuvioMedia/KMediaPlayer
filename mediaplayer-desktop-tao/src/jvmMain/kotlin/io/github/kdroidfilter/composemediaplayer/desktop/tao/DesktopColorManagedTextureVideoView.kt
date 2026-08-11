package io.github.kdroidfilter.composemediaplayer.desktop.tao

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.TextureViewController
import dev.nucleusframework.window.tao.TextureViewHostCapabilities
import dev.nucleusframework.window.tao.TextureViewHostPresentationState
import dev.nucleusframework.window.tao.TextureViewSource
import dev.nucleusframework.window.tao.TextureViewStreamController
import dev.nucleusframework.window.tao.currentTextureViewHostCapabilities

/**
 * Desktop video surface for producer-owned rotating GPU buffers.
 *
 * Video and [overlay] are composited in the same Nucleus scene. [onHostCapabilitiesChanged]
 * receives the exact output generation and system-present counter; submitting a producer frame
 * alone is deliberately not treated as evidence that HDR reached the display.
 */
@Composable
public fun DesktopColorManagedTextureVideoView(
    streamController: TextureViewStreamController,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onHostCapabilitiesChanged: (TextureViewHostCapabilities) -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
    overlay: @Composable () -> Unit = {},
) {
    val capabilities = currentTextureViewHostCapabilities()
    LaunchedEffect(capabilities) {
        onHostCapabilitiesChanged(capabilities)
        if (capabilities.presentationState != TextureViewHostPresentationState.UNAVAILABLE) {
            onSurfaceAttached()
        }
    }

    Box(modifier = modifier) {
        TextureView(
            streamController = streamController,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )
        Box(modifier = Modifier.fillMaxSize()) { overlay() }
    }
}

/** Fixed-resource variant for a producer that updates one shared GPU texture in place. */
@Composable
public fun DesktopColorManagedTextureVideoView(
    source: TextureViewSource?,
    controller: TextureViewController,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onHostCapabilitiesChanged: (TextureViewHostCapabilities) -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
    overlay: @Composable () -> Unit = {},
) {
    val capabilities = currentTextureViewHostCapabilities()
    LaunchedEffect(capabilities) {
        onHostCapabilitiesChanged(capabilities)
        if (capabilities.presentationState != TextureViewHostPresentationState.UNAVAILABLE) {
            onSurfaceAttached()
        }
    }

    Box(modifier = modifier) {
        TextureView(
            source = source,
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
        )
        Box(modifier = Modifier.fillMaxSize()) { overlay() }
    }
}
