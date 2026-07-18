@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package io.github.kdroidfilter.composemediaplayer

import platform.AVFoundation.AVPlayerHDRModeDolbyVision
import platform.AVFoundation.AVPlayerHDRModeHDR10
import platform.AVFoundation.AVPlayerHDRModeHLG
import platform.Foundation.KMPAVPlayerAvailableHDRModes
import platform.Foundation.KMPAVPlayerEligibleForHDRPlayback
import platform.UIKit.UIScreen

internal fun UIScreen.toAppleDisplayColorCapabilities(): DisplayColorCapabilities {
    val hasEdrHeadroom = potentialEDRHeadroom > 1.0
    val availableModes = KMPAVPlayerAvailableHDRModes()
    val eligibleForHdrPlayback = KMPAVPlayerEligibleForHDRPlayback() != 0
    return appleDisplayColorCapabilities(
        hasEdrHeadroom = hasEdrHeadroom,
        eligibleForHdrPlayback = eligibleForHdrPlayback,
        supportsHdr10 = availableModes.has(AVPlayerHDRModeHDR10),
        supportsHlg = availableModes.has(AVPlayerHDRModeHLG),
        supportsDolbyVision = availableModes.has(AVPlayerHDRModeDolbyVision),
    )
}

internal fun appleDisplayColorCapabilities(
    hasEdrHeadroom: Boolean,
    eligibleForHdrPlayback: Boolean,
    supportsHdr10: Boolean,
    supportsHlg: Boolean,
    supportsDolbyVision: Boolean,
): DisplayColorCapabilities =
    DisplayColorCapabilities(
        isKnown = true,
        supportedDynamicRanges =
            buildSet {
                add(VideoDynamicRange.SDR)
                if (hasEdrHeadroom && eligibleForHdrPlayback) {
                    if (supportsHdr10) add(VideoDynamicRange.HDR10)
                    if (supportsHlg) add(VideoDynamicRange.HLG)
                    if (supportsDolbyVision) add(VideoDynamicRange.DOLBY_VISION)
                }
            },
        // UIScreen exposes EDR ratios, not calibrated absolute luminance.
        minLuminanceNits = null,
        maxLuminanceNits = null,
        referenceWhiteNits = null,
    )

private fun ULong.has(mode: Long): Boolean = this and mode.toULong() != 0uL
