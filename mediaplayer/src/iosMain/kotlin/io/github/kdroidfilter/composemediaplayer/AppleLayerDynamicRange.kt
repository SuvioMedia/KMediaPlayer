@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import platform.Foundation.KMPConfigureLayerDynamicRange
import platform.Foundation.KMPIsLayerDynamicRangeConfigured
import platform.QuartzCore.CALayer

/**
 * Configures the current iOS dynamic-range API without referencing iOS 26-only symbols from
 * Kotlin. A positive [contentHeadroom] is used for untagged controlled Metal drawables; native
 * AVPlayer layers keep it at zero because AVFoundation supplies tagged IOSurfaces.
 */
internal fun CALayer.configureAppleDynamicRange(
    hdr: Boolean,
    contentHeadroom: Double = 0.0,
) {
    KMPConfigureLayerDynamicRange(
        layer = this,
        hdr = if (hdr) 1 else 0,
        contentHeadroom = contentHeadroom.coerceAtLeast(0.0),
    )
}

internal fun CALayer.isAppleDynamicRangeConfigured(hdr: Boolean): Boolean =
    KMPIsLayerDynamicRangeConfigured(this, if (hdr) 1 else 0) != 0
