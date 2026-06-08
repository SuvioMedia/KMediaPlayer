package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmDesktopBackendSelectionTest {
    @Test
    fun `auto native hdr forces libvlc native view`() {
        val playbackOptions =
            VideoPlaybackOptions(
                videoOutputMode = VideoOutputMode.NATIVE_HDR,
                desktopVideoBackend = DesktopVideoBackend.AUTO,
            )

        assertEquals(JVM_DESKTOP_BACKEND_LIBVLC_NATIVE_VIEW, playbackOptions.forcedJvmDesktopBackend())
    }

    @Test
    fun `auto non native hdr keeps backend unforced`() {
        assertNull(VideoPlaybackOptions().forcedJvmDesktopBackend())
        assertNull(VideoPlaybackOptions(videoOutputMode = VideoOutputMode.COMPOSE_SDR).forcedJvmDesktopBackend())
        assertNull(VideoPlaybackOptions(videoOutputMode = VideoOutputMode.TONE_MAPPED_SDR).forcedJvmDesktopBackend())
    }

    @Test
    fun `explicit desktop backend wins over native hdr preference`() {
        assertEquals(
            JVM_DESKTOP_BACKEND_PLATFORM,
            VideoPlaybackOptions(
                videoOutputMode = VideoOutputMode.NATIVE_HDR,
                desktopVideoBackend = DesktopVideoBackend.PLATFORM,
            ).forcedJvmDesktopBackend(),
        )
        assertEquals(
            JVM_DESKTOP_BACKEND_LIBVLC,
            VideoPlaybackOptions(
                videoOutputMode = VideoOutputMode.NATIVE_HDR,
                desktopVideoBackend = DesktopVideoBackend.LIBVLC,
            ).forcedJvmDesktopBackend(),
        )
        assertEquals(
            JVM_DESKTOP_BACKEND_LIBVLC_NATIVE_VIEW,
            VideoPlaybackOptions(
                videoOutputMode = VideoOutputMode.COMPOSE_SDR,
                desktopVideoBackend = DesktopVideoBackend.LIBVLC_NATIVE,
            ).forcedJvmDesktopBackend(),
        )
    }
}
