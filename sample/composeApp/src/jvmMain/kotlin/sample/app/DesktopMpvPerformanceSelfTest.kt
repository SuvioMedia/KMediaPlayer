package sample.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.nucleusframework.application.NucleusWindow
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.PlaybackDiagnostics
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberMpvVideoPlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Visible-surface smoke test for native macOS MPV playback, including recovery after a seek. */
@Composable
internal fun DesktopMpvPerformanceSelfTest(
    inputUri: String,
    durationSeconds: Long,
    resultFilePath: String,
    window: NucleusWindow,
    onComplete: () -> Unit,
) {
    val playerState = rememberMpvVideoPlayerState()
    LaunchedEffect(playerState, inputUri) {
        val result =
            runCatching {
                window.show()
                window.toFront()
                window.requestFocus()
                withTimeout(WINDOW_FOCUS_TIMEOUT_MS) {
                    while (!window.isFocused) delay(POLL_INTERVAL_MS)
                }
                playerState.loop = true
                playerState.openUri(inputUri, InitialPlayerState.PLAY)
                withTimeout(START_TIMEOUT_MS) {
                    while (true) {
                        check(playerState.error == null) { "MPV startup failed: ${playerState.error}" }
                        val presentation = playerState.diagnostics.nativePresentationCounters()
                        if (playerState.isPlaying && !playerState.isLoading && presentation != null) break
                        delay(POLL_INTERVAL_MS)
                    }
                }
                delay(STABILIZATION_MS)

                val sourceFps = playerState.metadata.frameRate?.toDouble()?.takeIf { it > 0.0 }
                val targetFps = sourceFps ?: DEFAULT_TARGET_FPS
                val pixelCount =
                    (playerState.metadata.width?.toLong() ?: 0L) *
                        (playerState.metadata.height?.toLong() ?: 0L)
                val isExtremeDecodeLoad =
                    pixelCount >= EXTREME_PIXEL_COUNT && targetFps >= EXTREME_SOURCE_FPS
                val minimumFreshFps =
                    targetFps *
                        if (isExtremeDecodeLoad) EXTREME_MINIMUM_FRESH_FPS_RATIO else MINIMUM_FRESH_FPS_RATIO
                val minimumPresentedFps =
                    targetFps *
                        if (isExtremeDecodeLoad) EXTREME_MINIMUM_PRESENTED_FPS_RATIO else MINIMUM_PRESENTED_FPS_RATIO

                val beforeSeek = measurePlaybackWindow(playerState, durationSeconds)

                val seekTarget = chooseSeekTarget(playerState)
                val countersBeforeSeek =
                    checkNotNull(playerState.diagnostics.nativePresentationCounters())
                playerState.seekTo(seekTarget)
                val seekStarted = TimeSource.Monotonic.markNow()
                withTimeout(SEEK_TIMEOUT_MS) {
                    while (true) {
                        check(playerState.error == null) { "MPV seek failed: ${playerState.error}" }
                        val positionErrorMs =
                            (playerState.preciseCurrentTime - seekTarget).inWholeMilliseconds.absoluteValue
                        val presentation = playerState.diagnostics.nativePresentationCounters()
                        val freshFrameArrived =
                            presentation != null && presentation.fresh > countersBeforeSeek.fresh
                        if (!playerState.isSeeking &&
                            !playerState.isLoading &&
                            positionErrorMs <= MAXIMUM_SEEK_POSITION_ERROR_MS &&
                            freshFrameArrived
                        ) {
                            break
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                }
                val seekRecoverySeconds = seekStarted.elapsedNow().inWholeNanoseconds / NANOS_PER_SECOND
                delay(POST_SEEK_STABILIZATION_MS)

                val afterSeek = measurePlaybackWindow(playerState, durationSeconds)
                println(
                    "KMP_MPV_PERFORMANCE_WINDOWS before={${beforeSeek.describe()}} " +
                        "after={${afterSeek.describe()}} seekRecoverySeconds=$seekRecoverySeconds",
                )
                beforeSeek.requireHealthy(
                    label = "before seek",
                    minimumFreshFps = minimumFreshFps,
                    minimumPresentedFps = minimumPresentedFps,
                )
                afterSeek.requireHealthy(
                    label = "after seek",
                    minimumFreshFps = minimumFreshFps,
                    minimumPresentedFps = minimumPresentedFps,
                )
                check(afterSeek.freshFps >= beforeSeek.freshFps * MINIMUM_POST_SEEK_FPS_RATIO) {
                    "MPV fresh-frame rate fell after seek: %.2f -> %.2f fps."
                        .format(Locale.US, beforeSeek.freshFps, afterSeek.freshFps)
                }
                check(afterSeek.presentedFps >= beforeSeek.presentedFps * MINIMUM_POST_SEEK_FPS_RATIO) {
                    "MPV presentation rate fell after seek: %.2f -> %.2f fps."
                        .format(Locale.US, beforeSeek.presentedFps, afterSeek.presentedFps)
                }

                String.format(
                    Locale.US,
                    "PASS seekTargetSeconds=%.3f seekRecoverySeconds=%.3f " +
                        "before={%s} after={%s} sourceFps=%s size=%sx%s " +
                        "maxAvSyncMs=%s renderer=%s notes=%s",
                    seekTarget.inWholeMilliseconds / MILLIS_PER_SECOND,
                    seekRecoverySeconds,
                    beforeSeek.describe(),
                    afterSeek.describe(),
                    sourceFps,
                    playerState.metadata.width,
                    playerState.metadata.height,
                    playerState.diagnostics.maximumAvSyncOffsetMs,
                    playerState.renderingInfo.videoRenderer,
                    playerState.diagnostics.notes,
                )
            }.getOrElse { failure ->
                "FAIL type=${failure::class.simpleName} detail=${failure.message} " +
                    "playing=${playerState.isPlaying} loading=${playerState.isLoading} " +
                    "seeking=${playerState.isSeeking} position=${playerState.preciseCurrentTime} " +
                    "rendered=${playerState.diagnostics.renderedVideoFrames} " +
                    "dropped=${playerState.diagnostics.droppedVideoFrames} " +
                    "renderer=${playerState.renderingInfo.videoRenderer} " +
                    "decoder=${playerState.renderingInfo.videoDecoder} " +
                    "notes=${playerState.diagnostics.notes}"
            }

        withContext(Dispatchers.IO) {
            File(resultFilePath).apply {
                parentFile?.mkdirs()
                writeText(result)
            }
        }
        println("KMP_MPV_PERFORMANCE_SELF_TEST=$result")
        onComplete()
    }

    VideoPlayerSurface(
        playerState = playerState,
        modifier = Modifier.fillMaxSize(),
    )
}

private data class NativePresentationCounters(
    val fresh: Long,
    val repeated: Long,
)

private data class PlaybackWindow(
    val elapsedSeconds: Double,
    val freshFrames: Long,
    val repeatedFrames: Long,
    val droppedFrames: Long,
    val freshFps: Double,
    val presentedFps: Double,
    val minimumFreshWindowFps: Double,
    val positionAdvanceSeconds: Double,
) {
    fun requireHealthy(
        label: String,
        minimumFreshFps: Double,
        minimumPresentedFps: Double,
    ) {
        check(freshFps >= minimumFreshFps) {
            "MPV $label decoded %.2f fresh fps; required %.2f fps."
                .format(Locale.US, freshFps, minimumFreshFps)
        }
        check(presentedFps >= minimumPresentedFps) {
            "MPV $label presented %.2f fps; required %.2f fps."
                .format(Locale.US, presentedFps, minimumPresentedFps)
        }
        check(minimumFreshWindowFps >= minimumFreshFps * MINIMUM_WINDOW_TO_AVERAGE_RATIO) {
            "MPV $label minimum fresh-frame window was %.2f fps; required %.2f fps."
                .format(Locale.US, minimumFreshWindowFps, minimumFreshFps * MINIMUM_WINDOW_TO_AVERAGE_RATIO)
        }
        check(positionAdvanceSeconds >= elapsedSeconds * MINIMUM_POSITION_ADVANCE_RATIO) {
            "MPV $label clock advanced only %.2fs during %.2fs of playback."
                .format(Locale.US, positionAdvanceSeconds, elapsedSeconds)
        }
    }

    fun describe(): String =
        String.format(
            Locale.US,
            "duration=%.3fs fresh=%d repeated=%d dropped=%d freshFps=%.3f " +
                "presentedFps=%.3f minFreshWindowFps=%.3f positionAdvance=%.3fs",
            elapsedSeconds,
            freshFrames,
            repeatedFrames,
            droppedFrames,
            freshFps,
            presentedFps,
            minimumFreshWindowFps,
            positionAdvanceSeconds,
        )
}

private suspend fun measurePlaybackWindow(
    playerState: VideoPlayerState,
    durationSeconds: Long,
): PlaybackWindow {
    val initialDiagnostics = playerState.diagnostics
    val baselinePresentation = checkNotNull(initialDiagnostics.nativePresentationCounters())
    val baselineDropped = checkNotNull(initialDiagnostics.droppedVideoFrames)
    val baselinePosition = playerState.preciseCurrentTime
    val started = TimeSource.Monotonic.markNow()
    var previousFresh = baselinePresentation.fresh
    var previousElapsedSeconds = 0.0
    var minimumFreshWindowFps = Double.POSITIVE_INFINITY

    while (started.elapsedNow().inWholeMilliseconds < durationSeconds * 1_000L) {
        delay(SAMPLE_WINDOW_MS)
        check(playerState.error == null) { "MPV playback failed: ${playerState.error}" }
        check(playerState.isPlaying || playerState.isLoading) { "MPV playback stopped." }
        val presentation = checkNotNull(playerState.diagnostics.nativePresentationCounters())
        val elapsedSeconds = started.elapsedNow().inWholeNanoseconds / NANOS_PER_SECOND
        val windowSeconds = elapsedSeconds - previousElapsedSeconds
        if (windowSeconds > 0.0) {
            minimumFreshWindowFps =
                minOf(minimumFreshWindowFps, (presentation.fresh - previousFresh) / windowSeconds)
        }
        previousFresh = presentation.fresh
        previousElapsedSeconds = elapsedSeconds
    }

    val elapsedSeconds = started.elapsedNow().inWholeNanoseconds / NANOS_PER_SECOND
    val diagnostics = playerState.diagnostics
    val presentation = checkNotNull(diagnostics.nativePresentationCounters())
    val fresh = presentation.fresh - baselinePresentation.fresh
    val repeated = presentation.repeated - baselinePresentation.repeated
    val dropped = checkNotNull(diagnostics.droppedVideoFrames) - baselineDropped
    val positionAdvance =
        (playerState.preciseCurrentTime - baselinePosition).inWholeMilliseconds / MILLIS_PER_SECOND
    return PlaybackWindow(
        elapsedSeconds = elapsedSeconds,
        freshFrames = fresh,
        repeatedFrames = repeated,
        droppedFrames = dropped,
        freshFps = fresh / elapsedSeconds,
        presentedFps = (fresh + repeated) / elapsedSeconds,
        minimumFreshWindowFps = minimumFreshWindowFps,
        positionAdvanceSeconds = positionAdvance,
    )
}

private fun PlaybackDiagnostics.nativePresentationCounters(): NativePresentationCounters? {
    val match = NATIVE_PRESENTATION_PATTERN.find(notes.orEmpty()) ?: return null
    return NativePresentationCounters(
        fresh = match.groupValues[1].toLong(),
        repeated = match.groupValues[2].toLong(),
    )
}

private fun chooseSeekTarget(playerState: VideoPlayerState): Duration {
    val latestUsefulTarget = (playerState.duration - SEEK_END_MARGIN).coerceAtLeast(Duration.ZERO)
    val forwardTarget = playerState.preciseCurrentTime + SEEK_FORWARD_DISTANCE
    return if (latestUsefulTarget > Duration.ZERO) {
        forwardTarget.coerceAtMost(latestUsefulTarget)
    } else {
        forwardTarget
    }
}

private val NATIVE_PRESENTATION_PATTERN = Regex("new=(\\d+) repeats=(\\d+)")
private val SEEK_FORWARD_DISTANCE = 120.seconds
private val SEEK_END_MARGIN = 5.seconds
private const val START_TIMEOUT_MS = 90_000L
private const val SEEK_TIMEOUT_MS = 90_000L
private const val WINDOW_FOCUS_TIMEOUT_MS = 10_000L
private const val STABILIZATION_MS = 2_000L
private const val POST_SEEK_STABILIZATION_MS = 750L
private const val POLL_INTERVAL_MS = 50L
private const val SAMPLE_WINDOW_MS = 1_000L
private const val MAXIMUM_SEEK_POSITION_ERROR_MS = 2_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0
private const val DEFAULT_TARGET_FPS = 60.0
private const val EXTREME_PIXEL_COUNT = 32_000_000L
private const val EXTREME_SOURCE_FPS = 50.0
private const val MINIMUM_FRESH_FPS_RATIO = 0.90
private const val MINIMUM_PRESENTED_FPS_RATIO = 0.94
private const val EXTREME_MINIMUM_FRESH_FPS_RATIO = 0.90
private const val EXTREME_MINIMUM_PRESENTED_FPS_RATIO = 0.94
private const val MINIMUM_WINDOW_TO_AVERAGE_RATIO = 0.75
private const val MINIMUM_POST_SEEK_FPS_RATIO = 0.90
private const val MINIMUM_POSITION_ADVANCE_RATIO = 0.70
