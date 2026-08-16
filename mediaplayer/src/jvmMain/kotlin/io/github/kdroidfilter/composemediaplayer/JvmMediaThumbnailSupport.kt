package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val JVM_MEDIA_THUMBNAIL_JPEG_QUALITY = 72
private const val JVM_MEDIA_THUMBNAIL_MAXIMUM_HEIGHT_MULTIPLIER = 2
private val JVM_MEDIA_THUMBNAIL_OPEN_TIMEOUT = 30.seconds
private val JVM_MEDIA_THUMBNAIL_SEEK_TIMEOUT = 10.seconds
private val JVM_MEDIA_THUMBNAIL_POSITION_TOLERANCE = 1.seconds

internal fun jvmMediaThumbnailHeight(
    maximumWidth: Int,
    aspectRatio: Float,
): Int {
    val normalizedAspectRatio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    return (maximumWidth / normalizedAspectRatio)
        .roundToInt()
        .coerceIn(1, maximumWidth * JVM_MEDIA_THUMBNAIL_MAXIMUM_HEIGHT_MULTIPLIER)
}

private fun ImageBitmap.encodeJvmMediaThumbnail(
    timestamp: Duration,
    maximumWidth: Int,
): JvmMediaThumbnail? =
    runCatching {
        val targetWidth = width.coerceAtMost(maximumWidth)
        val targetHeight =
            (height.toDouble() * targetWidth / width)
                .roundToInt()
                .coerceAtLeast(1)
        Image.makeFromBitmap(asSkiaBitmap()).use { source ->
            val encodedBytes =
                if (targetWidth == width && targetHeight == height) {
                    source.encodeToData(EncodedImageFormat.JPEG, JVM_MEDIA_THUMBNAIL_JPEG_QUALITY)?.use { it.bytes }
                } else {
                    Surface.makeRasterN32Premul(targetWidth, targetHeight).use { surface ->
                        surface.canvas.drawImageRect(
                            source,
                            Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                        )
                        surface.makeImageSnapshot().use { scaled ->
                            scaled
                                .encodeToData(
                                    EncodedImageFormat.JPEG,
                                    JVM_MEDIA_THUMBNAIL_JPEG_QUALITY,
                                )?.use { it.bytes }
                        }
                    }
                }
            encodedBytes?.let { bytes ->
                JvmMediaThumbnail(
                    bytes = bytes,
                    mimeType = "image/jpeg",
                    timestamp = timestamp,
                    width = targetWidth,
                    height = targetHeight,
                )
            }
        }
    }.getOrNull()

internal suspend fun <T : VideoPlayerState> generateIsolatedJvmMediaThumbnails(
    positions: List<Duration>,
    maximumWidth: Int,
    sourceUri: String?,
    requestHeaders: Map<String, String>,
    initialAspectRatio: Float,
    createPreview: () -> T,
    resizePreview: (T, width: Int, height: Int) -> Unit,
    currentFrame: (T) -> ImageBitmap?,
    emit: suspend (index: Int, thumbnail: JvmMediaThumbnail?) -> Unit,
) {
    require(maximumWidth > 0) { "maximumWidth must be positive." }
    if (positions.isEmpty()) return
    if (sourceUri == null) {
        positions.indices.forEach { index -> emit(index, null) }
        return
    }

    val preview = createPreview()
    try {
        resizePreview(preview, maximumWidth, jvmMediaThumbnailHeight(maximumWidth, initialAspectRatio))
        preview.openUri(sourceUri, InitialPlayerState.PAUSE, requestHeaders)
        val opened =
            withTimeoutOrNull(JVM_MEDIA_THUMBNAIL_OPEN_TIMEOUT) {
                while (!preview.hasMedia && preview.error == null) delay(25.milliseconds)
                preview.hasMedia
            } == true
        if (!opened) {
            positions.indices.forEach { index -> emit(index, null) }
            return
        }

        preview.pause()
        resizePreview(preview, maximumWidth, jvmMediaThumbnailHeight(maximumWidth, preview.aspectRatio))
        positions.forEachIndexed { index, requestedPosition ->
            currentCoroutineContext().ensureActive()
            val maximumPosition = (preview.duration - 1.milliseconds).coerceAtLeast(Duration.ZERO)
            val position = requestedPosition.coerceIn(Duration.ZERO, maximumPosition)
            val previousFrame = currentFrame(preview)
            preview.seekTo(position)
            val frame =
                withTimeoutOrNull<ImageBitmap>(JVM_MEDIA_THUMBNAIL_SEEK_TIMEOUT) {
                    var settledFrame: ImageBitmap? = null
                    while (settledFrame == null) {
                        currentCoroutineContext().ensureActive()
                        val candidate = currentFrame(preview)
                        val positionSettled =
                            abs((preview.currentTime - position).inWholeMilliseconds) <=
                                JVM_MEDIA_THUMBNAIL_POSITION_TOLERANCE.inWholeMilliseconds
                        if (candidate != null && candidate !== previousFrame && positionSettled && !preview.isSeeking) {
                            settledFrame = candidate
                        } else {
                            delay(25.milliseconds)
                        }
                    }
                    settledFrame
                }
            val thumbnail =
                frame?.let { image ->
                    withContext(Dispatchers.Default) {
                        image.encodeJvmMediaThumbnail(position, maximumWidth)
                    }
                }
            emit(index, thumbnail)
        }
    } finally {
        preview.dispose()
    }
}
