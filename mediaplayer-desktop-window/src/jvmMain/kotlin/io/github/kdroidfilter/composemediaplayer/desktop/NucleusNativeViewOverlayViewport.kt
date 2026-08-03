@file:Suppress("TooManyFunctions")

package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.IntSize
import dev.nucleusframework.window.tao.consumeOverlayPointerEvents
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private const val NUCLEUS_NATIVE_VIEW_CLASS = "dev.nucleusframework.window.tao.NativeViewKt"
private const val NUCLEUS_OVERLAY_LOCAL_GETTER = "getLocalNativeViewOverlayController"
private const val NUCLEUS_NATIVE_VIEW_HOST_LOCAL_GETTER = "getLocalTaoNativeViewHost"
private const val NUCLEUS_OVERLAY_SCENE_FIELD = "scene"
private const val NUCLEUS_MAC_NATIVE_VIEW_BRIDGE_CLASS =
    "dev.nucleusframework.window.tao.ffi.NativeTaoMacOsNativeViewBridge"
private const val NUCLEUS_METAL_BRIDGE_CLASS = "dev.nucleusframework.window.tao.ffi.NativeMetalBridge"
private const val REGISTER_REGION_PARAMETER_COUNT = 5

/**
 * Keeps Nucleus 2.2.0's macOS NativeView overlay scene aligned with its resized NSView.
 *
 * That release updates its cached width/height before the deferred transaction compares them,
 * making the scene-size branch unreachable after the first layout. The native frame consequently
 * resizes while Compose controls keep their previous viewport. This narrow compatibility shim can
 * disappear once Nucleus updates the scene size before overwriting its cached dimensions.
 */
@Composable
@OptIn(InternalComposeUiApi::class)
internal fun SyncNucleusNativeViewOverlayViewport(
    expectedSize: IntSize,
    scheduleInterop: (((() -> Unit) -> Unit))?,
) {
    val overlayLocal = nucleusOverlayControllerLocal ?: return
    val overlayController = overlayLocal.current
    val pendingSize = remember(overlayController) { arrayOfNulls<IntSize>(1) }
    SideEffect {
        if (!nucleusOverlayViewportNeedsRepair(overlayController, expectedSize)) return@SideEffect
        if (pendingSize[0] == expectedSize) return@SideEffect
        pendingSize[0] = expectedSize

        val repair = {
            repairNucleusNativeViewOverlayViewport(overlayController, expectedSize)
            if (pendingSize[0] == expectedSize) pendingSize[0] = null
            Unit
        }
        if (scheduleInterop != null) {
            runCatching { scheduleInterop(repair) }.onFailure { repair() }
        } else {
            repair()
        }
    }
}

/**
 * Marks an interactive Compose region rendered over a native desktop video surface.
 *
 * Nucleus 2.2.0 forwards pointer events in overlay-local pixels, but its macOS `hitTest:` checks
 * the registered regions against the point supplied in the overlay's superview coordinates. The
 * error is invisible when the native view starts at x=0 and is why controls near the right edge
 * stopped responding beside a navigation rail. Registering the same region in that coordinate
 * space preserves normal Nucleus behaviour on Windows/Linux and corrects the bounded macOS bug.
 */
public fun Modifier.consumeNativeVideoOverlayPointerEvents(cursor: PointerIcon? = null): Modifier =
    composed {
        val nucleusModifier = this.consumeOverlayPointerEvents(cursor)
        val overlayLocal = nucleusOverlayControllerLocal ?: return@composed nucleusModifier
        val overlayController = overlayLocal.current ?: return@composed nucleusModifier
        val compatibilityKey = remember(overlayController) { Any() }

        DisposableEffect(overlayController, compatibilityKey) {
            onDispose {
                unregisterNucleusOverlayRegion(overlayController, compatibilityKey)
            }
        }

        nucleusModifier.onGloballyPositioned { coordinates ->
            val geometry = nucleusOverlayGeometry(overlayController) ?: return@onGloballyPositioned
            val position = coordinates.positionInRoot()
            val localRegion =
                NucleusOverlayPointerRegion(
                    x = position.x.roundToInt(),
                    y = position.y.roundToInt(),
                    width = coordinates.size.width,
                    height = coordinates.size.height,
                )
            val hitTestRegion = compensateNucleus22MacHitTestRegion(localRegion, geometry)
            registerNucleusOverlayRegion(overlayController, compatibilityKey, hitTestRegion)
        }
    }

@Composable
internal fun currentNucleusInteropScheduler(): ((() -> Unit) -> Unit)? {
    val hostLocal = nucleusNativeViewHostLocal ?: return null
    val host = hostLocal.current ?: return null
    return remember(host) {
        val scheduleInterop = nucleusScheduleInteropMethod(host.javaClass) ?: return@remember null
        val scheduler: (() -> Unit) -> Unit = { action -> scheduleInterop.invoke(host, action) }
        scheduler
    }
}

