@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.removeOutput
import platform.CoreMedia.CMTime
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange

/**
 * Samples decoded P010 frames without suppressing AVPlayerLayer rendering. This detects the source
 * signal only; it deliberately does not claim that the system layer preserved dynamic metadata.
 */
internal class AppleHdr10PlusMetadataProbe {
    private var item: AVPlayerItem? = null
    private var output: AVPlayerItemVideoOutput? = null

    fun attach(newItem: AVPlayerItem) {
        if (item === newItem && output != null) return
        detach()
        runCatching {
            val attributes: Map<Any?, Any?> =
                mapOf(
                    "PixelFormatType" to kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange.toLong(),
                    "MetalCompatibility" to true,
                    "IOSurfaceProperties" to emptyMap<Any?, Any?>(),
                )
            val newOutput = AVPlayerItemVideoOutput(pixelBufferAttributes = attributes)
            newOutput.suppressesPlayerRendering = false
            newItem.addOutput(newOutput)
            newOutput.requestNotificationOfMediaDataChangeWithAdvanceInterval(PROBE_ADVANCE_SECONDS)
            item = newItem
            output = newOutput
        }
    }

    fun metadataAt(
        itemTime: CValue<CMTime>,
        presentationTimeUs: Long,
    ): Hdr10PlusInfo? {
        val activeOutput = output ?: return null
        if (!activeOutput.hasNewPixelBufferForItemTime(itemTime)) return null
        val pixelBuffer =
            memScoped {
                val displayTime = alloc<CMTime>()
                activeOutput.copyPixelBufferForItemTime(itemTime, displayTime.ptr)
            } ?: return null
        return try {
            pixelBuffer
                .hdr10PlusFrameMetadata(
                    presentationTimeUs = presentationTimeUs.coerceAtLeast(0L),
                    displayPeakNits = PROBE_REFERENCE_PEAK_NITS,
                )?.info
        } finally {
            CVPixelBufferRelease(pixelBuffer)
        }
    }

    fun detach() {
        val oldItem = item
        val oldOutput = output
        if (oldItem != null && oldOutput != null) oldItem.removeOutput(oldOutput)
        item = null
        output = null
    }
}

private const val PROBE_ADVANCE_SECONDS = 0.03
private const val PROBE_REFERENCE_PEAK_NITS = 1_000.0
