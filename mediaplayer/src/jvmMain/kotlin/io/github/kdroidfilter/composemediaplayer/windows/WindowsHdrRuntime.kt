package io.github.kdroidfilter.composemediaplayer.windows

import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities

/** Facts are kept separate from the evaluator so Windows HDR gating is unit-testable off Windows. */
internal data class WindowsHdrRuntimeFacts(
    val isWindows: Boolean,
    val osVersion: String?,
    val architecture: String?,
    val advancedColorDisplayQueried: Boolean,
    val advancedColorEnabled: Boolean,
    val mediaFoundationD3d11RendererAvailable: Boolean,
    val p010GpuDecodeAvailable: Boolean,
    val flipModelHdrSwapChainAvailable: Boolean,
)

internal data class WindowsHdrRuntimeStatus(
    val isReady: Boolean,
    val missingRequirements: List<String>,
    val displayCapabilities: DisplayColorCapabilities,
) {
    val detail: String?
        get() =
            missingRequirements
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString(prefix = "Windows HDR unavailable: ", separator = "; ", postfix = ".")
}

internal object WindowsHdrRuntimeEvaluator {
    fun evaluate(facts: WindowsHdrRuntimeFacts): WindowsHdrRuntimeStatus {
        val missing =
            buildList {
                if (!facts.isWindows || windowsBuild(facts.osVersion) < MINIMUM_WINDOWS_BUILD) {
                    add("Windows 10 1809 (build 17763) or newer is required")
                }
                if (!isSupportedArchitecture(facts.architecture)) {
                    add("only x64 and ARM64 native renderers are supported")
                }
                if (!facts.advancedColorDisplayQueried) {
                    add("the active output's DXGI Advanced Color state is not queried")
                } else if (!facts.advancedColorEnabled) {
                    add("HDR/Advanced Color is disabled on the active output")
                }
                if (!facts.mediaFoundationD3d11RendererAvailable) {
                    add("the Media Foundation + D3D11 HDR renderer is not installed")
                }
                if (!facts.p010GpuDecodeAvailable) add("P010 GPU decode is unavailable")
                if (!facts.flipModelHdrSwapChainAvailable) {
                    add("no HDR flip-model swapchain with a confirmed DXGI color space is available")
                }
            }
        return WindowsHdrRuntimeStatus(
            isReady = missing.isEmpty(),
            missingRequirements = missing,
            // Do not advertise HDR based on OS/build alone. A future DXGI probe fills this only
            // after inspecting the output containing the player HWND.
            displayCapabilities = DisplayColorCapabilities(isKnown = facts.advancedColorDisplayQueried),
        )
    }

    @Suppress("MagicNumber")
    internal fun windowsBuild(version: String?): Int {
        val numbers = version?.split('.')?.mapNotNull(String::toIntOrNull).orEmpty()
        return when {
            numbers.size >= 3 -> numbers[2]
            numbers.size == 1 && numbers[0] >= MINIMUM_WINDOWS_BUILD -> numbers[0]
            else -> -1
        }
    }

    internal fun isSupportedArchitecture(architecture: String?): Boolean =
        architecture
            ?.lowercase()
            ?.replace("-", "")
            ?.let { it == "amd64" || it == "x8664" || it == "aarch64" || it == "arm64" }
            ?: false

    private const val MINIMUM_WINDOWS_BUILD = 17_763
}

internal object WindowsHdrRuntimeProbe {
    fun query(): WindowsHdrRuntimeStatus =
        WindowsHdrRuntimeEvaluator.evaluate(
            WindowsHdrRuntimeFacts(
                isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
                osVersion = System.getProperty("os.version"),
                architecture = System.getProperty("os.arch"),
                // The current JNI path decodes/copies BGRA frames into a Compose canvas. These
                // stay false until the native renderer owns an HWND/swapchain and can query the
                // output through IDXGIOutput6.
                advancedColorDisplayQueried = false,
                advancedColorEnabled = false,
                mediaFoundationD3d11RendererAvailable = false,
                p010GpuDecodeAvailable = false,
                flipModelHdrSwapChainAvailable = false,
            ),
        )
}
