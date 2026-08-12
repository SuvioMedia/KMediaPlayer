package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.desktop.OptionalDesktopPlaybackBackendOptions
import io.github.kdroidfilter.composemediaplayer.desktop.loadOptionalDesktopPlaybackBackends
import io.github.shusek.kmediavlc.runtime.desktop.VlcFrameDeliveryMode
import io.github.shusek.kmediavlc.runtime.desktop.VlcRenderEngine
import io.github.shusek.kmediavlc.runtime.desktop.VlcRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

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
    fun autoUsesCpuPullForProjectedVideo() {
        assertEquals(
            VlcFrameDeliveryMode.CPU_PULL,
            LibVlcPlaybackOptions(
                projection = VideoProjectionSettings(projectionType = VideoProjectionType.Equirect360),
            ).effectiveDeliveryMode(),
        )
    }

    @Test
    fun autoUsesCpuPullForTextureCrop() {
        assertEquals(
            VlcFrameDeliveryMode.CPU_PULL,
            LibVlcPlaybackOptions(
                projectionTextureCrop = VideoTextureCrop(left = 0.1f),
            ).effectiveDeliveryMode(),
        )
    }

    @Test
    fun explicitGpuProjectionFailsBeforeRuntimeResolution() {
        val unavailable =
            assertIs<LibVlcBackendAvailability.Unavailable>(
                inspectLibVlcBackend(
                    LibVlcPlaybackOptions(
                        frameDeliveryPolicy = LibVlcFrameDeliveryPolicy.GPU_PUSH,
                        projection = VideoProjectionSettings(projectionType = VideoProjectionType.Fisheye200),
                    ),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE, unavailable.reason)
    }

    @Test
    fun requiredHdrRejectsProjectedCpuFallbackBeforeRuntimeResolution() {
        val unavailable =
            assertIs<LibVlcBackendAvailability.Unavailable>(
                inspectLibVlcBackend(
                    LibVlcPlaybackOptions(
                        dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR,
                        projection = VideoProjectionSettings(projectionType = VideoProjectionType.Eac360),
                    ),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.GPU_OUTPUT_UNAVAILABLE, unavailable.reason)
    }

    @Test
    fun mapsEveryDesktopGpuEngine() {
        assertEquals(VlcRenderEngine.D3D11, renderEngineForOsName("Windows 11"))
        assertEquals(VlcRenderEngine.OPENGL, renderEngineForOsName("Mac OS X"))
        assertEquals(VlcRenderEngine.OPENGL, renderEngineForOsName("Darwin"))
        assertEquals(VlcRenderEngine.GLES2, renderEngineForOsName("Linux"))
        assertEquals(null, renderEngineForOsName("FreeBSD"))
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

    @Test
    fun distinguishesMissingAndRejectedBundledRuntime() {
        assertEquals(
            LibVlcBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
            unavailableBundledLibVlcBackend(VlcRuntimeException.Reason.PAYLOAD_MISSING).reason,
        )
        assertEquals(
            LibVlcBackendUnavailableReason.INVALID_RUNTIME,
            unavailableBundledLibVlcBackend(VlcRuntimeException.Reason.MANIFEST_REJECTED).reason,
        )
    }
}
