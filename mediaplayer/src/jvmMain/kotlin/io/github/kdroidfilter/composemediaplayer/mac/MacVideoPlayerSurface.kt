package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
import io.github.kdroidfilter.composemediaplayer.util.drawScaledImage
import io.github.kdroidfilter.composemediaplayer.util.toCanvasModifier
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.IllegalComponentStateException
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import java.awt.Canvas as AwtCanvas
import java.awt.Window as AwtWindow

/**
 * A Composable function that renders a video player surface for MacOS.
 * Fills the entire canvas area with the video frame while maintaining aspect ratio.
 *
 * @param playerState The state object that encapsulates the AVPlayer logic for MacOS.
 * @param modifier An optional Modifier for customizing the layout.
 * @param contentScale Controls how the video content should be scaled inside the surface.
 *                    This affects how the video is displayed when its dimensions don't match
 *                    the surface dimensions.
 * @param overlay Optional composable content to be displayed on top of the video surface.
 *               This can be used to add custom controls, information, or any UI elements.
 * @param isInFullscreenWindow Whether this surface is already being displayed in a fullscreen window.
 */
@Composable
fun MacVideoPlayerSurface(
    playerState: MacVideoPlayerState,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    isInFullscreenWindow: Boolean = false,
) {
    Box(
        modifier =
            modifier.onSizeChanged { size ->
                playerState.onResized(size.width, size.height)
            },
        contentAlignment = Alignment.Center,
    ) {
        // Only render video in this surface if we're not in fullscreen mode or if this is the fullscreen window.
        val shouldRenderVideo =
            (playerState.hasMedia || playerState.libVlcNativeSurfaceRequested) &&
                (!playerState.isFullscreen || isInFullscreenWindow || playerState.libVlcNativeSurfaceRequested)
        if (shouldRenderVideo) {
            if (playerState.shouldUseHdrMetalSurface()) {
                MacHdrMetalVideoHost(
                    playerState = playerState,
                    contentScale = contentScale,
                    overlay = {
                        MacVideoOverlayContent(playerState, overlay)
                    },
                    modifier =
                        contentScale.toCanvasModifier(
                            playerState.aspectRatio,
                            playerState.metadata.width,
                            playerState.metadata.height,
                        ),
                )
            } else if (playerState.libVlcNativeSurfaceRequested) {
                MacLibVlcNativeVideoHost(
                    playerState = playerState,
                    overlay = {
                        MacVideoOverlayContent(playerState, overlay)
                    },
                    nativeFullscreen = playerState.isFullscreen && !isInFullscreenWindow,
                    showExternalOverlay = !isInFullscreenWindow,
                    modifier =
                        contentScale.toCanvasModifier(
                            playerState.aspectRatio,
                            playerState.metadata.width,
                            playerState.metadata.height,
                        ),
                )
            } else {
                // Force recomposition when currentFrameState changes
                val currentFrame by remember(playerState) { playerState.currentFrameState }

                currentFrame?.let { frame ->
                    Canvas(
                        modifier =
                            contentScale.toCanvasModifier(
                                playerState.aspectRatio,
                                playerState.metadata.width,
                                playerState.metadata.height,
                            ),
                    ) {
                        drawScaledImage(
                            image = frame,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                            contentScale = contentScale,
                        )
                    }
                }

                MacVideoOverlayContent(playerState, overlay)
            }
        }
    }

    if (playerState.isFullscreen && !isInFullscreenWindow && !playerState.libVlcNativeSurfaceRequested) {
        openFullscreenWindow(playerState, overlay = overlay, contentScale = contentScale)
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

    Box(modifier = Modifier.fillMaxSize()) {
        overlay()
    }
}

@Composable
private fun MacHdrMetalVideoHost(
    playerState: MacVideoPlayerState,
    contentScale: ContentScale,
    overlay: @Composable () -> Unit,
    modifier: Modifier,
) {
    var overlayVisible by remember { mutableStateOf(false) }
    var initialOverlayBounds by remember { mutableStateOf(AwtOverlayBounds(0, 0, 1, 1)) }
    val panel =
        remember(playerState) {
            MacHdrMetalPanel(playerState) { bounds ->
                if (bounds == null) {
                    overlayVisible = false
                } else {
                    initialOverlayBounds = bounds
                    overlayVisible = true
                }
            }
        }
    DisposableEffect(panel) {
        onDispose {
            panel.detachFromNative()
        }
    }

    SwingPanel(
        background = Color.Black,
        factory = { panel },
        modifier = modifier,
        update = { host ->
            host.updateContentScale(contentScale)
            host.attachOrUpdate()
        },
    )

    if (overlayVisible) {
        val bounds = initialOverlayBounds
        if (bounds.width > 0 && bounds.height > 0) {
            val overlayWindowState =
                rememberWindowState(
                    position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp),
                    width = bounds.width.dp,
                    height = bounds.height.dp,
                )

            Window(
                onCloseRequest = {},
                state = overlayWindowState,
                visible = true,
                title = "Compose Media Player Overlay",
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
            ) {
                DisposableEffect(panel, window) {
                    var pendingBounds: AwtOverlayBounds? = null
                    var updateScheduled = false
                    var disposed = false
                    val applyBounds: (AwtOverlayBounds?) -> Unit = { nextBounds ->
                        pendingBounds = nextBounds
                        if (!updateScheduled) {
                            updateScheduled = true
                            SwingUtilities.invokeLater {
                                updateScheduled = false
                                if (disposed) return@invokeLater
                                val boundsToApply = pendingBounds
                                if (boundsToApply == null) {
                                    if (window.isVisible) {
                                        window.isVisible = false
                                    }
                                } else {
                                    if (window.x != boundsToApply.x ||
                                        window.y != boundsToApply.y ||
                                        window.width != boundsToApply.width ||
                                        window.height != boundsToApply.height
                                    ) {
                                        window.setBounds(
                                            boundsToApply.x,
                                            boundsToApply.y,
                                            boundsToApply.width,
                                            boundsToApply.height,
                                        )
                                    }
                                    if (!window.isVisible) {
                                        window.isVisible = true
                                    }
                                }
                            }
                        }
                    }
                    panel.setOverlayBoundsApplier(applyBounds)
                    onDispose {
                        disposed = true
                        panel.setOverlayBoundsApplier(null)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    overlay()
                }
            }
        }
    }
}

@Composable
private fun MacLibVlcNativeVideoHost(
    playerState: MacVideoPlayerState,
    overlay: @Composable () -> Unit,
    nativeFullscreen: Boolean,
    showExternalOverlay: Boolean,
    modifier: Modifier,
) {
    var overlayVisible by remember { mutableStateOf(false) }
    var initialOverlayBounds by remember { mutableStateOf(AwtOverlayBounds(0, 0, 1, 1)) }
    val panel =
        remember(playerState) {
            MacLibVlcNativePanel(playerState) { bounds ->
                if (bounds == null) {
                    overlayVisible = false
                } else {
                    initialOverlayBounds = bounds
                    overlayVisible = true
                }
            }
        }
    DisposableEffect(panel) {
        onDispose {
            panel.detachFromNative()
        }
    }

    SwingPanel(
        background = Color.Black,
        factory = { panel },
        modifier = modifier,
        update = { host ->
            host.updateNativeFullscreen(nativeFullscreen)
            host.attachOrUpdate()
        },
    )

    if (overlayVisible && showExternalOverlay) {
        val bounds = initialOverlayBounds
        if (bounds.width > 0 && bounds.height > 0) {
            val overlayWindowState =
                rememberWindowState(
                    position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp),
                    width = bounds.width.dp,
                    height = bounds.height.dp,
                )

            Window(
                onCloseRequest = {},
                state = overlayWindowState,
                visible = true,
                title = "Compose Media Player Overlay",
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
            ) {
                DisposableEffect(panel, window) {
                    var pendingBounds: AwtOverlayBounds? = null
                    var updateScheduled = false
                    var disposed = false
                    val applyBounds: (AwtOverlayBounds?) -> Unit = { nextBounds ->
                        pendingBounds = nextBounds
                        if (!updateScheduled) {
                            updateScheduled = true
                            SwingUtilities.invokeLater {
                                updateScheduled = false
                                if (disposed) return@invokeLater
                                val boundsToApply = pendingBounds
                                if (boundsToApply == null) {
                                    if (window.isVisible) {
                                        window.isVisible = false
                                    }
                                } else {
                                    if (window.x != boundsToApply.x ||
                                        window.y != boundsToApply.y ||
                                        window.width != boundsToApply.width ||
                                        window.height != boundsToApply.height
                                    ) {
                                        window.setBounds(
                                            boundsToApply.x,
                                            boundsToApply.y,
                                            boundsToApply.width,
                                            boundsToApply.height,
                                        )
                                    }
                                    if (!window.isVisible) {
                                        window.isVisible = true
                                    }
                                }
                            }
                        }
                    }
                    panel.setOverlayBoundsApplier(applyBounds)
                    onDispose {
                        disposed = true
                        panel.setOverlayBoundsApplier(null)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    overlay()
                }
            }
        }
    }
}

private class MacHdrMetalPanel(
    private val playerState: MacVideoPlayerState,
    private val onOverlayBoundsChanged: (AwtOverlayBounds?) -> Unit,
) : JPanel(BorderLayout()) {
    private val awtCanvas =
        HdrMetalCanvas().apply {
            name = "ComposeMediaPlayer HDR Metal canvas"
            background = java.awt.Color.BLACK
            ignoreRepaint = true
            minimumSize = Dimension(1, 1)
            preferredSize = Dimension(1, 1)
        }
    private var attached = false
    private var contentScaleMode = ContentScale.Fit.toHdrMetalMode()
    private var lastOverlayBounds: AwtOverlayBounds? = null
    private var trackedWindow: java.awt.Window? = null
    private var overlayBoundsApplier: ((AwtOverlayBounds?) -> Unit)? = null
    private var pendingSettledOverlayBounds: AwtOverlayBounds? = null
    private val settledBoundsTimer =
        Timer(GEOMETRY_SETTLE_DELAY_MS) {
            pendingSettledOverlayBounds?.let { bounds ->
                pendingSettledOverlayBounds = null
                applyNativeGeometry()
                overlayBoundsApplier?.invoke(bounds)
            }
        }.apply {
            isRepeats = false
            isCoalesce = true
        }
    private val componentGeometryListener =
        object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent?) = publishOverlayBounds()

            override fun componentResized(e: ComponentEvent?) {
                publishOverlayBounds()
            }

            override fun componentShown(e: ComponentEvent?) {
                publishOverlayBounds()
            }

            override fun componentHidden(e: ComponentEvent?) {
                clearOverlayBounds()
            }
        }
    private val hierarchyBoundsListener =
        object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent?) = publishOverlayBounds()

            override fun ancestorResized(e: HierarchyEvent?) {
                publishOverlayBounds()
            }
        }
    private val hierarchyListener =
        HierarchyListener { event ->
            val flags = event.changeFlags
            if ((flags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L ||
                (flags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
            ) {
                if (awtCanvas.isShowing) {
                    publishOverlayBounds()
                } else {
                    clearOverlayBounds()
                }
            }
        }

    init {
        name = "ComposeMediaPlayer HDR Metal host"
        isOpaque = true
        background = java.awt.Color.BLACK
        minimumSize = Dimension(1, 1)
        preferredSize = Dimension(1, 1)
        add(awtCanvas, BorderLayout.CENTER)
        addComponentListener(componentGeometryListener)
        addHierarchyBoundsListener(hierarchyBoundsListener)
        addHierarchyListener(hierarchyListener)
        awtCanvas.addComponentListener(componentGeometryListener)
        awtCanvas.addHierarchyBoundsListener(hierarchyBoundsListener)
        awtCanvas.addHierarchyListener(hierarchyListener)
    }

    fun updateContentScale(contentScale: ContentScale) {
        val nextMode = contentScale.toHdrMetalMode()
        if (contentScaleMode != nextMode) {
            contentScaleMode = nextMode
            if (attached) {
                applyNativeGeometry()
            }
        }
    }

    fun attachOrUpdate() {
        if (!attached && lastOverlayBounds != null) {
            applyNativeGeometry()
        }
        publishOverlayBounds()
    }

    fun detachFromNative() {
        if (!attached) return
        playerState.detachHdrMetalComponent(awtCanvas)
        attached = false
        clearOverlayBounds()
    }

    override fun addNotify() {
        super.addNotify()
        attachOrUpdate()
        SwingUtilities.invokeLater { publishOverlayBounds() }
    }

    override fun removeNotify() {
        settledBoundsTimer.stop()
        trackedWindow?.removeComponentListener(componentGeometryListener)
        trackedWindow = null
        detachFromNative()
        super.removeNotify()
    }

    override fun doLayout() {
        super.doLayout()
        publishOverlayBounds()
    }

    fun setOverlayBoundsApplier(applier: ((AwtOverlayBounds?) -> Unit)?) {
        overlayBoundsApplier = applier
        applier?.invoke(lastOverlayBounds)
    }

    private fun publishOverlayBounds() {
        if (!awtCanvas.isShowing || awtCanvas.width <= 0 || awtCanvas.height <= 0) return
        updateTrackedWindow()
        val location: Point =
            try {
                awtCanvas.locationOnScreen
            } catch (_: IllegalComponentStateException) {
                return
            }
        val bounds =
            AwtOverlayBounds(
                x = location.x,
                y = location.y,
                width = awtCanvas.width,
                height = awtCanvas.height,
            )
        if (bounds != lastOverlayBounds) {
            val wasHidden = lastOverlayBounds == null
            lastOverlayBounds = bounds
            if (wasHidden) {
                settledBoundsTimer.stop()
                pendingSettledOverlayBounds = null
                applyNativeGeometry()
                overlayBoundsApplier?.invoke(bounds)
                onOverlayBoundsChanged(bounds)
            } else {
                pendingSettledOverlayBounds = bounds
                overlayBoundsApplier?.invoke(null)
                settledBoundsTimer.restart()
            }
        }
    }

    private fun applyNativeGeometry() {
        if (!awtCanvas.isDisplayable || awtCanvas.width <= 0 || awtCanvas.height <= 0) return
        val wasAttached = attached
        attached = playerState.attachHdrMetalComponent(awtCanvas, contentScaleMode) || wasAttached
    }

    private fun updateTrackedWindow() {
        val window = SwingUtilities.getWindowAncestor(awtCanvas)
        if (window === trackedWindow) return
        trackedWindow?.removeComponentListener(componentGeometryListener)
        trackedWindow = window
        trackedWindow?.addComponentListener(componentGeometryListener)
    }

    private fun clearOverlayBounds() {
        if (lastOverlayBounds == null) return
        settledBoundsTimer.stop()
        pendingSettledOverlayBounds = null
        lastOverlayBounds = null
        overlayBoundsApplier?.invoke(null)
        onOverlayBoundsChanged(null)
    }
}

private class MacLibVlcNativePanel(
    private val playerState: MacVideoPlayerState,
    private val onOverlayBoundsChanged: (AwtOverlayBounds?) -> Unit,
) : JPanel(BorderLayout()) {
    private val awtCanvas =
        LibVlcNativeCanvas().apply {
            name = "ComposeMediaPlayer libVLC native canvas"
            background = java.awt.Color.BLACK
            ignoreRepaint = true
            minimumSize = Dimension(1, 1)
            preferredSize = Dimension(1, 1)
        }
    private var attached = false
    private var lastOverlayBounds: AwtOverlayBounds? = null
    private var trackedWindow: AwtWindow? = null
    private var fullscreenWindow: AwtWindow? = null
    private var previousWindowBounds: Rectangle? = null
    private var overlayBoundsApplier: ((AwtOverlayBounds?) -> Unit)? = null
    private var pendingSettledOverlayBounds: AwtOverlayBounds? = null
    private val settledBoundsTimer =
        Timer(GEOMETRY_SETTLE_DELAY_MS) {
            pendingSettledOverlayBounds?.let { bounds ->
                pendingSettledOverlayBounds = null
                applyNativeGeometry()
                overlayBoundsApplier?.invoke(bounds)
            }
        }.apply {
            isRepeats = false
            isCoalesce = true
        }
    private val componentGeometryListener =
        object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent?) = publishOverlayBounds()

            override fun componentResized(e: ComponentEvent?) {
                publishOverlayBounds()
            }

            override fun componentShown(e: ComponentEvent?) {
                publishOverlayBounds()
            }

            override fun componentHidden(e: ComponentEvent?) {
                clearOverlayBounds()
            }
        }
    private val hierarchyBoundsListener =
        object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent?) = publishOverlayBounds()

            override fun ancestorResized(e: HierarchyEvent?) {
                publishOverlayBounds()
            }
        }
    private val hierarchyListener =
        HierarchyListener { event ->
            val flags = event.changeFlags
            if ((flags and HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L ||
                (flags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
            ) {
                if (awtCanvas.isShowing) {
                    publishOverlayBounds()
                } else {
                    clearOverlayBounds()
                }
            }
        }

    init {
        name = "ComposeMediaPlayer libVLC native host"
        isOpaque = true
        background = java.awt.Color.BLACK
        minimumSize = Dimension(1, 1)
        preferredSize = Dimension(1, 1)
        add(awtCanvas, BorderLayout.CENTER)
        addComponentListener(componentGeometryListener)
        addHierarchyBoundsListener(hierarchyBoundsListener)
        addHierarchyListener(hierarchyListener)
        awtCanvas.addComponentListener(componentGeometryListener)
        awtCanvas.addHierarchyBoundsListener(hierarchyBoundsListener)
        awtCanvas.addHierarchyListener(hierarchyListener)
    }

    fun attachOrUpdate() {
        if (!attached && lastOverlayBounds != null) {
            applyNativeGeometry()
        }
        publishOverlayBounds()
    }

    fun detachFromNative() {
        if (!attached) return
        updateNativeFullscreen(false)
        playerState.detachLibVlcNativeComponent(awtCanvas)
        attached = false
        clearOverlayBounds()
    }

    fun updateNativeFullscreen(requested: Boolean) {
        val window = SwingUtilities.getWindowAncestor(awtCanvas) ?: return
        if (requested) {
            if (fullscreenWindow === window) return
            exitNativeFullscreen()
            fullscreenWindow = window
            previousWindowBounds = window.bounds
            window.graphicsConfiguration?.bounds?.let { bounds ->
                window.bounds = bounds
            }
            SwingUtilities.invokeLater {
                publishOverlayBounds()
            }
        } else {
            exitNativeFullscreen()
        }
    }

    override fun addNotify() {
        super.addNotify()
        attachOrUpdate()
        SwingUtilities.invokeLater { publishOverlayBounds() }
    }

    override fun removeNotify() {
        settledBoundsTimer.stop()
        trackedWindow?.removeComponentListener(componentGeometryListener)
        trackedWindow = null
        exitNativeFullscreen()
        detachFromNative()
        super.removeNotify()
    }

    override fun doLayout() {
        super.doLayout()
        publishOverlayBounds()
    }

    fun setOverlayBoundsApplier(applier: ((AwtOverlayBounds?) -> Unit)?) {
        overlayBoundsApplier = applier
        applier?.invoke(lastOverlayBounds)
    }

    private fun publishOverlayBounds() {
        if (!awtCanvas.isShowing || awtCanvas.width <= 0 || awtCanvas.height <= 0) return
        updateTrackedWindow()
        val location: Point =
            try {
                awtCanvas.locationOnScreen
            } catch (_: IllegalComponentStateException) {
                return
            }
        val bounds =
            AwtOverlayBounds(
                x = location.x,
                y = location.y,
                width = awtCanvas.width,
                height = awtCanvas.height,
            )
        if (bounds != lastOverlayBounds) {
            val wasHidden = lastOverlayBounds == null
            lastOverlayBounds = bounds
            if (wasHidden) {
                settledBoundsTimer.stop()
                pendingSettledOverlayBounds = null
                applyNativeGeometry()
                overlayBoundsApplier?.invoke(bounds)
                onOverlayBoundsChanged(bounds)
            } else {
                pendingSettledOverlayBounds = bounds
                overlayBoundsApplier?.invoke(null)
                settledBoundsTimer.restart()
            }
        }
    }

    private fun applyNativeGeometry() {
        if (!awtCanvas.isDisplayable || awtCanvas.width <= 0 || awtCanvas.height <= 0) return
        val wasAttached = attached
        attached = playerState.attachLibVlcNativeComponent(awtCanvas) || wasAttached
    }

    private fun updateTrackedWindow() {
        val window = SwingUtilities.getWindowAncestor(awtCanvas)
        if (window === trackedWindow) return
        trackedWindow?.removeComponentListener(componentGeometryListener)
        trackedWindow = window
        trackedWindow?.addComponentListener(componentGeometryListener)
    }

    private fun exitNativeFullscreen() {
        val window = fullscreenWindow ?: return
        previousWindowBounds?.let { bounds ->
            if (window.isDisplayable) {
                window.bounds = bounds
            }
        }
        fullscreenWindow = null
        previousWindowBounds = null
        SwingUtilities.invokeLater {
            publishOverlayBounds()
        }
    }

    private fun clearOverlayBounds() {
        if (lastOverlayBounds == null) return
        settledBoundsTimer.stop()
        pendingSettledOverlayBounds = null
        lastOverlayBounds = null
        overlayBoundsApplier?.invoke(null)
        onOverlayBoundsChanged(null)
    }
}

private data class AwtOverlayBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

private class HdrMetalCanvas : AwtCanvas() {
    override fun update(g: Graphics?) = Unit

    override fun paint(g: Graphics?) = Unit
}

private class LibVlcNativeCanvas : AwtCanvas() {
    override fun update(g: Graphics?) = Unit

    override fun paint(g: Graphics?) = Unit
}

private fun ContentScale.toHdrMetalMode(): Int =
    when (this) {
        ContentScale.Crop,
        ContentScale.FillWidth,
        ContentScale.FillHeight,
        -> HDR_METAL_SCALE_CROP
        ContentScale.FillBounds -> HDR_METAL_SCALE_FILL
        else -> HDR_METAL_SCALE_FIT
    }

private const val HDR_METAL_SCALE_FIT = 0
private const val HDR_METAL_SCALE_CROP = 1
private const val HDR_METAL_SCALE_FILL = 2
private const val GEOMETRY_SETTLE_DELAY_MS = 450
