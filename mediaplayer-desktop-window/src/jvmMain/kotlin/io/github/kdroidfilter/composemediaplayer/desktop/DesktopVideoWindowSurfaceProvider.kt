package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import java.awt.Window

/**
 * Experimental SPI for rendering directly into the dedicated desktop player window.
 *
 * Native implementations receive the owning AWT window so they can install an AppKit view,
 * HWND/DirectComposition target or Wayland subsurface below the transparent Compose layer.
 */
@Stable
public interface DesktopVideoWindowSurfaceProvider {
    @Composable
    public fun RenderDesktopVideoWindowSurface(
        window: Window,
        modifier: Modifier = Modifier,
        contentScale: ContentScale = ContentScale.Fit,
        overlay: @Composable () -> Unit = {},
        onSurfaceAttached: () -> Unit = {},
    )
}
