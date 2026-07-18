package sample.app

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.ColorPipelineRenderer
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberRenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.allowComposeMediaPlayerLogging
import io.github.kdroidfilter.composemediaplayer.util.composeMediaPlayerLogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

private const val PERFORMANCE_SAMPLE_WINDOW_MS = 5_000L
private const val MINIMUM_PERFORMANCE_SAMPLE_WINDOW_SECONDS = 4.0
private const val MINIMUM_AVERAGE_FPS = 59.0
private const val MINIMUM_WINDOW_FPS = 55.0
private const val MAXIMUM_AV_SYNC_OFFSET_MS = 45.0f
private const val MAXIMUM_RESIDENT_SET_GROWTH_KIB = 256 * 1_024

/** Intent-driven end-to-end color pipeline test used by the physical Android matrix. */
internal class AndroidColorPipelineSelfTest(
    private val activity: ComponentActivity,
) {
    fun startIfRequested(playbackOptions: VideoPlaybackOptions): Boolean {
        val inputPath = activity.intent.getStringExtra(EXTRA_INPUT_PATH) ?: return false
        val expectedSource =
            activity.intent
                .getStringExtra(EXTRA_EXPECTED_SOURCE_DYNAMIC_RANGE)
                ?.let { value -> runCatching { VideoDynamicRange.valueOf(value) }.getOrNull() }
                ?: VideoDynamicRange.HLG
        val expectedOutput =
            activity.intent
                .getStringExtra(EXTRA_EXPECTED_OUTPUT_DYNAMIC_RANGE)
                ?.let { value -> runCatching { VideoDynamicRange.valueOf(value) }.getOrNull() }
                ?: VideoDynamicRange.SDR
        val sustainedTestSeconds =
            activity.intent.getLongExtra(EXTRA_SUSTAINED_TEST_SECONDS, 0L).coerceAtLeast(0L)
        if ((activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.e(TAG, "KMP_COLOR_SELF_TEST=REJECTED_NON_DEBUGGABLE")
            activity.finish()
            return true
        }
        allowComposeMediaPlayerLogging = true
        composeMediaPlayerLogSink = { line -> Log.d(TAG, line) }

        val resultFile = File(activity.filesDir, RESULT_FILE_NAME).also { it.delete() }
        activity.setContent {
            val playerState = rememberRenderableVideoPlayerState(playbackOptions = playbackOptions)
            LaunchedEffect(playerState, inputPath) {
                var lastStatus: VideoColorPipelineStatus? = null
                val outcome =
                    runCatching {
                        check(File(inputPath).isFile) { "Missing test input." }
                        playerState.loop = sustainedTestSeconds > 0L
                        playerState.openUri(Uri.fromFile(File(inputPath)).toString(), InitialPlayerState.PLAY)
                        withTimeout(SELF_TEST_TIMEOUT_MS + sustainedTestSeconds * 1_000L) {
                            val matchedStatus =
                                playerState.colorPipelineStatus.first { status ->
                                    lastStatus = status
                                    status.matchesExpectedColorOutput(expectedSource, expectedOutput)
                                }
                            delay(STABLE_OUTPUT_WINDOW_MS)
                            val stableStatus = playerState.colorPipelineStatus.value
                            lastStatus = stableStatus
                            check(stableStatus.matchesExpectedColorOutput(expectedSource, expectedOutput)) {
                                "The expected output was not stable for $STABLE_OUTPUT_WINDOW_MS ms " +
                                    "after ${matchedStatus.verification}."
                            }
                            if (sustainedTestSeconds > 0L) {
                                runSustainedPlaybackCheck(
                                    playerState = playerState,
                                    expectedSource = expectedSource,
                                    expectedOutput = expectedOutput,
                                    durationSeconds = sustainedTestSeconds,
                                ).also { result ->
                                    lastStatus = result.status
                                }
                            } else {
                                stableStatus.also { status ->
                                    lastStatus = status
                                }
                            }
                        }
                    }
                val summary =
                    outcome.fold(
                        onSuccess = { result ->
                            when (result) {
                                is SustainedPlaybackResult -> result.summary()
                                is VideoColorPipelineStatus ->
                                    "PASS source=${result.source.dynamicRange} output=${result.outputDynamicRange} " +
                                        "renderer=${result.renderer} verification=${result.verification} " +
                                        "decoder=${result.decoderName}"
                                else -> error("Unexpected self-test result: ${result::class.simpleName}")
                            }
                        },
                        onFailure = { failure ->
                            val detail =
                                listOfNotNull(failure.message, lastStatus?.diagnosticSummary())
                                    .joinToString("; ")
                                    .replace(inputPath, "<input>")
                                    .replace(File(inputPath).name, "<input>")
                                    .take(MAXIMUM_LOGGED_DETAIL_LENGTH)
                            "FAIL type=${failure::class.simpleName} detail=$detail"
                        },
                    )
                withContext(Dispatchers.IO) { resultFile.writeText(summary) }
                Log.i(TAG, "KMP_COLOR_SELF_TEST=$summary")
                activity.finish()
            }
            VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return true
    }

    private companion object {
        const val TAG = "KMediaPlayerColorSelfTest"
        const val EXTRA_INPUT_PATH = "sample.app.extra.COLOR_PIPELINE_SELF_TEST_PATH"
        const val EXTRA_EXPECTED_SOURCE_DYNAMIC_RANGE =
            "sample.app.extra.COLOR_PIPELINE_SELF_TEST_EXPECTED_SOURCE"
        const val EXTRA_EXPECTED_OUTPUT_DYNAMIC_RANGE =
            "sample.app.extra.COLOR_PIPELINE_SELF_TEST_EXPECTED_OUTPUT"
        const val EXTRA_SUSTAINED_TEST_SECONDS =
            "sample.app.extra.COLOR_PIPELINE_SELF_TEST_SUSTAINED_SECONDS"
        const val RESULT_FILE_NAME = "kmp-color-self-test-result.txt"
        const val SELF_TEST_TIMEOUT_MS = 60_000L
        const val STABLE_OUTPUT_WINDOW_MS = 1_500L
        const val MAXIMUM_LOGGED_DETAIL_LENGTH = 320
    }
}

private suspend fun runSustainedPlaybackCheck(
    playerState: io.github.kdroidfilter.composemediaplayer.RenderableVideoPlayerState,
    expectedSource: VideoDynamicRange,
    expectedOutput: VideoDynamicRange,
    durationSeconds: Long,
): SustainedPlaybackResult {
    val startedAtNs = SystemClock.elapsedRealtimeNanos()
    val baselineFrames = playerState.diagnostics.renderedVideoFrames ?: 0L
    val initialPssKib = Debug.getPss()
    var previousFrames = baselineFrames
    var previousSampleNs = startedAtNs
    var minimumFps = Float.POSITIVE_INFINITY
    var peakPssKib = initialPssKib
    val deadlineNs = startedAtNs + durationSeconds * 1_000_000_000L

    while (SystemClock.elapsedRealtimeNanos() < deadlineNs) {
        val remainingMs = ((deadlineNs - SystemClock.elapsedRealtimeNanos()) / 1_000_000L).coerceAtLeast(1L)
        delay(minOf(PERFORMANCE_SAMPLE_WINDOW_MS, remainingMs))
        val sampledAtNs = SystemClock.elapsedRealtimeNanos()
        val diagnostics = playerState.diagnostics
        val renderedFrames = checkNotNull(diagnostics.renderedVideoFrames) { "Rendered frame telemetry unavailable." }
        val windowSeconds = (sampledAtNs - previousSampleNs) / 1_000_000_000.0
        if (windowSeconds >= MINIMUM_PERFORMANCE_SAMPLE_WINDOW_SECONDS) {
            minimumFps = minOf(minimumFps, ((renderedFrames - previousFrames) / windowSeconds).toFloat())
        }
        previousFrames = renderedFrames
        previousSampleNs = sampledAtNs
        peakPssKib = maxOf(peakPssKib, Debug.getPss())
        check(playerState.colorPipelineStatus.value.matchesExpectedColorOutput(expectedSource, expectedOutput)) {
            "Color output changed during the sustained run."
        }
        check(playerState.error == null) { "Playback error during sustained run: ${playerState.error}" }
        if (!playerState.isPlaying && !playerState.isLoading) {
            delay(1_000L)
            check(playerState.isPlaying || playerState.isLoading) { "Playback stopped during the sustained run." }
        }
    }

    val finishedAtNs = SystemClock.elapsedRealtimeNanos()
    val diagnostics = playerState.diagnostics
    val renderedFrames = (diagnostics.renderedVideoFrames ?: 0L) - baselineFrames
    val actualDurationSeconds = (finishedAtNs - startedAtNs) / 1_000_000_000.0
    val averageFps = renderedFrames / actualDurationSeconds
    val sampledMinimumFps = minimumFps.takeIf(Float::isFinite) ?: 0f
    val maximumAvSyncOffsetMs =
        checkNotNull(diagnostics.maximumAvSyncOffsetMs) { "A/V sync telemetry unavailable." }
    val residentSetGrowthKib = (peakPssKib - initialPssKib).coerceAtLeast(0)
    check(averageFps >= MINIMUM_AVERAGE_FPS) { "Average frame rate $averageFps is below $MINIMUM_AVERAGE_FPS." }
    check(sampledMinimumFps >= MINIMUM_WINDOW_FPS) {
        "Minimum sampled frame rate $sampledMinimumFps is below $MINIMUM_WINDOW_FPS."
    }
    check(maximumAvSyncOffsetMs <= MAXIMUM_AV_SYNC_OFFSET_MS) {
        "Maximum A/V sync offset $maximumAvSyncOffsetMs ms exceeds $MAXIMUM_AV_SYNC_OFFSET_MS ms."
    }
    check(residentSetGrowthKib <= MAXIMUM_RESIDENT_SET_GROWTH_KIB) {
        "Resident set grew by ${residentSetGrowthKib / 1_024.0} MiB."
    }
    return SustainedPlaybackResult(
        status = playerState.colorPipelineStatus.value,
        durationSeconds = actualDurationSeconds,
        renderedFrames = renderedFrames,
        droppedFrames = diagnostics.droppedVideoFrames ?: 0L,
        averageFps = averageFps,
        minimumFps = sampledMinimumFps,
        maximumAvSyncOffsetMs = maximumAvSyncOffsetMs,
        initialPssMib = initialPssKib / 1_024.0,
        peakPssMib = peakPssKib / 1_024.0,
        residentSetGrowthMib = residentSetGrowthKib / 1_024.0,
    )
}

private data class SustainedPlaybackResult(
    val status: VideoColorPipelineStatus,
    val durationSeconds: Double,
    val renderedFrames: Long,
    val droppedFrames: Long,
    val averageFps: Double,
    val minimumFps: Float,
    val maximumAvSyncOffsetMs: Float,
    val initialPssMib: Double,
    val peakPssMib: Double,
    val residentSetGrowthMib: Double,
) {
    fun summary(): String =
        String.format(
            Locale.US,
            "PASS source=%s output=%s renderer=%s verification=%s decoder=%s " +
                "durationSeconds=%.3f renderedFrames=%d droppedFrames=%d averageFps=%.3f " +
                "minimumFps=%.3f maxAvSyncMs=%.3f initialPssMiB=%.3f peakPssMiB=%.3f " +
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
            maximumAvSyncOffsetMs,
            initialPssMib,
            peakPssMib,
            residentSetGrowthMib,
        )
}

private fun VideoColorPipelineStatus.matchesExpectedColorOutput(
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
        renderer == ColorPipelineRenderer.SOURCE_BRIDGE_SDR &&
            metadataHandling == DynamicMetadataHandling.APPLIED_BY_SOURCE_BRIDGE &&
            verification == ColorPipelineVerification.RENDERER_CONFIGURED
    } else {
        metadataHandling == DynamicMetadataHandling.PASSTHROUGH &&
            when (renderer) {
                ColorPipelineRenderer.SYSTEM_NATIVE ->
                    verification == ColorPipelineVerification.RENDERER_CONFIGURED ||
                        verification == ColorPipelineVerification.SYSTEM_REPORTED
                ColorPipelineRenderer.CONTROLLED_HDR ->
                    verification == ColorPipelineVerification.RENDERER_CONFIGURED
                else -> false
            }
    }
}

private fun VideoColorPipelineStatus.diagnosticSummary(): String =
    "last=source=${source.dynamicRange} planned=$plannedOutputDynamicRange " +
        "output=$outputDynamicRange renderer=$renderer verification=$verification " +
        "fallback=$fallbackReason"
