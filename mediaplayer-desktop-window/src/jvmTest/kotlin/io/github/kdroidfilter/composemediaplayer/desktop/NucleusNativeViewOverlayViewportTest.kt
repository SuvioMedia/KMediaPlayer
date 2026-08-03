package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.IntSize
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class NucleusNativeViewOverlayViewportTest {
    @Test
    fun updatesPrivateOverlaySceneViewportAfterResize() {
        val currentSize = AtomicReference(IntSize(640, 360))
        val scene = composeSceneProxy(currentSize)
        val controller = FakeNucleusOverlayController(scene)

        assertTrue(syncNucleusNativeViewOverlaySceneViewport(controller, IntSize(1280, 720)))
        assertEquals(IntSize(1280, 720), currentSize.get())
        assertFalse(syncNucleusNativeViewOverlaySceneViewport(controller, IntSize(1280, 720)))
    }

    @Test
    fun ignoresUnavailableControllerAndEmptyViewport() {
        assertFalse(syncNucleusNativeViewOverlaySceneViewport(null, IntSize(1280, 720)))
        val currentSize = AtomicReference(IntSize(640, 360))
        val controller = FakeNucleusOverlayController(composeSceneProxy(currentSize))

        assertFalse(syncNucleusNativeViewOverlaySceneViewport(controller, IntSize.Zero))
        assertEquals(IntSize(640, 360), currentSize.get())
    }

    @Test
    fun compensatesMacHitTestForOverlayOffsetInSuperviewCoordinates() {
        val corrected =
            compensateNucleus22MacHitTestRegion(
                localRegion = NucleusOverlayPointerRegion(x = 0, y = 0, width = 1280, height = 2000),
                geometry =
                    NucleusOverlayGeometry(
                        offsetX = 160,
                        offsetY = 0,
                        width = 1280,
                        height = 2000,
                        parentHeight = 2000,
                    ),
            )

        assertEquals(NucleusOverlayPointerRegion(160, 0, 1280, 2000), corrected)
    }

    @Test
    fun compensatesMacHitTestForInsetOverlayFrameOrigin() {
        val corrected =
            compensateNucleus22MacHitTestRegion(
                localRegion = NucleusOverlayPointerRegion(x = 10, y = 20, width = 30, height = 40),
                geometry =
                    NucleusOverlayGeometry(
                        offsetX = 100,
                        offsetY = 200,
                        width = 800,
                        height = 600,
                        parentHeight = 1000,
                    ),
            )

        assertEquals(NucleusOverlayPointerRegion(110, -180, 30, 40), corrected)
    }

    private class FakeNucleusOverlayController(
        @Suppress("unused") private val scene: ComposeScene,
    )
}

@OptIn(InternalComposeUiApi::class)
private fun composeSceneProxy(size: AtomicReference<IntSize>): ComposeScene =
    Proxy.newProxyInstance(
        ComposeScene::class.java.classLoader,
        arrayOf(ComposeScene::class.java),
    ) { proxy, method, arguments ->
        when {
            method.name.startsWith("getSize-") -> size.get()
            method.name.startsWith("setSize-") -> {
                size.set(arguments.single() as IntSize)
                null
            }
            method.name == "toString" -> "ComposeSceneProxy(size=${size.get()})"
            method.name == "hashCode" -> System.identityHashCode(proxy)
            method.name == "equals" -> proxy === arguments.singleOrNull()
            else -> error("Unexpected ComposeScene call: ${method.name}")
        }
    } as ComposeScene
