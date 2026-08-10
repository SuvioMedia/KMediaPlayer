@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import dev.nucleusframework.window.tao.TextureColorInfo
import dev.nucleusframework.window.tao.TextureView
import dev.nucleusframework.window.tao.currentMacMetalTextureHost
import dev.nucleusframework.window.tao.nucleusIOSurfaceTextureSource
import dev.nucleusframework.window.tao.rememberTextureViewController
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopColorManagedTextureVideoView
import io.github.kdroidfilter.composemediaplayer.desktop.tao.DesktopProjectedVideoCanvas
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** Renders macOS video through the color-managed TextureView or the explicit SDR canvas. */
@Composable
internal fun MacVideoPlayerSurface(
    playerState: MacVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
) {
    MacVideoSurfaceContent(playerState, modifier, contentScale, overlay, onSurfaceAttached)
}

@Composable
private fun MacVideoSurfaceContent(
    playerState: MacVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    onSurfaceAttached: () -> Unit = {},
) {
    val latestOnSurfaceAttached by rememberUpdatedState(onSurfaceAttached)
    val metalTextureRequested = playerState.shouldUseHdrMetalSurface()
    val videoModifier =
        contentScale.toCanvasModifier(
            playerState.aspectRatio,
            playerState.metadata.width,
            playerState.metadata.height,
        )

    val hostModifier =
        if (metalTextureRequested) {
            modifier
        } else {
            modifier.onSizeChanged { size ->
                playerState.onResized(size.width, size.height)
            }
        }

    Box(
        modifier = hostModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (metalTextureRequested) {
            MacMetalTextureVideoView(
                playerState = playerState,
                modifier =
                    videoModifier.onSizeChanged { size ->
                        playerState.onMetalTextureResized(size.width, size.height)
                        if (contentScale.preservesMediaAspectRatio()) {
                            playerState.recordMetalTextureViewportGeometry(
                                width = size.width,
                                height = size.height,
                                expectedAspectRatio = playerState.aspectRatio,
                            )
                        }
                    },
                contentScale = contentScale,
                onSurfaceAttached = { latestOnSurfaceAttached() },
            )
            // The media follows the same aspect-ratio rectangle as the Canvas path, while player
            // chrome continues to own the complete viewport.
            MacVideoOverlayContent(playerState, overlay)
        } else {
            val currentFrame by remember(playerState) { playerState.currentFrameState }
            currentFrame?.let { frame ->
                DesktopProjectedVideoCanvas(
                    frame = frame,
                    projection = playerState.projection,
                    projectionView = playerState.projectionView,
                    textureCrop = playerState.projectionTextureCrop,
                    contentScale = contentScale,
                    modifier = videoModifier,
                )
            }
            MacVideoOverlayContent(playerState, overlay)
            DisposableEffect(playerState) {
                latestOnSurfaceAttached()
                onDispose { }
            }
        }
    }
}

/**
 * Presents AVFoundation's FP16 IOSurface in the Tao Metal scene. The texture is imported by the
 * window GPU and is never copied through Compose or the CPU. Keeping it in the same scene as the
 * controls also lets [ContentScale] preserve the last completed frame while a live resize is
 * waiting for a newly projected texture with the updated viewport aspect.
 */
