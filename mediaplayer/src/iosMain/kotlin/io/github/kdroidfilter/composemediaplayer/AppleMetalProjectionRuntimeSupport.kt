@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Metal.MTLCreateSystemDefaultDevice

internal data class AppleMetalProjectionRuntimeSupport(
    val hasMetalDevice: Boolean,
) {
    val supportsControlledProjection: Boolean
        get() = hasMetalDevice
}

internal fun queryAppleMetalProjectionRuntimeSupport(): AppleMetalProjectionRuntimeSupport =
    AppleMetalProjectionRuntimeSupport(hasMetalDevice = MTLCreateSystemDefaultDevice() != null)

internal fun queryAppleProjectionRendererColorCapabilities(
    runtimeSupport: AppleMetalProjectionRuntimeSupport = queryAppleMetalProjectionRuntimeSupport(),
    unavailableHdrRanges: Set<VideoDynamicRange> = emptySet(),
    includesNativeSurface: Boolean = true,
    includesControlledRenderer: Boolean = true,
): RendererColorCapabilities {
    val controlledRanges =
        if (runtimeSupport.supportsControlledProjection && includesControlledRenderer) {
            APPLE_METAL_HDR_RANGES - unavailableHdrRanges
        } else {
            emptySet()
        }
    return RendererColorCapabilities(
        nativeSurfaceDynamicRanges =
            if (includesNativeSurface) {
                setOf(
                    VideoDynamicRange.HDR10,
                    VideoDynamicRange.HLG,
                    VideoDynamicRange.DOLBY_VISION,
                )
            } else {
                emptySet()
            },
        controlledHdrDynamicRanges = controlledRanges,
        supportsToneMappingToSdr = runtimeSupport.supportsControlledProjection && includesControlledRenderer,
        supportsNativeToneMappingToSdr = includesNativeSurface,
        supportsHdrProjection = controlledRanges.isNotEmpty(),
        supportsHdr10PlusApplication =
            runtimeSupport.supportsControlledProjection &&
                includesControlledRenderer &&
                VideoDynamicRange.HDR10_PLUS !in unavailableHdrRanges,
        supportsDolbyVisionMetadata = includesNativeSurface,
    )
}

private val APPLE_METAL_HDR_RANGES =
    setOf(VideoDynamicRange.HDR10, VideoDynamicRange.HDR10_PLUS, VideoDynamicRange.HLG)
