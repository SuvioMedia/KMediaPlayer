package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebHlsCapabilityTest {
    @Test
    fun hlsIsSupportedWhenEitherNativeOrBundledBackendIsAvailable() {
        assertTrue(combineWebHlsSupport(nativeHlsSupported = true, bundledHlsSupported = false))
        assertTrue(combineWebHlsSupport(nativeHlsSupported = false, bundledHlsSupported = true))
        assertTrue(combineWebHlsSupport(nativeHlsSupported = true, bundledHlsSupported = true))
        assertFalse(combineWebHlsSupport(nativeHlsSupported = false, bundledHlsSupported = false))
    }
}
