@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer.ass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.UIKitView
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import kotlinx.cinterop.cValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.stringWithContentsOfURL
import platform.UIKit.UIColor
import platform.UIKit.UIImageView
import platform.UIKit.UIView
import platform.UIKit.UIViewContentMode
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun AppleAssSubtitleOverlay(
    track: SubtitleTrack,
    positionMs: Long,
    isPlaying: Boolean,
    modifier: Modifier,
    onRendererActiveChanged: (Boolean) -> Unit,
) {
    var renderSize by remember(track.id) { mutableStateOf(IntSize.Zero) }
    var renderedFrame by remember(track.id) { mutableStateOf<AppleAssRenderedFrame?>(null) }
    val latestPositionMs by rememberUpdatedState(positionMs)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestActiveCallback by rememberUpdatedState(onRendererActiveChanged)

    DisposableEffect(track.id) {
        latestActiveCallback(false)
        onDispose { latestActiveCallback(false) }
    }

    LaunchedEffect(track.id, track.src, renderSize) {
        renderedFrame = null
        latestActiveCallback(false)
        if (renderSize.width <= 0 || renderSize.height <= 0) return@LaunchedEffect

        val script =
            runCatching { loadAppleAssScript(track.src) }
                .getOrNull()
                ?: return@LaunchedEffect
        if (!script.containsAssEvents()) return@LaunchedEffect

        val session =
            runCatching { AppleAssNativeSession.create(script) }
                .getOrNull()
                ?: return@LaunchedEffect
        latestActiveCallback(true)
        try {
            var observedPositionMs = latestPositionMs
            var anchorPositionMs = observedPositionMs
            var anchorFrameNanos = 0L
            var lastRenderedTimeMs = Long.MIN_VALUE
            var wasPlaying = latestIsPlaying
            while (true) {
                currentCoroutineContext().ensureActive()
                val frameNanos = withFrameNanos { it }
                val currentPositionMs = latestPositionMs
                val playing = latestIsPlaying
                if (
                    anchorFrameNanos == 0L ||
                    playing != wasPlaying ||
                    abs(currentPositionMs - observedPositionMs) >= POSITION_RESYNC_THRESHOLD_MS
                ) {
                    observedPositionMs = currentPositionMs
                    anchorPositionMs = currentPositionMs
                    anchorFrameNanos = frameNanos
                }
                val renderTimeMs =
                    if (playing) {
                        anchorPositionMs + (frameNanos - anchorFrameNanos) / NANOS_PER_MILLISECOND
                    } else {
                        currentPositionMs
                    }.coerceAtLeast(0L)

                if (playing || renderTimeMs != lastRenderedTimeMs) {
                    renderedFrame =
                        withContext(Dispatchers.Default) {
                            session.render(
                                width = renderSize.width,
                                height = renderSize.height,
                                timeMs = renderTimeMs,
                            )
                        }
                    lastRenderedTimeMs = renderTimeMs
                } else {
                    delay(PAUSED_POLL_INTERVAL)
                }
                wasPlaying = playing
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            renderedFrame = null
        } finally {
            latestActiveCallback(false)
            session.close()
        }
    }

    UIKitView(
        factory = {
            UIView(frame = cValue<CGRect>()).apply {
                backgroundColor = UIColor.clearColor
                opaque = false
                clipsToBounds = true
                userInteractionEnabled = false
                addSubview(
                    UIImageView(frame = cValue<CGRect>()).apply {
                        backgroundColor = UIColor.clearColor
                        opaque = false
                        contentMode = UIViewContentMode.UIViewContentModeScaleToFill
                        userInteractionEnabled = false
                    },
                )
            }
        },
        modifier = modifier.onSizeChanged { size -> renderSize = size },
        update = { container ->
            val imageView = container.subviews.firstOrNull() as? UIImageView
            val frame = renderedFrame
            if (imageView == null || frame == null) {
                imageView?.image = null
                return@UIKitView
            }
            val (boundsWidth, boundsHeight) =
                container.bounds.useContents {
                    size.width to size.height
                }
            val scaleX = boundsWidth / frame.canvasWidth.toDouble()
            val scaleY = boundsHeight / frame.canvasHeight.toDouble()
            imageView.setFrame(
                CGRectMake(
                    x = frame.x * scaleX,
                    y = frame.y * scaleY,
                    width = frame.width * scaleX,
                    height = frame.height * scaleY,
                ),
            )
            imageView.image = frame.image
        },
        onRelease = { container ->
            (container.subviews.firstOrNull() as? UIImageView)?.image = null
        },
    )
}

private suspend fun loadAppleAssScript(source: String): ByteArray =
    withContext(Dispatchers.Default) {
        val content =
            when {
                source.startsWith("http://", ignoreCase = true) ||
                    source.startsWith("https://", ignoreCase = true) -> {
                    NSString.stringWithContentsOfURL(
                        NSURL(string = source),
                        encoding = NSUTF8StringEncoding,
                        error = null,
                    )
                }

                source.startsWith("file://", ignoreCase = true) -> {
                    NSString.stringWithContentsOfURL(
                        NSURL(string = source),
                        encoding = NSUTF8StringEncoding,
                        error = null,
                    )
                }

                else -> {
                    NSString.stringWithContentsOfFile(
                        source,
                        encoding = NSUTF8StringEncoding,
                        error = null,
                    )
                }
            } ?: error("The external ASS/SSA source could not be loaded.")
        content.encodeToByteArray().also { bytes ->
            require(bytes.size <= MAX_EXTERNAL_ASS_BYTES) {
                "The external ASS/SSA source exceeds 64 MiB."
            }
        }
    }

private fun ByteArray.containsAssEvents(): Boolean = decodeToString().contains("[Events]", ignoreCase = true)

private const val MAX_EXTERNAL_ASS_BYTES = 64 * 1024 * 1024
private const val POSITION_RESYNC_THRESHOLD_MS = 100L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private val PAUSED_POLL_INTERVAL = 50.milliseconds
