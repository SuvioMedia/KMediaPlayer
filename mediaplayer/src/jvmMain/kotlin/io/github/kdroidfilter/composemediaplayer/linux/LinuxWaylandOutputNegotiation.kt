package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange

internal const val WAYLAND_OUTPUT_ATTACHED = 1 shl 0
internal const val WAYLAND_OUTPUT_CAPS_NEGOTIATED = 1 shl 1
internal const val WAYLAND_OUTPUT_TEN_BIT = 1 shl 2
internal const val WAYLAND_OUTPUT_PQ = 1 shl 3
internal const val WAYLAND_OUTPUT_HLG = 1 shl 4
internal const val WAYLAND_OUTPUT_DMABUF = 1 shl 5
internal const val WAYLAND_OUTPUT_ERROR = 1 shl 6
internal const val WAYLAND_OUTPUT_FIRST_FRAME = 1 shl 7
internal const val WAYLAND_OUTPUT_HDR10_PLUS_APPLIED = 1 shl 8
internal const val WAYLAND_OUTPUT_HDR10_PLUS_UNAVAILABLE = 1 shl 9

internal enum class LinuxWaylandNegotiationState {
    PENDING,
    VERIFIED,
    FAILED,
}

internal data class LinuxWaylandNegotiationResult(
    val state: LinuxWaylandNegotiationState,
    val detail: String? = null,
)

internal object LinuxWaylandOutputNegotiation {
    @Suppress("ReturnCount")
    fun evaluate(
        nativeState: Int,
        sourceDynamicRange: VideoDynamicRange,
        requireDmaBuf: Boolean = false,
        requireHdr10PlusApplication: Boolean = false,
    ): LinuxWaylandNegotiationResult {
        if (requireHdr10PlusApplication && nativeState and WAYLAND_OUTPUT_HDR10_PLUS_UNAVAILABLE != 0) {
            return LinuxWaylandNegotiationResult(
                LinuxWaylandNegotiationState.FAILED,
                "GStreamer did not expose valid per-frame HDR10+ metadata to the Vulkan renderer.",
            )
        }
        if (nativeState and WAYLAND_OUTPUT_ERROR != 0) {
            return LinuxWaylandNegotiationResult(
                LinuxWaylandNegotiationState.FAILED,
                "GStreamer reported an error from the direct Wayland output.",
            )
        }

        val frameFlags = WAYLAND_OUTPUT_ATTACHED or WAYLAND_OUTPUT_CAPS_NEGOTIATED or WAYLAND_OUTPUT_FIRST_FRAME
        if (nativeState and frameFlags != frameFlags) {
            return LinuxWaylandNegotiationResult(LinuxWaylandNegotiationState.PENDING)
        }
        if (sourceDynamicRange == VideoDynamicRange.SDR) {
            if (nativeState and (WAYLAND_OUTPUT_PQ or WAYLAND_OUTPUT_HLG) != 0) {
                return LinuxWaylandNegotiationResult(
                    LinuxWaylandNegotiationState.FAILED,
                    "The Wayland sink still reports an HDR transfer after the source switched to SDR.",
                )
            }
            return LinuxWaylandNegotiationResult(LinuxWaylandNegotiationState.VERIFIED)
        }
        if (sourceDynamicRange == VideoDynamicRange.UNKNOWN) {
            return LinuxWaylandNegotiationResult(
                LinuxWaylandNegotiationState.FAILED,
                "The decoded transfer is unknown and cannot verify the Wayland color output.",
            )
        }
        if (nativeState and WAYLAND_OUTPUT_TEN_BIT == 0) {
            return LinuxWaylandNegotiationResult(
                LinuxWaylandNegotiationState.FAILED,
                "The Wayland sink negotiated a sub-10-bit video format.",
            )
        }
        if (requireDmaBuf && nativeState and WAYLAND_OUTPUT_DMABUF == 0) {
            return LinuxWaylandNegotiationResult(
                LinuxWaylandNegotiationState.FAILED,
                "The Vulkan projection input was not negotiated as a linear P010 DMA-BUF.",
            )
        }
        if (requireHdr10PlusApplication && nativeState and WAYLAND_OUTPUT_HDR10_PLUS_APPLIED == 0) {
            return LinuxWaylandNegotiationResult(LinuxWaylandNegotiationState.PENDING)
        }

        val expectedTransfer =
            if (sourceDynamicRange == VideoDynamicRange.HLG) {
                WAYLAND_OUTPUT_HLG
            } else {
                WAYLAND_OUTPUT_PQ
            }
        if (nativeState and expectedTransfer == 0) {
            return LinuxWaylandNegotiationResult(
                LinuxWaylandNegotiationState.FAILED,
                "The Wayland sink did not preserve the source PQ/HLG transfer.",
            )
        }
        return LinuxWaylandNegotiationResult(LinuxWaylandNegotiationState.VERIFIED)
    }
}
