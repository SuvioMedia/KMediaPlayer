@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.desktop.tao

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.NucleusPlatformView
import dev.nucleusframework.window.tao.nucleusGtkPlatformView
import dev.nucleusframework.window.tao.nucleusHwndPlatformView
import dev.nucleusframework.window.tao.nucleusNsPlatformView
import io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Native child type mounted directly by Nucleus Tao, without a Java UI-toolkit intermediary. */
@ExperimentalComposeMediaPlayerBackendApi
public enum class TaoNativeVideoSurfaceKind {
    MACOS_NS_VIEW,
    WINDOWS_HWND,
    LINUX_GTK_WIDGET,
}

/**
 * Lifecycle contract for a video backend's native child view.
 *
 * [createHandle] returns an owned `NSView*`, child `HWND`, or `GtkWidget*`. Nucleus reparents and
 * sizes that child. [disposeHandle] is called exactly once when the Compose node leaves the tree.
 */
@Stable
@ExperimentalComposeMediaPlayerBackendApi
public class TaoNativeVideoSurface(
    public val kind: TaoNativeVideoSurfaceKind,
    private val createHandle: () -> Long,
    private val resizeHandle: (handle: Long, widthPx: Int, heightPx: Int) -> Unit = { _, _, _ -> },
    private val setBoundsHandle: (handle: Long, xPx: Int, yPx: Int, widthPx: Int, heightPx: Int) -> Unit =
        { _, _, _, _, _ -> },
    private val clearFocusHandle: (handle: Long) -> Unit = {},
    private val disposeHandle: (handle: Long) -> Unit,
) {
    private val disposalRequested = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val interopActivity = AtomicLong(0L)

    @Volatile
    private var creationAttempted: Boolean = false

    @Volatile
    private var nativeHandle: Long = 0L

    internal fun handle(): Long {
        if (disposalRequested.get() || disposed.get()) return 0L
        val current = nativeHandle
        if (current != 0L) return current
        return synchronized(this) {
            when {
                nativeHandle != 0L -> nativeHandle
                creationAttempted -> 0L
                else -> {
                    creationAttempted = true
                    createHandle().also { nativeHandle = it }
                }
            }
        }
    }

    internal fun createdHandle(): Long = nativeHandle

    internal fun resize(
        widthPx: Int,
        heightPx: Int,
    ) {
        interopActivity.incrementAndGet()
        handle().takeIf { it != 0L }?.let { resizeHandle(it, widthPx, heightPx) }
    }

    internal fun setBounds(
        xPx: Int,
        yPx: Int,
        widthPx: Int,
        heightPx: Int,
    ) {
        interopActivity.incrementAndGet()
        handle().takeIf { it != 0L }?.let { setBoundsHandle(it, xPx, yPx, widthPx, heightPx) }
    }

    internal fun clearFocus() {
        interopActivity.incrementAndGet()
        nativeHandle.takeIf { it != 0L }?.let(clearFocusHandle)
    }

    internal fun dispose() {
        if (!disposalRequested.compareAndSet(false, true)) return
        disposeNow()
    }

    internal fun disposeAfterInterop(schedule: ((() -> Unit) -> Unit)) {
        if (!disposalRequested.compareAndSet(false, true)) return
        scheduleQuietInteropFence(
            schedule = schedule,
            observedActivity = interopActivity.get(),
            quietFences = 0,
        )
    }

    /**
     * Nucleus queues a raw native handle before calling this surface's resize callbacks. A layout
     * callback may therefore append another `setFrame` after disposal has started. Keep crossing
     * transaction boundaries until the old layout has been quiet for several complete frames;
     * only then can no queued Tao frame mutation still reference this handle.
     */
    private fun scheduleQuietInteropFence(
        schedule: ((() -> Unit) -> Unit),
        observedActivity: Long,
        quietFences: Int,
    ) {
        schedule {
            val currentActivity = interopActivity.get()
            val activityChanged = currentActivity != observedActivity
            val nextQuietFences = if (activityChanged) 0 else quietFences + 1
            if (nextQuietFences >= REQUIRED_QUIET_INTEROP_FENCES) {
                disposeNow()
            } else {
                scheduleQuietInteropFence(
                    schedule = schedule,
                    observedActivity = currentActivity,
                    quietFences = nextQuietFences,
                )
            }
        }
    }

    private fun disposeNow() {
        if (!disposed.compareAndSet(false, true)) return
        val handle = synchronized(this) { nativeHandle.also { nativeHandle = 0L } }
        if (handle != 0L) disposeHandle(handle)
    }
}

/** Embeds [surface] and renders [overlay] in Nucleus' native sibling overlay. */
@Composable
@ExperimentalComposeMediaPlayerBackendApi
public fun TaoNativeVideoView(
    surface: TaoNativeVideoSurface,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
    onAttached: () -> Unit = {},
    onUnavailable: () -> Unit = {},
) {
    val latestOnAttached by rememberUpdatedState(onAttached)
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)
    val overlayViewportSize = remember(surface) { mutableStateOf(IntSize.Zero) }
    val scheduleInterop = currentNucleusInteropScheduler()
    val platformView = remember(surface, scheduleInterop) { surface.toNucleusPlatformView(scheduleInterop) }
    // Nucleus NativeView remembers factory() without using the factory as a key. Give every
    // backend-owned surface its own composition identity so a transactional backend switch first
    // detaches/disposes the old platform view and then creates the replacement handle.
    key(surface) {
        NativeView(
            factory = { platformView },
            modifier = modifier.onSizeChanged { overlayViewportSize.value = it },
            content = {
                SyncNucleusNativeViewOverlayViewport(
                    expectedSize = overlayViewportSize.value,
                    scheduleInterop = scheduleInterop,
                )
                overlay()
            },
        )
    }
    // NativeView attaches its child from DisposableEffect. This coroutine starts after applyChanges,
    // so callers can retire the previous backend without racing Tao's detach/attach transaction.
    LaunchedEffect(surface) {
        if (surface.createdHandle() != 0L) {
            latestOnAttached()
        } else {
            latestOnUnavailable()
        }
    }
}

private fun TaoNativeVideoSurface.toNucleusPlatformView(
    scheduleInterop: ((() -> Unit) -> Unit)?,
): NucleusPlatformView {
    val dispose = {
        if (scheduleInterop != null) {
            // Tao applies native frame updates asynchronously and can enqueue another one while
            // fullscreen/resize is rebuilding layout. The surface owns the quiet-frame barrier.
            disposeAfterInterop(scheduleInterop)
        } else {
            dispose()
        }
    }
    return when (kind) {
        TaoNativeVideoSurfaceKind.MACOS_NS_VIEW ->
            nucleusNsPlatformView(
                handle = ::handle,
                onResize = ::resize,
                onSetBounds = ::setBounds,
                onClearFocus = ::clearFocus,
                onDispose = dispose,
            )
        TaoNativeVideoSurfaceKind.WINDOWS_HWND ->
            nucleusHwndPlatformView(
                handle = ::handle,
                onResize = ::resize,
                onSetBounds = ::setBounds,
                onClearFocus = ::clearFocus,
                onDispose = dispose,
            )
        TaoNativeVideoSurfaceKind.LINUX_GTK_WIDGET ->
            nucleusGtkPlatformView(
                handle = ::handle,
                onResize = ::resize,
                onSetBounds = ::setBounds,
                onClearFocus = ::clearFocus,
                onDispose = dispose,
            )
    }
}

/** More than one quiet frame protects against a layout callback landing between two drains. */
internal const val REQUIRED_QUIET_INTEROP_FENCES: Int = 3
