package io.github.kdroidfilter.composemediaplayer

import io.github.shusek.kmediavlc.runtime.desktop.VlcFrameDeliveryMode
import io.github.kdroidfilter.composemediaplayer.desktop.OptionalDesktopPlaybackBackendOptions
import io.github.kdroidfilter.composemediaplayer.desktop.loadOptionalDesktopPlaybackBackends
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibVlcPlaybackOptionsTest {
    @Test
    fun autoUsesGpuForColorManagedTexture() {
        assertEquals(
            VlcFrameDeliveryMode.GPU_PUSH,
            LibVlcPlaybackOptions().effectiveDeliveryMode(),
        )
    }

    @Test
    fun composeIsExplicitCpuPull() {
        assertEquals(
            VlcFrameDeliveryMode.CPU_PULL,
            LibVlcPlaybackOptions(
                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.COMPOSE,
            ).effectiveDeliveryMode(),
        )
    }

    @Test
    fun requireHdrRejectsCpuCompose() {
        assertFailsWith<IllegalArgumentException> {
            LibVlcPlaybackOptions(
                dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR,
                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.COMPOSE,
            )
        }
    }

    @Test
    fun optionalArtifactRegistersOneTextureBackend() {
        val backends = loadOptionalDesktopPlaybackBackends(OptionalDesktopPlaybackBackendOptions())

        assertEquals(listOf("libvlc4-texture"), backends.map { backend -> backend.info.id })
    }
}
