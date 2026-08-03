package io.github.kdroidfilter.composemediaplayer.mac

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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import io.github.kdroidfilter.composemediaplayer.JvmProjectedVideoCanvas
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.subtitle.ComposeSubtitleLayer
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
        // Dedicated native playback keeps using this same NSWindow in macOS full screen. Only the
        // legacy inline surface yields ownership to a separately composed full-screen window.
        val shouldRenderVideo =
            shouldRenderMacVideoSurface(
                hasMedia = playerState.hasMedia,
                libVlcNativeSurfaceRequested = playerState.libVlcNativeSurfaceRequested,
                isFullscreen = playerState.isFullscreen,
                isInFullscreenWindow = isInFullscreenWindow,
                usesDedicatedNativeWindow = false,
            )
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
                    JvmProjectedVideoCanvas(
                        frame = frame,
                        projection = playerState.projection,
                        projectionView = playerState.projectionView,
                        textureCrop = playerState.projectionTextureCrop,
                        contentScale = contentScale,
                        modifier =
                            contentScale.toCanvasModifier(
                                playerState.aspectRatio,
                                playerState.metadata.width,
                                playerState.metadata.height,
                            ),
                    )
                }

                MacVideoOverlayContent(playerState, overlay)
            }
        }
    }

    if (
        playerState.isFullscreen &&
        !isInFullscreenWindow &&
        !playerState.libVlcNativeSurfaceRequested
    ) {
        openFullscreenWindow(playerState, overlay = overlay, contentScale = contentScale)
    }
}

/** Renders the native AppKit layer into the caller-owned dedicated player window. */
@Composable
internal fun MacVideoPlayerWindowSurface(
    playerState: MacVideoPlayerState,
    window: AwtWindow,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    overlay: @Composable () -> Unit = {},
    onSurfaceAttached: () -> Unit = {},
) {
    val nativeKind =
        when {
            playerState.shouldUseLibVlcNativeSurface() -> MacDedicatedNativeSurfaceKind.LIBVLC
            playerState.shouldUseHdrMetalSurface() -> MacDedicatedNativeSurfaceKind.HDR_METAL
            else -> null
        }
    if (nativeKind == null) {
        MacVideoPlayerSurface(
            playerState = playerState,
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
            isInFullscreenWindow = true,
        )
        DisposableEffect(playerState, window) {
            onSurfaceAttached()
            onDispose { }
        }
        return
    }

    DisposableEffect(playerState, window, nativeKind, contentScale) {
        val attachment =
            MacDedicatedNativeWindowAttachment(
                playerState = playerState,
                window = window,
                kind = nativeKind,
                contentScaleMode = contentScale.toHdrMetalMode(),
                onAttached = onSurfaceAttached,
            )
        attachment.start()
        onDispose(attachment::close)
    }
    Box(
        modifier =
            modifier.onSizeChanged { size ->
                playerState.onResized(size.width, size.height)
            },
    ) {
        MacVideoOverlayContent(playerState, overlay)
    }
}

/** Keeps a dedicated AppKit window composed while that same NSWindow enters native full screen. */
internal fun shouldRenderMacVideoSurface(
    hasMedia: Boolean,
    libVlcNativeSurfaceRequested: Boolean,
    isFullscreen: Boolean,
    isInFullscreenWindow: Boolean,
    usesDedicatedNativeWindow: Boolean,
): Boolean =
    (hasMedia || libVlcNativeSurfaceRequested) &&
        (
            !isFullscreen ||
                isInFullscreenWindow ||
                libVlcNativeSurfaceRequested ||
                usesDedicatedNativeWindow
        )

private enum class MacDedicatedNativeSurfaceKind {
    HDR_METAL,
    LIBVLC,
}

private const val HDR_SCREEN_REFRESH_DELAY_MILLIS = 120

private class MacDedicatedNativeWindowAttachment(
    private val playerState: MacVideoPlayerState,
    private val window: AwtWindow,
    private val kind: MacDedicatedNativeSurfaceKind,
    private val contentScaleMode: Int,
    private val onAttached: () -> Unit = {},
) : ComponentAdapter() {
    private var disposed = false
    private var attached = false
    private var attachScheduled = false
    private val screenRefreshTimer =
        Timer(HDR_SCREEN_REFRESH_DELAY_MILLIS) {
            if (!disposed && attached && kind == MacDedicatedNativeSurfaceKind.HDR_METAL) {
                playerState.requestAttachedHdrColorPipelineRefresh()
            }
        }.apply {
            isRepeats = false
            isCoalesce = true
        }

    fun start() {
        window.addComponentListener(this)
        scheduleAttach()
    }

    fun close() {
        disposed = true
        screenRefreshTimer.stop()
        window.removeComponentListener(this)
        detachNativeWindow()
    }

    private fun detachNativeWindow() {
        if (!attached) return
        when (kind) {
            MacDedicatedNativeSurfaceKind.HDR_METAL -> playerState.detachHdrMetalComponent(window)
            MacDedicatedNativeSurfaceKind.LIBVLC -> playerState.detachLibVlcNativeComponent(window)
        }
        attached = false
    }

    override fun componentShown(e: ComponentEvent?) = scheduleAttach()

    override fun componentHidden(e: ComponentEvent?) {
        // Compose hides an AWT window before disposing its child composition. Restore the JBR
        // content view while the NSWindow peer is still alive; doing it only from onDispose is too
        // late on macOS and can leave a video-only orphan window behind.
        detachNativeWindow()
    }

    override fun componentMoved(e: ComponentEvent?) {
        if (kind == MacDedicatedNativeSurfaceKind.HDR_METAL) {
            screenRefreshTimer.restart()
        }
    }

    private fun scheduleAttach() {
        if (disposed || attached || attachScheduled) return
        attachScheduled = true
        SwingUtilities.invokeLater {
            attachScheduled = false
            if (disposed || attached || !window.isDisplayable || !window.isShowing) return@invokeLater
            attached =
                when (kind) {
                    MacDedicatedNativeSurfaceKind.HDR_METAL ->
                        playerState.attachHdrMetalWindow(window, contentScaleMode)
                    MacDedicatedNativeSurfaceKind.LIBVLC ->
                        playerState.attachLibVlcNativeWindow(window)
                }
            if (attached) onAttached()
        }
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
    val hostWindowFocused = LocalWindowInfo.current.isWindowFocused
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
                visible = hostWindowFocused,
                title = "Compose Media Player Overlay",
                undecorated = true,
                transparent = true,
                resizable = false,
                focusable = false,
                alwaysOnTop = hostWindowFocused,
            ) {
                DisposableEffect(panel, window, hostWindowFocused) {
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
                                    if (hostWindowFocused && !window.isVisible) {
                                        window.isVisible = true
                                    } else if (!hostWindowFocused && window.isVisible) {
                                        window.isVisible = false
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
    val hostWindowFocused = LocalWindowInfo.current.isWindowFocused
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
                visible = hostWindowFocused,
                title = "Compose Media Player Overlay",
                undecorated = true,
                transparent = true,
                resizable = false,
                focusable = false,
                alwaysOnTop = hostWindowFocused,
            ) {
                DisposableEffect(panel, window, hostWindowFocused) {
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
                                    if (hostWindowFocused && !window.isVisible) {
                                        window.isVisible = true
                                    } else if (!hostWindowFocused && window.isVisible) {
                                        window.isVisible = false
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
        if (attached) playerState.requestAttachedHdrColorPipelineRefresh()
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
