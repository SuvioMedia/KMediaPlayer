package io.github.kdroidfilter.composemediaplayer.mac

/**
 * In-place AppKit fullscreen for Tao windows that contain native video children.
 *
 * AppKit's regular fullscreen transition reparents the content view into another Space. That is
 * fragile while Tao, the native video child and its Compose overlay have pending resize work. This
 * mode keeps the existing NSWindow and view tree, hides system chrome, and resizes it to the screen.
 */
public object MacNativeWindowFullscreen {
    public val isSupported: Boolean
        get() = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    public fun setFullscreen(
        taoNativeViewHandle: Long,
        fullscreen: Boolean,
    ): Boolean {
        if (!isSupported || taoNativeViewHandle == 0L) return false
        return runCatching {
            MacNativeBridge.nSetNativeWindowFullscreen(taoNativeViewHandle, fullscreen)
        }.getOrDefault(false)
    }
}
