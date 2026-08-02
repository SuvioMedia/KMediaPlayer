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

    /**
     * Requests full-screen presentation for the same native window that owns the video surface.
     * Returns `true` when the platform backend accepted and owns the transition. Returning `false`
     * lets the desktop-window host use Compose's ordinary [Window] placement as a fallback.
     */
    public fun requestWindowFullscreen(
        window: Window,
        fullscreen: Boolean,
    ): Boolean = false

    /**
     * Lets a native backend configure the caller-owned desktop window before presenting video.
     * On macOS this restores ordinary AppKit window chrome instead of emulating a title bar in
     * Compose.
     */
    public fun configureNativeWindow(window: Window): Boolean = false

    /**
     * Returns the platform window's actual full-screen state, or `null` when the backend cannot
     * observe it. This keeps player controls synchronized with native title-bar actions.
     */
    public fun nativeWindowFullscreenState(window: Window): Boolean? = null
}