@OptIn(InternalComposeUiApi::class)
internal fun syncNucleusNativeViewOverlaySceneViewport(
    overlayController: Any?,
    expectedSize: IntSize,
): Boolean {
    if (overlayController == null || expectedSize.width <= 0 || expectedSize.height <= 0) return false
    val scene = nucleusOverlayScene(overlayController) ?: return false
    if (scene.size == expectedSize) return false
    scene.size = expectedSize
    return true
}

@OptIn(InternalComposeUiApi::class)
private fun nucleusOverlayViewportNeedsRepair(
    overlayController: Any?,
    expectedSize: IntSize,
): Boolean {
    if (overlayController == null || expectedSize.width <= 0 || expectedSize.height <= 0) return false
    return nucleusOverlayScene(overlayController)?.size != expectedSize
}

/**
 * Runs after Nucleus' own queued resize transaction. Nucleus 2.2.0 compares the captured target
 * size with fields it has already overwritten, so an earlier intermediate resize can restore the
 * old scene viewport after our layout pass. Re-applying the complete AppKit/Metal/scene tuple at
 * the end of that same interop queue makes the last requested size authoritative.
 */
@OptIn(InternalComposeUiApi::class)
private fun repairNucleusNativeViewOverlayViewport(
    overlayController: Any?,
    expectedSize: IntSize,
): Boolean {
    if (overlayController == null || expectedSize.width <= 0 || expectedSize.height <= 0) return false
    val scene = nucleusOverlayScene(overlayController) ?: return false
    val geometry = nucleusOverlayGeometry(overlayController)
    var nativeViewportRepaired = false
    if (geometry?.width == expectedSize.width && geometry.height == expectedSize.height) {
        val controllerClass = overlayController.javaClass
        val overlayHandle = controllerClass.readLongField(overlayController, "overlayNsView") ?: 0L
        val attachmentHandle = controllerClass.readLongField(overlayController, "attachmentHandle") ?: 0L
        val scale = controllerClass.readFloatField(overlayController, "scale") ?: 1f
        if (overlayHandle != 0L) {
            nativeViewportRepaired =
                runCatching {
                    nucleusNativeSetOverlayFrameMethod?.invoke(
                        null,
                        overlayHandle,
                        geometry.offsetX,
                        geometry.offsetY,
                        expectedSize.width,
                        expectedSize.height,
                    )
                    if (attachmentHandle != 0L) {
                        nucleusNativeResizeOverlayMethod?.invoke(
                            null,
                            attachmentHandle,
                            expectedSize.width,
                            expectedSize.height,
                            scale,
                        )
                    }
                    true
                }.getOrDefault(false)
        }
    }
    val sceneViewportRepaired = scene.size != expectedSize
    if (sceneViewportRepaired) scene.size = expectedSize
    return nativeViewportRepaired || sceneViewportRepaired
}

@OptIn(InternalComposeUiApi::class)
private fun nucleusOverlayScene(overlayController: Any?): ComposeScene? {
    if (overlayController == null) return null
    val sceneField = nucleusOverlaySceneField(overlayController.javaClass) ?: return null
    return runCatching { sceneField.get(overlayController) as? ComposeScene }.getOrNull()
}

@Suppress("UNCHECKED_CAST")
private val nucleusOverlayControllerLocal: CompositionLocal<Any?>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
        Class
            .forName(NUCLEUS_NATIVE_VIEW_CLASS)
            .getMethod(NUCLEUS_OVERLAY_LOCAL_GETTER)
            .invoke(null) as CompositionLocal<Any?>
    }.getOrNull()
}

@Suppress("UNCHECKED_CAST")
private val nucleusNativeViewHostLocal: CompositionLocal<Any?>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
        Class
            .forName(NUCLEUS_NATIVE_VIEW_CLASS)
            .getMethod(NUCLEUS_NATIVE_VIEW_HOST_LOCAL_GETTER)
            .invoke(null) as CompositionLocal<Any?>
    }.getOrNull()
}

private val nucleusOverlaySceneFields = ConcurrentHashMap<Class<*>, Field>()
private val nucleusScheduleInteropMethods = ConcurrentHashMap<Class<*>, Method>()
private val nucleusRegisterRegionMethods = ConcurrentHashMap<Class<*>, Method>()
private val nucleusUnregisterRegionMethods = ConcurrentHashMap<Class<*>, Method>()

private val nucleusNativeSetOverlayFrameMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
        Class
            .forName(NUCLEUS_MAC_NATIVE_VIEW_BRIDGE_CLASS)
            .getMethod(
                "nativeSetOverlayFrame",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
    }.getOrNull()
}

private val nucleusNativeResizeOverlayMethod: Method? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    runCatching {
        Class
            .forName(NUCLEUS_METAL_BRIDGE_CLASS)
            .getMethod(
                "nativeResize",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType,
            )
    }.getOrNull()
}

