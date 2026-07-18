package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmDesktopBackendSelectionTest {
    @Test
    fun `dynamic range policy never promotes libvlc to confirmed HDR backend`() {
        DynamicRangePolicy.entries.forEach { policy ->
            assertNull(
                VideoPlaybackOptions(
                    dynamicRangePolicy = policy,
                    desktopVideoBackend = DesktopVideoBackend.AUTO,
                ).forcedJvmDesktopBackend(),
            )
        }
    }

    @Test
    fun `explicit desktop backend wins over native hdr preference`() {
        assertEquals(
            JVM_DESKTOP_BACKEND_PLATFORM,
            VideoPlaybackOptions(
                dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR,
                desktopVideoBackend = DesktopVideoBackend.PLATFORM,
            ).forcedJvmDesktopBackend(),
        )
        assertEquals(
            JVM_DESKTOP_BACKEND_LIBVLC,
            VideoPlaybackOptions(
                dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR,
                desktopVideoBackend = DesktopVideoBackend.LIBVLC,
            ).forcedJvmDesktopBackend(),
        )
        assertEquals(
            JVM_DESKTOP_BACKEND_LIBVLC_NATIVE_VIEW,
            VideoPlaybackOptions(
                dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                desktopVideoBackend = DesktopVideoBackend.LIBVLC_NATIVE,
            ).forcedJvmDesktopBackend(),
        )
    }
}
