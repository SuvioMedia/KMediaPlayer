package io.github.kdroidfilter.composemediaplayer.mpv.internal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibMpvLibraryLifetimeTest {
    @Test
    fun embeddedMacVkRuntimeStaysLoadedOnMacOs() {
        assertTrue(
            shouldRetainMpvLibraryForProcessLifetime(
                osName = "Mac OS X",
                embeddedMacVkApiVersion = 1,
            ),
        )
        assertTrue(
            shouldRetainMpvLibraryForProcessLifetime(
                osName = "Darwin",
                embeddedMacVkApiVersion = 1,
            ),
        )
    }

    @Test
    fun ordinaryLibrariesKeepTheirExistingLifetime() {
        assertFalse(
            shouldRetainMpvLibraryForProcessLifetime(
                osName = "Mac OS X",
                embeddedMacVkApiVersion = 0,
            ),
        )
        assertFalse(
            shouldRetainMpvLibraryForProcessLifetime(
                osName = "Linux",
                embeddedMacVkApiVersion = 1,
            ),
        )
    }
}
