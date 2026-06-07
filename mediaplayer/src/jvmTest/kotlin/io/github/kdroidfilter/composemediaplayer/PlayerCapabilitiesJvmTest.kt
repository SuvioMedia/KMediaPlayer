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
    fun `libvlc native backend follows libvlc availability on desktop`() {
        val libVlcCapabilities =
            jvmPlayerCapabilities(
                VideoPlaybackOptions(desktopVideoBackend = DesktopVideoBackend.LIBVLC),
            )
        val libVlcNativeCapabilities =
            jvmPlayerCapabilities(
                VideoPlaybackOptions(desktopVideoBackend = DesktopVideoBackend.LIBVLC_NATIVE),
            )
        assertEquals(libVlcCapabilities.supportsMkv, libVlcNativeCapabilities.supportsMkv)
    }
}
