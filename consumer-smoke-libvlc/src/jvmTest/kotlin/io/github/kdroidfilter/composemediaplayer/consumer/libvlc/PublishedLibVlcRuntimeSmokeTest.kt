package io.github.kdroidfilter.composemediaplayer.consumer.libvlc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PublishedLibVlcRuntimeSmokeTest {
    @Test
    fun adapterExposesAnInjectableBackend() {
        assertEquals("libvlc4", publishedLibVlcBackends().single().info.id)
    }

    @Test
    fun desktopRuntimeIsTransitivelyAvailable() {
        assertNotNull(
            Class.forName("io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopRuntime"),
        )
    }
}
