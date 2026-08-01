package io.github.kdroidfilter.composemediaplayer.mac

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacDedicatedNativeWindowRenderPolicyTest {
    @Test
    fun keepsDedicatedNativeWindowRenderedInFullscreen() {
        assertTrue(
            shouldRenderMacVideoSurface(
                hasMedia = true,
                libVlcNativeSurfaceRequested = false,
                isFullscreen = true,
                isInFullscreenWindow = false,
                usesDedicatedNativeWindow = true,
            ),
        )
    }

    @Test
    fun hidesOrdinaryInlineSurfaceWhenSeparateFullscreenWindowOwnsVideo() {
        assertFalse(
            shouldRenderMacVideoSurface(
                hasMedia = true,
                libVlcNativeSurfaceRequested = false,
                isFullscreen = true,
                isInFullscreenWindow = false,
                usesDedicatedNativeWindow = false,
            ),
        )
    }
}
