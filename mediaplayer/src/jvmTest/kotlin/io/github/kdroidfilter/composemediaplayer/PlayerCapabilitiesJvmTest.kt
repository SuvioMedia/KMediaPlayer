package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerCapabilitiesJvmTest {
    @Test
    fun `platform backend reports only native container support`() {
        val options = VideoPlaybackOptions(desktopVideoBackend = DesktopVideoBackend.PLATFORM)
        val capabilities =
            jvmPlayerCapabilities(options)

        assertEquals(CurrentPlatform.os == CurrentPlatform.OS.LINUX, capabilities.supportsMkv)
        assertTrue(capabilities.supportsExternalAudioTracks)
        assertEquals(
            capabilities,
            defaultVideoPlayerBackend(playbackOptions = options).info.capabilities,
        )
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

    @Test
    fun `desktop capabilities only advertise URI schemes accepted by openUri`() {
        val capabilities = jvmPlayerCapabilities(VideoPlaybackOptions())

        assertTrue(capabilities.canPlaySource("file:///movie.mp4"))
        assertTrue(capabilities.canPlaySource("""C:\Videos\movie.mp4"""))
        assertTrue(capabilities.canPlaySource("""\\server\share\movie.mp4"""))
        assertTrue(capabilities.canPlaySource("https://example.test/movie.mp4"))
        assertFalse(capabilities.canPlaySource("blob:https://example.test/movie.mp4"))
        assertFalse(capabilities.canPlaySource("content://media/external/video/1"))
        assertFalse(capabilities.canPlaySource("data:video/mp4;base64,AAA"))
    }
}
