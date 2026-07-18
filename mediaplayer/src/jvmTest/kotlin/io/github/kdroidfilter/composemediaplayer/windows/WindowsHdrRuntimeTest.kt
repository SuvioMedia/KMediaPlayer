package io.github.kdroidfilter.composemediaplayer.windows

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsHdrRuntimeTest {
    @Test
    fun `requires every confirmed Windows HDR component`() {
        val ready =
            WindowsHdrRuntimeEvaluator.evaluate(
                WindowsHdrRuntimeFacts(
                    isWindows = true,
                    osVersion = "10.0.26100",
                    architecture = "amd64",
                    advancedColorDisplayQueried = true,
                    advancedColorEnabled = true,
                    mediaFoundationD3d11RendererAvailable = true,
                    p010GpuDecodeAvailable = true,
                    flipModelHdrSwapChainAvailable = true,
                ),
            )

        assertTrue(ready.isReady)
        assertTrue(ready.missingRequirements.isEmpty())
    }

    @Test
    fun `BGRA Compose path is never reported as HDR`() {
        val unavailable =
            WindowsHdrRuntimeEvaluator.evaluate(
                WindowsHdrRuntimeFacts(
                    isWindows = true,
                    osVersion = "10.0.19045",
                    architecture = "arm64",
                    advancedColorDisplayQueried = false,
                    advancedColorEnabled = false,
                    mediaFoundationD3d11RendererAvailable = false,
                    p010GpuDecodeAvailable = false,
                    flipModelHdrSwapChainAvailable = false,
                ),
            )

        assertFalse(unavailable.isReady)
        assertFalse(unavailable.displayCapabilities.supportsHdr)
        assertTrue(unavailable.detail.orEmpty().contains("DXGI Advanced Color"))
        assertTrue(unavailable.detail.orEmpty().contains("flip-model"))
    }

    @Test
    fun `rejects Windows builds before 1809 and unsupported architectures`() {
        assertTrue(WindowsHdrRuntimeEvaluator.windowsBuild("10.0.17763") == 17_763)
        assertFalse(WindowsHdrRuntimeEvaluator.isSupportedArchitecture("x86"))
        assertTrue(WindowsHdrRuntimeEvaluator.isSupportedArchitecture("aarch64"))
    }
}