@Composable
private fun MacMetalTextureVideoView(
    playerState: MacVideoPlayerState,
    modifier: Modifier,
    contentScale: ContentScale,
    onSurfaceAttached: () -> Unit,
) {
    val host = currentMacMetalTextureHost()
    val commandQueue = host?.commandQueue ?: 0L
    val nativeView = host?.nativeView ?: 0L
    val latestOnSurfaceAttached by rememberUpdatedState(onSurfaceAttached)
    if (commandQueue == 0L || nativeView == 0L) {
        DesktopColorManagedTextureVideoView(
            source = null,
            controller = rememberTextureViewController(),
            modifier = modifier,
            contentScale = contentScale,
            onHostCapabilitiesChanged = playerState::onTextureViewHostCapabilities,
            onSurfaceAttached = { latestOnSurfaceAttached() },
        )
        return
    }

    val controller = rememberTextureViewController()
    var source by remember(playerState, playerState.nativeSurfaceGeneration, commandQueue) {
        mutableStateOf<dev.nucleusframework.window.tao.TextureViewSource?>(null)
    }
    var previousSource by remember(playerState, playerState.nativeSurfaceGeneration, commandQueue) {
        mutableStateOf<dev.nucleusframework.window.tao.TextureViewSource?>(null)
    }
    var textureWidth by remember(playerState, playerState.nativeSurfaceGeneration, commandQueue) {
        mutableIntStateOf(0)
    }
    var textureHeight by remember(playerState, playerState.nativeSurfaceGeneration, commandQueue) {
        mutableIntStateOf(0)
    }
    var attached by remember(playerState, playerState.nativeSurfaceGeneration, commandQueue) {
        mutableStateOf(false)
    }

    DisposableEffect(playerState, playerState.nativeSurfaceGeneration, commandQueue, nativeView) {
        val didAttach =
            commandQueue != 0L &&
                playerState.attachMetalTextureOutput(
                    commandQueue = commandQueue,
                    nativeView = nativeView,
                )
        attached = didAttach
        onDispose {
            if (didAttach) playerState.detachMetalTextureOutput()
            attached = false
        }
    }

    LaunchedEffect(playerState, playerState.nativeSurfaceGeneration, attached) {
        if (!attached) return@LaunchedEffect
        val info = LongArray(METAL_TEXTURE_INFO_SIZE)
        var lastSurface = 0L
        var lastWidth = 0
        var lastHeight = 0
        var lastFrameSerial = Long.MIN_VALUE
        while (true) {
            val frame = withContext(Dispatchers.IO) { playerState.metalTextureFrame(info) }
            if (frame != null) {
                if (
                    frame.ioSurface != lastSurface ||
                    frame.width != lastWidth ||
                    frame.height != lastHeight
                ) {
                    lastSurface = frame.ioSurface
                    lastWidth = frame.width
                    lastHeight = frame.height
                    textureWidth = frame.width
                    textureHeight = frame.height
                    val nextSource =
                        nucleusIOSurfaceTextureSource(
                            ioSurface = frame.ioSurface,
                            widthPx = frame.width,
                            heightPx = frame.height,
                            colorInfo = TextureColorInfo.EXTENDED_LINEAR_SRGB_PREMULTIPLIED,
                        )
                    previousSource = source
                    source = nextSource
                }
                if (frame.frameSerial != lastFrameSerial) {
                    lastFrameSerial = frame.frameSerial
                    controller.markFrameAvailable()
                    playerState.onTextureProducerFrameSubmitted(frame.frameSerial)
                }
            }
            delay(METAL_TEXTURE_POLL_INTERVAL)
        }
    }

    DisposableEffect(source) {
        if (source != null) latestOnSurfaceAttached()
        onDispose { }
    }

    // A fresh IOSurface has a completed producer frame, but TextureView still prepares its first
    // immutable Skia snapshot asynchronously. Keep the last imported image underneath until that
    // snapshot starts drawing; the new view is transparent meanwhile. At most two surfaces stay
    // leased, and the older one is released on the next completed viewport transition.
    Box(modifier = modifier) {
        previousSource?.let { completedSource ->
            TextureView(
                source = completedSource,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
        DesktopColorManagedTextureVideoView(
            source = source,
            controller = controller,
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewport ->
                        if (textureWidth <= 0 || textureHeight <= 0 || viewport.width <= 0 || viewport.height <= 0) {
                            return@onSizeChanged
                        }
                        val scale =
                            contentScale.computeScaleFactor(
                                srcSize = Size(textureWidth.toFloat(), textureHeight.toFloat()),
                                dstSize = Size(viewport.width.toFloat(), viewport.height.toFloat()),
                            )
                        playerState.recordMetalTextureGeometry(scale.scaleX, scale.scaleY)
                    },
            // A projected texture is rebuilt for the final viewport aspect. Until that new texture is
            // ready, honour the caller's scale mode for the previous one instead of stretching it.
            contentScale = contentScale,
            onHostCapabilitiesChanged = playerState::onTextureViewHostCapabilities,
            onSurfaceAttached = { latestOnSurfaceAttached() },
        )
    }
}

@Composable
private fun MacVideoOverlayContent(
    playerState: MacVideoPlayerState,
    overlay: @Composable () -> Unit,
) {
    if (playerState.subtitlesEnabled &&
        playerState.currentSubtitleTrack != null &&
        playerState.currentSubtitleTrack?.isEmbedded != true &&
        !playerState.usesLibAssSubtitleOverlay
    ) {
        val currentTime =
            if (playerState.userDragging) {
                playerState.duration *
                    (playerState.sliderPos / VideoPlayerState.SLIDER_SCALE).toDouble().coerceIn(0.0, 1.0)
            } else {
                playerState.preciseCurrentTime
            } + playerState.subtitleOffset

        ComposeSubtitleLayer(
            currentTime = currentTime,
            duration = playerState.duration,
            isPlaying = playerState.isPlaying,
            subtitleTrack = playerState.currentSubtitleTrack,
            subtitlesEnabled = playerState.subtitlesEnabled,
            textStyle = playerState.subtitleTextStyle,
            backgroundColor = playerState.subtitleBackgroundColor,
        )
    }
    Box(modifier = Modifier.fillMaxSize()) { overlay() }
}

private fun ContentScale.preservesMediaAspectRatio(): Boolean =
    this != ContentScale.Crop && this != ContentScale.FillBounds

private const val METAL_TEXTURE_INFO_SIZE = 4
private val METAL_TEXTURE_POLL_INTERVAL = 8.milliseconds
