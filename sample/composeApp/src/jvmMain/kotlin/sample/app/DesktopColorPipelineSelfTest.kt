package sample.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.ColorPipelineRenderer
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.RenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberRenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.allowComposeMediaPlayerLogging
import io.github.kdroidfilter.composemediaplayer.util.composeMediaPlayerLogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import kotlin.time.TimeSource

private const val PERFORMANCE_SAMPLE_WINDOW_MS = 5_000L
private const val MINIMUM_PERFORMANCE_SAMPLE_WINDOW_SECONDS = 4.0
private const val MINIMUM_AVERAGE_FPS = 59.0
private const val MINIMUM_WINDOW_FPS = 55.0
private const val MAXIMUM_AV_SYNC_OFFSET_MS = 45.0f
private const val MAXIMUM_RESIDENT_SET_GROWTH_KIB = 256L * 1_024L
private const val SELF_TEST_START_TIMEOUT_MS = 90_000L
private const val STABLE_OUTPUT_WINDOW_MS = 1_500L

@Composable
internal fun DesktopColorPipelineSelfTest(
    inputUri: String,
    expectedSource: VideoDynamicRange,
    expectedOutput: VideoDynamicRange,
    requireAudioSync: Boolean,
    durationSeconds: Long,
    resultFilePath: String,
    playbackOptions: VideoPlaybackOptions,
    onComplete: () -> Unit,
) {
    allowComposeMediaPlayerLogging = true
    composeMediaPlayerLogSink = { line -> println(line) }
    val playerState = rememberRenderableVideoPlayerState(playbackOptions = playbackOptions)
    LaunchedEffect(playerState, inputUri) {
        var lastStatus: VideoColorPipelineStatus? = null
        val outcome =
            runCatching {
                playerState.loop = true
                playerState.openUri(inputUri, InitialPlayerState.PLAY)
                withTimeout(SELF_TEST_START_TIMEOUT_MS) {
                    while (true) {
                        val status = playerState.colorPipelineStatus.value
                        lastStatus = status
                        if (status.matchesExpectedDesktopColorOutput(expectedSource, expectedOutput)) break
                        delay(100L)
                    }
                }
                delay(STABLE_OUTPUT_WINDOW_MS)
                val stableStatus = playerState.colorPipelineStatus.value
                lastStatus = stableStatus
                check(stableStatus.matchesExpectedDesktopColorOutput(expectedSource, expectedOutput)) {
                    "The expected color output did not remain stable."
                }
                runDesktopSustainedPlaybackCheck(
                    playerState = playerState,
                    expectedSource = expectedSource,
                    expectedOutput = expectedOutput,
                    requireAudioSync = requireAudioSync,
                    durationSeconds = durationSeconds,
                ).also { result -> lastStatus = result.status }
            }
        val summary =
            outcome.fold(
                onSuccess = DesktopSustainedPlaybackResult::summary,
                onFailure = { failure ->
                    val detail =
                        listOfNotNull(failure.message, lastStatus?.compactDiagnostic())
                            .joinToString("; ")
                            .replace(inputUri, "<input>")
                            .take(500)
                    "FAIL type=${failure::class.simpleName} detail=$detail"
                },
            )
        withContext(Dispatchers.IO) {
            File(resultFilePath).apply {
                parentFile?.mkdirs()
                writeText(summary)
            }
        }
        println("KMP_COLOR_SELF_TEST=$summary")
        onComplete()
    }
    VideoPlayerSurface(
        playerState = playerState,
        modifier = Modifier.fillMaxSize(),
    )
}

