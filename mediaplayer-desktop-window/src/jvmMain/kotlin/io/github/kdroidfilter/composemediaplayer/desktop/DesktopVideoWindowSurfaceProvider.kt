package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Renders a backend surface inside a Tao-owned player window.
 *
 * Implementations must not initialize a Java desktop UI toolkit or assume a Java window peer. Native renderers are
 * hosted through [DesktopNativeVideoView] and receive only the platform handle they own.
 */
@Stable
public interface DesktopVideoWindowSurfaceProvider {
    @Composable
    public fun RenderDesktopVideoWindowSurface(
        modifier: Modifier = Modifier,
        contentScale: ContentScale = ContentScale.Fit,
        overlay: @Composable () -> Unit = {},
        onSurfaceAttached: () -> Unit = {},
    )
}
