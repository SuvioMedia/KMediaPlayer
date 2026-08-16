@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun generateIosMediaThumbnails(
    sourceUri: String,
    requestHeaders: Map<String, String>,
    positions: List<Duration>,
    maximumWidth: Int,
    emit: suspend (index: Int, thumbnail: IosMediaThumbnail?) -> Unit,
) {
    require(maximumWidth > 0) { "maximumWidth must be positive." }
    if (positions.isEmpty()) return
    val sourceUrl = NSURL.URLWithString(sourceUri)
    if (sourceUrl == null) {
        positions.indices.forEach { index -> emit(index, null) }
        return
    }

    val asset = AVURLAsset.URLAssetWithURL(sourceUrl, requestHeaders.iosThumbnailAssetOptions())
    val imageGenerator =
        AVAssetImageGenerator(asset).apply {
            appliesPreferredTrackTransform = true
            maximumSize =
                CGSizeMake(
                    width = maximumWidth.toDouble(),
                    height = (maximumWidth * IOS_THUMBNAIL_MAXIMUM_HEIGHT_MULTIPLIER).toDouble(),
                )
        }
    try {
        positions.forEachIndexed { index, position ->
            kotlin.coroutines.coroutineContext.ensureActive()
            val thumbnail =
                try {
                    imageGenerator.thumbnailAt(position)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            emit(index, thumbnail)
        }
    } finally {
        imageGenerator.cancelAllCGImageGeneration()
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun AVAssetImageGenerator.thumbnailAt(position: Duration): IosMediaThumbnail? =
    suspendCancellableCoroutine { continuation ->
        val requestedTime =
            CMTimeMakeWithSeconds(
                seconds = position.inWholeMilliseconds.coerceAtLeast(0L) / MILLISECONDS_PER_SECOND,
                preferredTimescale = IOS_THUMBNAIL_TIMESCALE,
            )
        generateCGImageAsynchronouslyForTime(requestedTime) { image, actualTime, error ->
            if (!continuation.isActive) return@generateCGImageAsynchronouslyForTime
            if (image == null || error != null) {
                continuation.resume(null)
                return@generateCGImageAsynchronouslyForTime
            }
            try {
                val uiImage = UIImage.imageWithCGImage(image)
                val jpeg = UIImageJPEGRepresentation(uiImage, IOS_THUMBNAIL_JPEG_QUALITY)
                if (jpeg == null) {
                    continuation.resume(null)
                    return@generateCGImageAsynchronouslyForTime
                }
                val timestampMs =
                    (CMTimeGetSeconds(actualTime) * MILLISECONDS_PER_SECOND)
                        .takeIf(Double::isFinite)
                        ?.roundToLong()
                        ?: position.inWholeMilliseconds
                continuation.resume(
                    IosMediaThumbnail(
                        bytes = jpeg.toByteArray(),
                        mimeType = IOS_THUMBNAIL_MIME_TYPE,
                        timestamp = timestampMs.milliseconds,
                        width = CGImageGetWidth(image).toInt(),
                        height = CGImageGetHeight(image).toInt(),
                    ),
                )
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
        continuation.invokeOnCancellation { cancelAllCGImageGeneration() }
    }

private fun NSData.toByteArray(): ByteArray {
    require(length <= Int.MAX_VALUE.toULong()) { "The encoded iOS thumbnail is too large." }
    if (length == 0UL) return ByteArray(0)
    return ByteArray(length.toInt()).also { output ->
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

private fun Map<String, String>.iosThumbnailAssetOptions(): Map<Any?, *>? {
    val headers = sanitizedRequestHeaders()
    if (headers.isEmpty()) return null
    return mapOf<Any?, Any>("AVURLAssetHTTPHeaderFieldsKey" to headers)
}

private const val IOS_THUMBNAIL_MAXIMUM_HEIGHT_MULTIPLIER = 2
private const val IOS_THUMBNAIL_TIMESCALE = 1_000
private const val MILLISECONDS_PER_SECOND = 1_000.0
private const val IOS_THUMBNAIL_JPEG_QUALITY = 0.82
private const val IOS_THUMBNAIL_MIME_TYPE = "image/jpeg"