private suspend fun runDesktopSustainedPlaybackCheck(
    playerState: RenderableVideoPlayerState,
    expectedSource: VideoDynamicRange,
    expectedOutput: VideoDynamicRange,
    requireAudioSync: Boolean,
    durationSeconds: Long,
): DesktopSustainedPlaybackResult {
    val started = TimeSource.Monotonic.markNow()
    val baselineFrames =
        checkNotNull(playerState.diagnostics.renderedVideoFrames) { "Rendered frame telemetry unavailable." }
    val initialResidentSetKib = checkNotNull(currentResidentSetKib()) { "Resident-set telemetry unavailable." }
    var previousFrames = baselineFrames
    var previousElapsedSeconds = 0.0
    var minimumFps = Float.POSITIVE_INFINITY
    var peakResidentSetKib = initialResidentSetKib

    while (started.elapsedNow().inWholeMilliseconds < durationSeconds * 1_000L) {
        val remainingMs =
            (durationSeconds * 1_000L - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1L)
        delay(minOf(PERFORMANCE_SAMPLE_WINDOW_MS, remainingMs))
        val elapsedSeconds = started.elapsedNow().inWholeNanoseconds / 1_000_000_000.0
        val diagnostics = playerState.diagnostics
        val renderedFrames =
            checkNotNull(diagnostics.renderedVideoFrames) { "Rendered frame telemetry disappeared." }
        val windowSeconds = elapsedSeconds - previousElapsedSeconds
        if (windowSeconds >= MINIMUM_PERFORMANCE_SAMPLE_WINDOW_SECONDS) {
            minimumFps = minOf(minimumFps, ((renderedFrames - previousFrames) / windowSeconds).toFloat())
        }
        previousFrames = renderedFrames
        previousElapsedSeconds = elapsedSeconds
        currentResidentSetKib()?.let { peakResidentSetKib = maxOf(peakResidentSetKib, it) }
        check(playerState.colorPipelineStatus.value.matchesExpectedDesktopColorOutput(expectedSource, expectedOutput)) {
            "Color output changed during the sustained run."
        }
        check(playerState.error == null) { "Playback error during sustained run: ${playerState.error}" }
        if (!playerState.isPlaying && !playerState.isLoading) {
            delay(1_000L)
            check(playerState.isPlaying || playerState.isLoading) { "Playback stopped during the sustained run." }
        }
    }

    val actualDurationSeconds = started.elapsedNow().inWholeNanoseconds / 1_000_000_000.0
    val diagnostics = playerState.diagnostics
    val renderedFrames =
        checkNotNull(diagnostics.renderedVideoFrames) { "Rendered frame telemetry disappeared." } - baselineFrames
    val droppedFrames = checkNotNull(diagnostics.droppedVideoFrames) { "Dropped-frame telemetry unavailable." }
    val maximumAvSyncOffsetMs = diagnostics.maximumAvSyncOffsetMs
    val averageFps = renderedFrames / actualDurationSeconds
    val sampledMinimumFps = minimumFps.takeIf(Float::isFinite) ?: 0f
    val residentSetGrowthKib = (peakResidentSetKib - initialResidentSetKib).coerceAtLeast(0L)

    check(averageFps >= MINIMUM_AVERAGE_FPS) { "Average frame rate $averageFps is below $MINIMUM_AVERAGE_FPS." }
    check(sampledMinimumFps >= MINIMUM_WINDOW_FPS) {
        "Minimum sampled frame rate $sampledMinimumFps is below $MINIMUM_WINDOW_FPS."
    }
    if (requireAudioSync) {
        checkNotNull(maximumAvSyncOffsetMs) {
            "A/V sync telemetry unavailable because the source has no active audio track."
        }
    }
    maximumAvSyncOffsetMs?.let { offsetMs ->
        check(offsetMs <= MAXIMUM_AV_SYNC_OFFSET_MS) {
            "Maximum A/V sync offset $offsetMs ms exceeds $MAXIMUM_AV_SYNC_OFFSET_MS ms."
        }
    }
    check(residentSetGrowthKib <= MAXIMUM_RESIDENT_SET_GROWTH_KIB) {
        "Resident set grew by ${residentSetGrowthKib / 1_024.0} MiB."
    }
    return DesktopSustainedPlaybackResult(
        status = playerState.colorPipelineStatus.value,
        durationSeconds = actualDurationSeconds,
        renderedFrames = renderedFrames,
        droppedFrames = droppedFrames,
        averageFps = averageFps,
        minimumFps = sampledMinimumFps,
        maximumAvSyncOffsetMs = maximumAvSyncOffsetMs,
        initialResidentSetMib = initialResidentSetKib / 1_024.0,
        peakResidentSetMib = peakResidentSetKib / 1_024.0,
        residentSetGrowthMib = residentSetGrowthKib / 1_024.0,
    )
}

