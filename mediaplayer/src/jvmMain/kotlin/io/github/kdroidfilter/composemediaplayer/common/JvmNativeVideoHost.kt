package io.github.kdroidfilter.composemediaplayer.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Component
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
import java.awt.Window as AwtWindow

private const val GEOMETRY_SETTLE_DELAY_MS = 120

@Composable
internal fun JvmNativeVideoHost(
    modifier: Modifier,
    canvasName: String,
    hostName: String,
    attachNative: (Component) -> Boolean,
    detachNative: (Component) -> Unit,
    nativeFullscreen: Boolean,
    showExternalOverlay: Boolean,
    overlay: @Composable () -> Unit,
) {
    var overlayVisible by remember { mutableStateOf(false) }
    var initialOverlayBounds by remember { mutableStateOf(AwtOverlayBounds(0, 0, 1, 1)) }
    val currentAttachNative by rememberUpdatedState(attachNative)
    val currentDetachNative by rememberUpdatedState(detachNative)
    val panel =
        remember(canvasName, hostName) {
            JvmNativeVideoPanel(
                canvasName = canvasName,
                hostName = hostName,
                attachNative = { component -> currentAttachNative(component) },
                detachNative = { component -> currentDetachNative(component) },
            ) { bounds ->
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

private class JvmNativeVideoPanel(
    canvasName: String,
    hostName: String,
    private val attachNative: (Component) -> Boolean,
    private val detachNative: (Component) -> Unit,
    private val onOverlayBoundsChanged: (AwtOverlayBounds?) -> Unit,
) : JPanel(BorderLayout()) {
    private val awtCanvas =
        NativeVideoCanvas().apply {
            name = canvasName
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
        name = hostName
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
        detachNative(awtCanvas)
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
        attached = attachNative(awtCanvas) || wasAttached
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

private class NativeVideoCanvas : Canvas() {
    override fun update(g: Graphics?) = Unit

    override fun paint(g: Graphics?) = Unit
}
