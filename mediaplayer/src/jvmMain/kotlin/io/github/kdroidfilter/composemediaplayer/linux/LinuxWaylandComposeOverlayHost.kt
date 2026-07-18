package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import kotlin.coroutines.coroutineContext

private const val OVERLAY_RETRY_DELAY_MILLIS = 16L
private const val MAX_CONSECUTIVE_UPLOAD_FAILURES = 3

/**
 * Renders the visual overlay into the dedicated JBR Wayland subsurface. The
 * regular Compose tree renders the same content as an input/semantics proxy;
 * both native child surfaces have empty Wayland input regions.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun LinuxWaylandComposeOverlayHost(
    playerState: LinuxVideoPlayerState,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val currentContent by rememberUpdatedState(content)

    LaunchedEffect(playerState, density, layoutDirection) {
        var consecutiveFailures = 0
        try {
            while (isActive && playerState.waylandNativeOverlayAvailable) {
                val size = playerState.waylandOverlaySize()
                if (size == null) {
                    delay(OVERLAY_RETRY_DELAY_MILLIS)
                    continue
                }

                val scene =
                    ImageComposeScene(
                        width = size.width,
                        height = size.height,
                        density = density,
                        layoutDirection = layoutDirection,
                        coroutineContext = coroutineContext,
                        content = {
                            Box(modifier = Modifier.fillMaxSize()) {
                                currentContent()
                            }
                        },
                    )
                try {
                    var forceUpload = true
                    var sizeChanged = false
                    while (
                        isActive &&
                        playerState.waylandNativeOverlayAvailable &&
                        !sizeChanged
                    ) {
                        withFrameNanos { frameTimeNanos ->
                            if (playerState.waylandOverlaySize() != size) {
                                sizeChanged = true
                                return@withFrameNanos
                            }
                            if (!forceUpload && !scene.hasInvalidations()) {
                                return@withFrameNanos
                            }

                            when (scene.renderAndUpload(playerState, size, frameTimeNanos)) {
                                WAYLAND_OVERLAY_UPLOAD_COMMITTED -> {
                                    forceUpload = false
                                    consecutiveFailures = 0
                                }

                                WAYLAND_OVERLAY_UPLOAD_DROPPED -> {
                                    // Retry on the next frame after a compositor buffer release.
                                    forceUpload = true
                                }

                                else -> {
                                    forceUpload = true
                                    consecutiveFailures++
                                }
                            }
                        }
                        if (consecutiveFailures >= MAX_CONSECUTIVE_UPLOAD_FAILURES) {
                            playerState.disableWaylandNativeOverlay(
                                "The JBR Wayland overlay could not upload a premultiplied " +
                                    "BGRA frame; using the external Compose overlay fallback.",
                            )
                        }
                    }
                } finally {
                    scene.close()
                }
            }
        } finally {
            playerState.clearWaylandOverlay()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun ImageComposeScene.renderAndUpload(
    playerState: LinuxVideoPlayerState,
    size: IntSize,
    frameTimeNanos: Long,
): Int {
    val image = render(frameTimeNanos)
    try {
        if (
            image.colorType != ColorType.BGRA_8888 ||
            image.alphaType != ColorAlphaType.PREMUL
        ) {
            return WAYLAND_OVERLAY_UPLOAD_FAILED
        }
        val pixmap = image.peekPixels() ?: return WAYLAND_OVERLAY_UPLOAD_FAILED
        try {
            return playerState.updateWaylandOverlay(
                pixelAddress = pixmap.addr,
                rowBytes = pixmap.rowBytes,
                width = size.width,
                height = size.height,
            )
        } finally {
            pixmap.close()
        }
    } finally {
        image.close()
    }
}