private fun nucleusScheduleInteropMethod(hostClass: Class<*>): Method? {
    nucleusScheduleInteropMethods[hostClass]?.let { return it }
    return runCatching {
        hostClass.methods.single { method -> method.name == "scheduleInterop" && method.parameterCount == 1 }
    }.getOrNull()?.also { method -> nucleusScheduleInteropMethods[hostClass] = method }
}

private fun nucleusOverlaySceneField(controllerClass: Class<*>): Field? {
    nucleusOverlaySceneFields[controllerClass]?.let { return it }
    return runCatching {
        controllerClass.getDeclaredField(NUCLEUS_OVERLAY_SCENE_FIELD).also { field ->
            check(field.trySetAccessible()) { "Nucleus overlay scene is not accessible." }
        }
    }.getOrNull()?.also { field -> nucleusOverlaySceneFields[controllerClass] = field }
}

internal data class NucleusOverlayPointerRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal data class NucleusOverlayGeometry(
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
    val parentHeight: Int,
)

internal fun compensateNucleus22MacHitTestRegion(
    localRegion: NucleusOverlayPointerRegion,
    geometry: NucleusOverlayGeometry,
): NucleusOverlayPointerRegion {
    val frameOriginY = geometry.parentHeight - geometry.offsetY - geometry.height
    return localRegion.copy(
        x = localRegion.x + geometry.offsetX,
        y = localRegion.y - frameOriginY,
    )
}

private fun nucleusOverlayGeometry(overlayController: Any): NucleusOverlayGeometry? {
    val controllerClass = overlayController.javaClass
    val offsetX = controllerClass.readIntField(overlayController, "overlayOffsetX") ?: return null
    val offsetY = controllerClass.readIntField(overlayController, "overlayOffsetY") ?: return null
    val width = controllerClass.readIntField(overlayController, "widthPx") ?: return null
    val height = controllerClass.readIntField(overlayController, "heightPx") ?: return null
    if (width <= 0 || height <= 0) return null
    val parentHeight = nucleusParentWindowHeight(controllerClass, overlayController) ?: (offsetY + height)
    return NucleusOverlayGeometry(offsetX, offsetY, width, height, parentHeight)
}

private fun nucleusParentWindowHeight(
    controllerClass: Class<*>,
    overlayController: Any,
): Int? =
    runCatching {
        val popupHostField =
            controllerClass.getDeclaredField("popupHost").also { field ->
                check(field.trySetAccessible())
            }
        val popupHost = popupHostField.get(overlayController)
        val sizeGetter =
            popupHost.javaClass.methods.single { method ->
                method.name.startsWith("getParentWindowSize") && method.parameterCount == 0
            }
        when (val packedSize = sizeGetter.invoke(popupHost)) {
            is IntSize -> packedSize.height
            is Long -> packedSize.toInt()
            else -> null
        }
    }.getOrNull()

private fun Class<*>.readIntField(
    instance: Any,
    name: String,
): Int? =
    runCatching {
        getDeclaredField(name)
            .also { field -> check(field.trySetAccessible()) }
            .getInt(instance)
    }.getOrNull()

private fun Class<*>.readLongField(
    instance: Any,
    name: String,
): Long? =
    runCatching {
        getDeclaredField(name)
            .also { field -> check(field.trySetAccessible()) }
            .getLong(instance)
    }.getOrNull()

private fun Class<*>.readFloatField(
    instance: Any,
    name: String,
): Float? =
    runCatching {
        getDeclaredField(name)
            .also { field -> check(field.trySetAccessible()) }
            .getFloat(instance)
    }.getOrNull()

private fun registerNucleusOverlayRegion(
    overlayController: Any,
    key: Any,
    region: NucleusOverlayPointerRegion,
) {
    val method = nucleusRegisterRegionMethod(overlayController.javaClass) ?: return
    runCatching {
        method.invoke(overlayController, key, region.x, region.y, region.width, region.height)
    }
}

private fun unregisterNucleusOverlayRegion(
    overlayController: Any,
    key: Any,
) {
    val method = nucleusUnregisterRegionMethod(overlayController.javaClass) ?: return
    runCatching { method.invoke(overlayController, key) }
}

private fun nucleusRegisterRegionMethod(controllerClass: Class<*>): Method? {
    nucleusRegisterRegionMethods[controllerClass]?.let { return it }
    return controllerClass.methods
        .firstOrNull { method ->
            method.name == "registerRegion" && method.parameterCount == REGISTER_REGION_PARAMETER_COUNT
        }?.also { method -> nucleusRegisterRegionMethods[controllerClass] = method }
}

private fun nucleusUnregisterRegionMethod(controllerClass: Class<*>): Method? {
    nucleusUnregisterRegionMethods[controllerClass]?.let { return it }
    return controllerClass.methods
        .firstOrNull { method -> method.name == "unregisterRegion" && method.parameterCount == 1 }
        ?.also { method -> nucleusUnregisterRegionMethods[controllerClass] = method }
}
