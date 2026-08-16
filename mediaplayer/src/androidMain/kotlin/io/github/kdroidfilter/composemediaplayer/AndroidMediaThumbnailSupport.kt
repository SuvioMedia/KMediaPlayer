@file:Suppress("UnstableApiUsage")

package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal suspend fun generateAndroidMediaThumbnails(
    context: Context,
    mediaItem: MediaItem,
    mediaSourceFactory: MediaSource.Factory,
    positions: List<Duration>,
    maximumWidth: Int,
    emit: suspend (index: Int, thumbnail: AndroidMediaThumbnail?) -> Unit,
) {
    require(maximumWidth > 0) { "maximumWidth must be positive." }
    if (positions.isEmpty()) return

    val extractor =
        withContext(Dispatchers.Main.immediate) {
            FrameExtractor
                .Builder(context, mediaItem)
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                .setEffects(listOf(Presentation.createForShortSide(maximumWidth)))
                .build()
        }
    try {
        positions.forEachIndexed { index, position ->
            kotlin.coroutines.coroutineContext.ensureActive()
            val future =
                withContext(Dispatchers.Main.immediate) {
                    extractor.getFrame(position.inWholeMilliseconds.coerceAtLeast(0L))
                }
            val thumbnail =
                try {
                    val frame =
                        withContext(Dispatchers.Main.immediate) {
                            future.awaitFrame().copyForEncoding()
                        }
                    withContext(Dispatchers.Default) { frame?.toAndroidMediaThumbnail(maximumWidth) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            emit(index, thumbnail)
        }
    } finally {
        withContext(NonCancellable + Dispatchers.Main.immediate) { extractor.close() }
    }
}

private suspend fun <T> ListenableFuture<T>.awaitFrame(): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: ExecutionException) {
                    continuation.resumeWithException(error.cause ?: error)
                } catch (error: java.util.concurrent.CancellationException) {
                    continuation.resumeWithException(error)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    continuation.resumeWithException(error)
                }
            },
            Runnable::run,
        )
        continuation.invokeOnCancellation { cancel(false) }
    }

private data class AndroidFrameForEncoding(
    val bitmap: Bitmap,
    val presentationTimeMs: Long,
)

private fun FrameExtractor.Frame.copyForEncoding(): AndroidFrameForEncoding? {
    if (bitmap.isRecycled) return null
    return AndroidFrameForEncoding(
        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null,
        presentationTimeMs = presentationTimeMs,
    )
}

private fun AndroidFrameForEncoding.toAndroidMediaThumbnail(maximumWidth: Int): AndroidMediaThumbnail? {
    val source = bitmap
    val maximumHeight = maximumWidth * ANDROID_THUMBNAIL_MAXIMUM_HEIGHT_MULTIPLIER
    val scale =
        minOf(
            1.0,
            maximumWidth.toDouble() / source.width.coerceAtLeast(1),
            maximumHeight.toDouble() / source.height.coerceAtLeast(1),
        )
    val targetWidth = (source.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (source.height * scale).roundToInt().coerceAtLeast(1)
    val output =
        if (targetWidth == source.width && targetHeight == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }

    return try {
        val bytes =
            ByteArrayOutputStream().use { stream ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, ANDROID_THUMBNAIL_JPEG_QUALITY, stream)) {
                    return null
                }
                stream.toByteArray()
            }
        AndroidMediaThumbnail(
            bytes = bytes,
            mimeType = ANDROID_THUMBNAIL_MIME_TYPE,
            timestamp = presentationTimeMs.milliseconds,
            width = output.width,
            height = output.height,
        )
    } finally {
        if (output !== source) output.recycle()
        source.recycle()
    }
}

private const val ANDROID_THUMBNAIL_MAXIMUM_HEIGHT_MULTIPLIER = 2
private const val ANDROID_THUMBNAIL_JPEG_QUALITY = 82
private const val ANDROID_THUMBNAIL_MIME_TYPE = "image/jpeg"
