package io.github.kdroidfilter.composemediaplayer.consumer.mpv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PublishedMpvRuntimeSmokeTest {
    @Test
    fun adapterExposesAnInjectableBackend() {
        assertEquals("mpv", publishedBackends().last().info.id)
    }

    @Test
    fun desktopRuntimeIsTransitivelyAvailable() {
        assertNotNull(
            Class.forName("io.github.shusek.kmediampv.runtime.desktop.MpvDesktopRuntime"),
        )
    }
}
