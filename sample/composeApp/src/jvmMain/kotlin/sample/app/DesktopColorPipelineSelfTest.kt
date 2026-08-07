package sample.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nucleusframework.window.tao.LocalTaoWindow
import dev.nucleusframework.window.tao.TaoWindow
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.ColorPipelineRenderer
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.RenderableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
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
private const val MAXIMUM_EXPECTED_RENDER_FPS = 60.0
private const val STRICT_HIGH_FRAME_RATE_FLOOR = 59.0
private const val MINIMUM_SOURCE_FPS_RATIO = 0.94
private const val MINIMUM_WINDOW_SOURCE_FPS_RATIO = 0.85
private const val MAXIMUM_DROPPED_FRAME_RATIO = 0.15
private const val MAXIMUM_AV_SYNC_OFFSET_MS = 45.0f
private const val MAXIMUM_RESIDENT_SET_GROWTH_KIB = 256L * 1_024L
private val selfTestStartTimeoutMs =
    System.getProperty("sample.app.colorSelfTestStartTimeoutSeconds")
        ?.toLongOrNull()
        ?.takeIf { it in 1L..600L }
        ?.times(1_000L)
        ?: 90_000L
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
    windowState: WindowState,
    verifyWindowLifecycle: Boolean,
    onComplete: () -> Unit,
) {
    allowComposeMediaPlayerLogging = true
    composeMediaPlayerLogSink = { line -> println(line) }
    val playerState = rememberRenderableVideoPlayerState(playbackOptions = playbackOptions)
    val taoWindow = LocalTaoWindow.current
    LaunchedEffect(playerState, inputUri, taoWindow) {
        var lastStatus: VideoColorPipelineStatus? = null
        val outcome =
            runCatching {
                playerState.loop = true
                playerState.openUri(inputUri, InitialPlayerState.PLAY)
                withTimeout(selfTestStartTimeoutMs) {
                    while (true) {
                        val status = playerState.colorPipelineStatus.value
                        lastStatus = status
                        val startupError = playerState.error
                        val startupFailureIsFinal =
                            startupError != null &&
                                (
                                    startupError !is VideoPlayerError.ColorPipelineError ||
                                    status.source.dynamicRange != VideoDynamicRange.UNKNOWN ||
                                        status.outputDynamicRange != VideoDynamicRange.UNKNOWN
                                )
                        check(!startupFailureIsFinal) {
                            "Playback failed before color output was confirmed: $startupError"
                        }
                        if (status.matchesExpectedDesktopColorOutput(expectedSource, expectedOutput)) break
                        delay(100L)
                    }
                }
                withTimeout(selfTestStartTimeoutMs) {
                    while (!playerState.isPlaying || playerState.isLoading) {
                        check(playerState.error == null) {
                            "Playback failed before the sustained run: ${playerState.error}"
                        }
                        delay(50L)
                    }
                }
                delay(STABLE_OUTPUT_WINDOW_MS)
                val stableStatus = playerState.colorPipelineStatus.value
                lastStatus = stableStatus
                check(stableStatus.matchesExpectedDesktopColorOutput(expectedSource, expectedOutput)) {
                    "The expected color output did not remain stable."
                }
                if (verifyWindowLifecycle) {
                    runDesktopWindowLifecycleCheck(
                        playerState = playerState,
                        windowState = windowState,
                        taoWindow = checkNotNull(taoWindow) { "The Tao window is unavailable." },
                    )
                }
                runDesktopSustainedPlaybackCheck(
                    playerState = playerState,
                    expectedSource = expectedSource,
                    expectedOutput = expectedOutput,
                    requireAudioSync = requireAudioSync,
                    durationSeconds = durationSeconds,
                ).copy(windowLifecycleVerified = verifyWindowLifecycle)
                    .also { result -> lastStatus = result.status }
            }
        val summary =
            outcome.fold(
                onSuccess = DesktopSustainedPlaybackResult::summary,
                onFailure = { failure ->
                    val detail =
                        listOfNotNull(
                            failure.message,
                            lastStatus?.compactDiagnostic(),
                            "playing=${playerState.isPlaying} loading=${playerState.isLoading} " +
                                "error=${playerState.error} " +
                                "rendered=${playerState.diagnostics.renderedVideoFrames} " +
                                "dropped=${playerState.diagnostics.droppedVideoFrames}",
                        )
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

private suspend fun runDesktopWindowLifecycleCheck(
    playerState: RenderableVideoPlayerState,
    windowState: WindowState,
    taoWindow: TaoWindow,
) {
    val originalSize = windowState.size
    val originalBounds = checkNotNull(taoWindow.outerBoundsPx()) { "The native window bounds are unavailable." }

    suspend fun awaitNativeState(label: String, predicate: () -> Boolean) {
        withTimeout(WINDOW_TRANSITION_TIMEOUT_MS) {
            while (!predicate()) delay(WINDOW_STATE_POLL_MS)
        }
        delay(WINDOW_STATE_SETTLING_MS)
        check(predicate()) { "$label did not remain stable." }
    }

    suspend fun awaitRenderedFrames(label: String) {
        val baseline = checkNotNull(playerState.diagnostics.renderedVideoFrames) {
            "Rendered-frame telemetry is unavailable during $label."
        }
        withTimeout(WINDOW_FRAME_TIMEOUT_MS) {
            while ((playerState.diagnostics.renderedVideoFrames ?: baseline) < baseline + WINDOW_MINIMUM_FRESH_FRAMES) {
                check(playerState.error == null) { "Playback failed during $label: ${playerState.error}" }
                delay(WINDOW_STATE_POLL_MS)
            }
        }
    }

    playerState.isFullscreen = true
    windowState.placement = WindowPlacement.Fullscreen
    awaitNativeState("Fullscreen entry") { taoWindow.isFullscreen }
    check(windowState.placement == WindowPlacement.Fullscreen) {
        "WindowState lost Fullscreen during the native entry animation."
    }
    awaitRenderedFrames("fullscreen playback")

    playerState.isFullscreen = false
    windowState.placement = WindowPlacement.Floating
    awaitNativeState("Fullscreen exit") { !taoWindow.isFullscreen }
    check(windowState.placement != WindowPlacement.Fullscreen) {
        "WindowState remained Fullscreen after the native exit animation."
    }
    awaitRenderedFrames("post-fullscreen playback")

    windowState.placement = WindowPlacement.Maximized
    awaitNativeState("Native maximize") { taoWindow.isMaximized && !taoWindow.isFullscreen }
    awaitRenderedFrames("maximized playback")

    windowState.placement = WindowPlacement.Floating
    awaitNativeState("Native maximize restore") { !taoWindow.isMaximized && !taoWindow.isFullscreen }
    awaitRenderedFrames("restored playback")

    val scale = taoWindow.scaleFactor.toDouble().coerceAtLeast(1.0)
    val movedX = originalBounds[0] / scale + WINDOW_MOVE_OFFSET_DP
    val movedY = originalBounds[1] / scale + WINDOW_MOVE_OFFSET_DP
    windowState.position = WindowPosition.Absolute(movedX.dp, movedY.dp)
    awaitNativeState("Native window move") {
        taoWindow.outerBoundsPx()?.let { bounds ->
            bounds[0] != originalBounds[0] || bounds[1] != originalBounds[1]
        } == true
    }
    awaitRenderedFrames("moved-window playback")

    val resizedWidth = (originalSize.width.value - WINDOW_RESIZE_DELTA_DP).coerceAtLeast(WINDOW_MINIMUM_WIDTH_DP)
    val resizedHeight = (originalSize.height.value - WINDOW_RESIZE_DELTA_DP).coerceAtLeast(WINDOW_MINIMUM_HEIGHT_DP)
    windowState.size = DpSize(resizedWidth.dp, resizedHeight.dp)
    awaitNativeState("Native window resize") {
        taoWindow.outerBoundsPx()?.let { bounds ->
            bounds[2] != originalBounds[2] || bounds[3] != originalBounds[3]
        } == true
    }
    awaitRenderedFrames("resized-window playback")

    windowState.size = originalSize
    windowState.position =
        WindowPosition.Absolute(
            (originalBounds[0] / scale).dp,
            (originalBounds[1] / scale).dp,
        )
    awaitNativeState("Window geometry restore") {
        taoWindow.outerBoundsPx()?.let { bounds ->
            kotlin.math.abs(bounds[0] - originalBounds[0]) <= WINDOW_GEOMETRY_TOLERANCE_PX &&
                kotlin.math.abs(bounds[1] - originalBounds[1]) <= WINDOW_GEOMETRY_TOLERANCE_PX &&
                kotlin.math.abs(bounds[2] - originalBounds[2]) <= WINDOW_GEOMETRY_TOLERANCE_PX &&
                kotlin.math.abs(bounds[3] - originalBounds[3]) <= WINDOW_GEOMETRY_TOLERANCE_PX
        } == true
    }
    awaitRenderedFrames("restored-geometry playback")
}

private suspend fun runDesktopSustainedPlaybackCheck(
    playerState: RenderableVideoPlayerState,
    expectedSource: VideoDynamicRange,
    expectedOutput: VideoDynamicRange,
    requireAudioSync: Boolean,
    durationSeconds: Long,
): DesktopSustainedPlaybackResult {
    val frameRateThresholds = desktopFrameRateThresholds(playerState.metadata.frameRate?.toDouble())
    val started = TimeSource.Monotonic.markNow()
    val baselineRenderedFrames =
        checkNotNull(playerState.diagnostics.renderedVideoFrames) { "Rendered frame telemetry unavailable." }
    val baselineDroppedFrames =
        checkNotNull(playerState.diagnostics.droppedVideoFrames) { "Dropped-frame telemetry unavailable." }
    val initialResidentSetKib = checkNotNull(currentResidentSetKib()) { "Resident-set telemetry unavailable." }
    var previousProcessedFrames = baselineRenderedFrames + baselineDroppedFrames
    var previousElapsedSeconds = 0.0
    var minimumProcessedFps = Double.POSITIVE_INFINITY
    var peakResidentSetKib = initialResidentSetKib

    while (started.elapsedNow().inWholeMilliseconds < durationSeconds * 1_000L) {
        val remainingMs =
            (durationSeconds * 1_000L - started.elapsedNow().inWholeMilliseconds).coerceAtLeast(1L)
        delay(minOf(PERFORMANCE_SAMPLE_WINDOW_MS, remainingMs))
        val elapsedSeconds = started.elapsedNow().inWholeNanoseconds / 1_000_000_000.0
        val diagnostics = playerState.diagnostics
        val renderedFrames =
            checkNotNull(diagnostics.renderedVideoFrames) { "Rendered frame telemetry disappeared." }
        val droppedFrames =
            checkNotNull(diagnostics.droppedVideoFrames) { "Dropped-frame telemetry disappeared." }
        val processedFrames = renderedFrames + droppedFrames
        val windowSeconds = elapsedSeconds - previousElapsedSeconds
        if (windowSeconds >= MINIMUM_PERFORMANCE_SAMPLE_WINDOW_SECONDS) {
            minimumProcessedFps =
                minOf(minimumProcessedFps, (processedFrames - previousProcessedFrames) / windowSeconds)
        }
        previousProcessedFrames = processedFrames
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
        checkNotNull(diagnostics.renderedVideoFrames) { "Rendered frame telemetry disappeared." } -
            baselineRenderedFrames
    val droppedFrames =
        checkNotNull(diagnostics.droppedVideoFrames) { "Dropped-frame telemetry unavailable." } -
            baselineDroppedFrames
    val maximumAvSyncOffsetMs = diagnostics.maximumAvSyncOffsetMs
    val framePerformance = desktopFramePerformance(renderedFrames, droppedFrames, actualDurationSeconds)
    val sampledMinimumProcessedFps = minimumProcessedFps.takeIf(Double::isFinite) ?: 0.0
    val residentSetGrowthKib = (peakResidentSetKib - initialResidentSetKib).coerceAtLeast(0L)

    check(framePerformance.processedAverageFps >= frameRateThresholds.minimumAverageFps) {
        "Average processed frame rate ${framePerformance.processedAverageFps} is below " +
            "${frameRateThresholds.minimumAverageFps} " +
            "(rendered=$renderedFrames, dropped=$droppedFrames, duration=$actualDurationSeconds, " +
            "minimumProcessedWindowFps=$sampledMinimumProcessedFps, " +
            "sourceFps=${frameRateThresholds.sourceFrameRate})."
    }
    check(sampledMinimumProcessedFps >= frameRateThresholds.minimumWindowFps) {
        "Minimum sampled processed frame rate $sampledMinimumProcessedFps is below " +
            "${frameRateThresholds.minimumWindowFps} " +
            "for sourceFps=${frameRateThresholds.sourceFrameRate}."
    }
    check(framePerformance.droppedFrameRatio <= MAXIMUM_DROPPED_FRAME_RATIO) {
        "Dropped-frame ratio ${framePerformance.droppedFrameRatio} exceeds $MAXIMUM_DROPPED_FRAME_RATIO " +
            "(rendered=$renderedFrames, dropped=$droppedFrames)."
    }
    if (requireAudioSync) {
        val measuredMaximumAvSyncOffsetMs = checkNotNull(maximumAvSyncOffsetMs) {
            "A/V sync telemetry unavailable because the source has no active audio track."
        }
        check(measuredMaximumAvSyncOffsetMs <= MAXIMUM_AV_SYNC_OFFSET_MS) {
            "Maximum A/V sync offset $measuredMaximumAvSyncOffsetMs ms exceeds $MAXIMUM_AV_SYNC_OFFSET_MS ms."
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
        renderedAverageFps = framePerformance.renderedAverageFps,
        processedAverageFps = framePerformance.processedAverageFps,
        minimumProcessedFps = sampledMinimumProcessedFps,
        droppedFrameRatio = framePerformance.droppedFrameRatio,
        maximumDroppedFrameRatio = MAXIMUM_DROPPED_FRAME_RATIO,
        sourceFrameRate = frameRateThresholds.sourceFrameRate,
        requiredAverageFps = frameRateThresholds.minimumAverageFps,
        requiredWindowFps = frameRateThresholds.minimumWindowFps,
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
    val renderedAverageFps: Double,
    val processedAverageFps: Double,
    val minimumProcessedFps: Double,
    val droppedFrameRatio: Double,
    val maximumDroppedFrameRatio: Double,
    val sourceFrameRate: Double?,
    val requiredAverageFps: Double,
    val requiredWindowFps: Double,
    val maximumAvSyncOffsetMs: Float?,
    val initialResidentSetMib: Double,
    val peakResidentSetMib: Double,
    val residentSetGrowthMib: Double,
    val windowLifecycleVerified: Boolean = false,
) {
    fun summary(): String =
        String.format(
            Locale.US,
            "PASS source=%s output=%s renderer=%s verification=%s decoder=%s " +
                "durationSeconds=%.3f renderedFrames=%d droppedFrames=%d renderedFps=%.3f " +
                "processedFps=%.3f minimumProcessedFps=%.3f droppedFrameRatio=%.4f " +
                "maximumDroppedFrameRatio=%.4f sourceFps=%s requiredAverageFps=%.3f " +
                "requiredWindowFps=%.3f " +
                "maxAvSyncMs=%s initialRssMiB=%.3f peakRssMiB=%.3f " +
                "residentSetGrowthMiB=%.3f boundedMemory=true windowLifecycle=%s",
            status.source.dynamicRange,
            status.outputDynamicRange,
            status.renderer,
            status.verification,
            status.decoderName,
            durationSeconds,
            renderedFrames,
            droppedFrames,
            renderedAverageFps,
            processedAverageFps,
            minimumProcessedFps,
            droppedFrameRatio,
            maximumDroppedFrameRatio,
            sourceFrameRate?.let { String.format(Locale.US, "%.3f", it) } ?: "unknown",
            requiredAverageFps,
            requiredWindowFps,
            maximumAvSyncOffsetMs?.let { String.format(Locale.US, "%.3f", it) } ?: "unavailable",
            initialResidentSetMib,
            peakResidentSetMib,
            residentSetGrowthMib,
            if (windowLifecycleVerified) "PASS" else "SKIPPED",
        )
}

private const val WINDOW_TRANSITION_TIMEOUT_MS = 20_000L
private const val WINDOW_FRAME_TIMEOUT_MS = 8_000L
private const val WINDOW_STATE_POLL_MS = 50L
private const val WINDOW_STATE_SETTLING_MS = 350L
private const val WINDOW_MINIMUM_FRESH_FRAMES = 8L
private const val WINDOW_MOVE_OFFSET_DP = 24.0
private const val WINDOW_RESIZE_DELTA_DP = 96f
private const val WINDOW_MINIMUM_WIDTH_DP = 640f
private const val WINDOW_MINIMUM_HEIGHT_DP = 480f
private const val WINDOW_GEOMETRY_TOLERANCE_PX = 8L

internal data class DesktopFrameRateThresholds(
    val sourceFrameRate: Double?,
    val minimumAverageFps: Double,
    val minimumWindowFps: Double,
)

internal data class DesktopFramePerformance(
    val renderedAverageFps: Double,
    val processedAverageFps: Double,
    val droppedFrameRatio: Double,
)

internal fun desktopFramePerformance(
    renderedFrames: Long,
    droppedFrames: Long,
    durationSeconds: Double,
): DesktopFramePerformance {
    require(renderedFrames >= 0L) { "Rendered-frame count must not decrease." }
    require(droppedFrames >= 0L) { "Dropped-frame count must not decrease." }
    require(durationSeconds.isFinite() && durationSeconds > 0.0) { "Duration must be positive and finite." }
    val processedFrames = renderedFrames + droppedFrames
    require(processedFrames >= renderedFrames) { "Processed-frame count overflowed." }
    return DesktopFramePerformance(
        renderedAverageFps = renderedFrames / durationSeconds,
        processedAverageFps = processedFrames / durationSeconds,
        droppedFrameRatio = if (processedFrames == 0L) 0.0 else droppedFrames.toDouble() / processedFrames,
    )
}

internal fun desktopFrameRateThresholds(sourceFrameRate: Double?): DesktopFrameRateThresholds {
    val normalizedSourceRate = sourceFrameRate?.takeIf { it.isFinite() && it > 0.0 }
    val expectedRenderRate = normalizedSourceRate?.coerceAtMost(MAXIMUM_EXPECTED_RENDER_FPS)
    val ordinarySourceRate = expectedRenderRate?.takeIf { it < STRICT_HIGH_FRAME_RATE_FLOOR }
    return DesktopFrameRateThresholds(
        sourceFrameRate = normalizedSourceRate,
        minimumAverageFps =
            ordinarySourceRate
                ?.let { minOf(MINIMUM_AVERAGE_FPS, it * MINIMUM_SOURCE_FPS_RATIO) }
                ?: MINIMUM_AVERAGE_FPS,
        minimumWindowFps =
            ordinarySourceRate
                ?.let { minOf(MINIMUM_WINDOW_FPS, it * MINIMUM_WINDOW_SOURCE_FPS_RATIO) }
                ?: MINIMUM_WINDOW_FPS,
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
                    when (source.dynamicRange) {
                        VideoDynamicRange.HDR10_PLUS ->
                            metadataHandling == DynamicMetadataHandling.APPLIED_BY_RENDERER

                        VideoDynamicRange.DOLBY_VISION ->
                            metadataHandling == DynamicMetadataHandling.PASSTHROUGH ||
                                (
                                    metadataHandling == DynamicMetadataHandling.DROPPED &&
                                        fallbackReason == ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_USED
                                )

                        else -> metadataHandling == DynamicMetadataHandling.PASSTHROUGH
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
