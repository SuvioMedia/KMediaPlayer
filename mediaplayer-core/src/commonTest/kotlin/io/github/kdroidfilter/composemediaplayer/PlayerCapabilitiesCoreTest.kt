package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerCapabilitiesCoreTest {
    @Test
    fun explicitHlsSupportSurvivesStructuralCopy() {
        val original = PlayerCapabilities(supportsMkv = true, supportsHls = true)

        val copied = original.copy(supportsPiP = true)

        assertTrue(copied.supportsHls)
        assertTrue(copied.supportsMkv)
        assertTrue(copied.supportsPiP)
    }

    @Test
    fun backendNeutralDefaultDoesNotPromiseHls() {
        assertFalse(PlayerCapabilities().supportsHls)
    }
}
