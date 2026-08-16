package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertNotNull

class JvmMediaAdvancedControlsTest {
    @Test
    fun `default desktop player exposes advanced controls from its platform delegate`() {
        val playerState = DefaultVideoPlayerState()
        try {
            assertNotNull(playerState.jvmMediaAdvancedControls)
        } finally {
            playerState.dispose()
        }
    }
}