private data class DesktopSustainedPlaybackResult(
    val status: VideoColorPipelineStatus,
    val durationSeconds: Double,
    val renderedFrames: Long,
    val droppedFrames: Long,
    val averageFps: Double,
    val minimumFps: Float,
    val maximumAvSyncOffsetMs: Float?,
    val initialResidentSetMib: Double,
    val peakResidentSetMib: Double,
    val residentSetGrowthMib: Double,
) {
    fun summary(): String =
        String.format(
            Locale.US,
            "PASS source=%s output=%s renderer=%s verification=%s decoder=%s " +
                "durationSeconds=%.3f renderedFrames=%d droppedFrames=%d averageFps=%.3f " +
                "minimumFps=%.3f maxAvSyncMs=%s initialRssMiB=%.3f peakRssMiB=%.3f " +
                "residentSetGrowthMiB=%.3f boundedMemory=true",
            status.source.dynamicRange,
            status.outputDynamicRange,
            status.renderer,
            status.verification,
            status.decoderName,
            durationSeconds,
            renderedFrames,
            droppedFrames,
            averageFps,
            minimumFps,
            maximumAvSyncOffsetMs?.let { String.format(Locale.US, "%.3f", it) } ?: "unavailable",
            initialResidentSetMib,
            peakResidentSetMib,
            residentSetGrowthMib,
        )
}

private fun VideoColorPipelineStatus.matchesExpectedDesktopColorOutput(
    expectedSource: VideoDynamicRange,
    expectedOutput: VideoDynamicRange,
): Boolean {
    if (
        source.dynamicRange != expectedSource ||
        outputDynamicRange != expectedOutput ||
        !requestHonored ||
        decoderName == null
    ) {
        return false
    }
    return if (expectedOutput == VideoDynamicRange.SDR) {
        verification == ColorPipelineVerification.RENDERER_CONFIGURED
    } else {
        when (renderer) {
            ColorPipelineRenderer.SYSTEM_NATIVE ->
                metadataHandling == DynamicMetadataHandling.PASSTHROUGH &&
                    verification == ColorPipelineVerification.SYSTEM_REPORTED

            ColorPipelineRenderer.CONTROLLED_HDR ->
                verification == ColorPipelineVerification.RENDERER_CONFIGURED &&
                    if (source.dynamicRange == VideoDynamicRange.HDR10_PLUS) {
                        metadataHandling == DynamicMetadataHandling.APPLIED_BY_RENDERER
                    } else {
                        metadataHandling == DynamicMetadataHandling.PASSTHROUGH
                    }

            else -> false
        }
    }
}

private fun VideoColorPipelineStatus.compactDiagnostic(): String =
    "source=${source.dynamicRange} output=$outputDynamicRange planned=$plannedOutputDynamicRange " +
        "renderer=$renderer surface=$surface verification=$verification decoder=$decoderName " +
        "requestHonored=$requestHonored fallback=$fallbackReason detail=$detail"

private fun currentResidentSetKib(): Long? =
    runCatching {
        val pid = ProcessHandle.current().pid()
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        val process =
            if (windows) {
                ProcessBuilder(
                    "powershell.exe",
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "(Get-Process -Id $pid).WorkingSet64",
                ).start()
            } else {
                ProcessBuilder(
                    "/bin/ps",
                    "-o",
                    "rss=",
                    "-p",
                    pid.toString(),
                ).start()
            }
        val rawValue = process.inputStream.bufferedReader().use { it.readText() }.trim().toLongOrNull()
        process.waitFor()
        rawValue
            ?.let { value -> if (windows) value / 1_024L else value }
            ?.takeIf { process.exitValue() == 0 }
    }.getOrNull()
