package io.github.kdroidfilter.composemediaplayer

/**
 * Selects how a desktop backend presents video.
 *
 * [PREFER_COLOR_MANAGED_TEXTURE] keeps video and Compose overlays in one GPU color-managed
 * scene. [COMPOSE] is the explicit CPU/SDR path intended for thumbnails and test surfaces.
 */
public enum class DesktopVideoSurfaceMode {
    PREFER_COLOR_MANAGED_TEXTURE,

    @Deprecated(
        message = "Native desktop child views were replaced by the color-managed TextureView pipeline.",
        replaceWith = ReplaceWith("DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE"),
    )
    PREFER_NATIVE,

    COMPOSE,
}
