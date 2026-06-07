package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerCapabilitiesJvmTest {
    @Test
    fun `platform backend reports only native container support`() {
        val capabilities =
            jvmPlayerCapabilities(
                VideoPlaybackOptions(desktopVideoBackend = DesktopVideoBackend.PLATFORM),
            )

        assertEquals(CurrentPlatform.os == CurrentPlatform.OS.LINUX, capabilities.supportsMkv)
    }

    @Test
    fun `libvlc native backend is reported as macOS only`() {
        val libVlcCapabilities =
            jvmPlayerCapabilities(
                VideoPlaybackOptions(desktopVideoBackend = DesktopVideoBackend.LIBVLC),
            )
        val libVlcNativeCapabilities =
            jvmPlayerCapabilities(
                VideoPlaybackOptions(desktopVideoBackend = DesktopVideoBackend.LIBVLC_NATIVE),
            )
        val expectedSupportsMkv =
            CurrentPlatform.os == CurrentPlatform.OS.MAC && libVlcCapabilities.supportsMkv

        assertEquals(expectedSupportsMkv, libVlcNativeCapabilities.supportsMkv)
    }
}
