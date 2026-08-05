package io.github.kdroidfilter.composemediaplayer.desktop.tao

import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)
class TaoNativeVideoSurfaceTest {
    @Test
    fun `native handle is created and disposed exactly once`() {
        var createCalls = 0
        val disposed = mutableListOf<Long>()
        val surface =
            TaoNativeVideoSurface(
                kind = TaoNativeVideoSurfaceKind.MACOS_NS_VIEW,
                createHandle = {
                    createCalls++
                    42L
                },
                disposeHandle = disposed::add,
            )

        assertEquals(42L, surface.handle())
        assertEquals(42L, surface.handle())
        surface.dispose()
        surface.dispose()

        assertEquals(1, createCalls)
        assertEquals(listOf(42L), disposed)
        assertEquals(0L, surface.handle())
    }

    @Test
    fun `failed creation is stable until a new surface lifecycle is composed`() {
        var createCalls = 0
        val surface =
            TaoNativeVideoSurface(
                kind = TaoNativeVideoSurfaceKind.LINUX_GTK_WIDGET,
                createHandle = {
                    createCalls++
                    0L
                },
                disposeHandle = {},
            )

        assertEquals(0L, surface.handle())
        assertEquals(0L, surface.handle())
        surface.dispose()

        assertEquals(1, createCalls)
    }

    @Test
    fun `native handle stays alive until deferred interop disposal runs`() {
        val disposed = mutableListOf<Long>()
        val scheduled = mutableListOf<() -> Unit>()
        val surface =
            TaoNativeVideoSurface(
                kind = TaoNativeVideoSurfaceKind.MACOS_NS_VIEW,
                createHandle = { 42L },
                disposeHandle = disposed::add,
            )

        assertEquals(42L, surface.handle())
        surface.disposeAfterInterop(scheduled::add)

        assertEquals(emptyList(), disposed)
        assertEquals(1, scheduled.size)
        repeat(REQUIRED_QUIET_INTEROP_FENCES - 1) {
            scheduled.removeFirst().invoke()
            assertEquals(emptyList(), disposed)
        }
        scheduled.removeFirst().invoke()
        assertEquals(listOf(42L), disposed)
    }

    @Test
    fun `late layout activity restarts the quiet interop disposal barrier`() {
        val disposed = mutableListOf<Long>()
        val scheduled = mutableListOf<() -> Unit>()
        val surface =
            TaoNativeVideoSurface(
                kind = TaoNativeVideoSurfaceKind.MACOS_NS_VIEW,
                createHandle = { 42L },
                disposeHandle = disposed::add,
            )

        assertEquals(42L, surface.handle())
        surface.disposeAfterInterop(scheduled::add)

        assertEquals(emptyList(), disposed)
        scheduled.removeFirst().invoke()
        assertEquals(emptyList(), disposed)

        // Mirrors Nucleus calling view.resize()/setBounds() after it queued host.setFrame().
        surface.resize(1280, 720)
        scheduled.removeFirst().invoke()
        assertEquals(emptyList(), disposed)

        repeat(REQUIRED_QUIET_INTEROP_FENCES - 1) {
            scheduled.removeFirst().invoke()
            assertEquals(emptyList(), disposed)
        }
        scheduled.removeFirst().invoke()
        assertEquals(listOf(42L), disposed)
    }
}
