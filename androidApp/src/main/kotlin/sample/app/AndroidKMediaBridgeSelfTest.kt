package sample.app

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.shusek.kmediabridge.AudioHandling
import io.github.shusek.kmediabridge.BridgeOutput
import io.github.shusek.kmediabridge.BridgeRequest
import io.github.shusek.kmediabridge.BridgeSupport
import io.github.shusek.kmediabridge.ColorMatrix
import io.github.shusek.kmediabridge.ColorPrimaries
import io.github.shusek.kmediabridge.ColorRange
import io.github.shusek.kmediabridge.ColorTransfer
import io.github.shusek.kmediabridge.DynamicRangeFormat
import io.github.shusek.kmediabridge.MediaBridgeEvent
import io.github.shusek.kmediabridge.MediaBridgeException
import io.github.shusek.kmediabridge.MediaBridgeSession
import io.github.shusek.kmediabridge.MediaInput
import io.github.shusek.kmediabridge.MediaInputKind
import io.github.shusek.kmediabridge.SubtitleHandling
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.ffmpeg.AndroidFfmpegNativeDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File

/** Intent-driven native smoke test used by the hardware matrix; never enabled in release builds. */
internal class AndroidKMediaBridgeSelfTest(
    private val activity: Activity,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startIfRequested(): Boolean {
        val inputPath = activity.intent.getStringExtra(EXTRA_INPUT_PATH) ?: return false
        if ((activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.e(TAG, "KMB_SELF_TEST=REJECTED_NON_DEBUGGABLE")
            activity.finish()
            return true
        }
        Log.i(TAG, "KMB_SELF_TEST=START")
        val resultFile = File(activity.filesDir, RESULT_FILE_NAME).also { it.delete() }
        scope.launch {
            val outcome = runCatching { withTimeout(SELF_TEST_TIMEOUT_MS) { run(inputPath) } }
            outcome
                .onSuccess { result ->
                    val summary =
                        "PASS init=${result.initializationFragments} " +
                            "media=${result.mediaFragments} bytes=${result.totalBytes}"
                    Log.i(
                        TAG,
                        "KMB_SELF_TEST=$summary",
                    )
                    resultFile.writeText(summary)
                }.onFailure { failure ->
                    // Do not include the source locator or native exception message in device logs.
                    val code = (failure as? MediaBridgeException)?.code?.name ?: "NONE"
                    val detail =
                        failure.message
                            ?.replace(inputPath, "<input>")
                            ?.replace(File(inputPath).name, "<input>")
                            ?.take(MAXIMUM_LOGGED_DETAIL_LENGTH)
                            .orEmpty()
                    val summary = "FAIL type=${failure::class.simpleName} code=$code detail=$detail"
                    Log.e(TAG, "KMB_SELF_TEST=$summary")
                    resultFile.writeText(summary)
                }
            activity.runOnUiThread(activity::finish)
        }
        return true
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun run(inputPath: String): Result {
        check(File(inputPath).isFile) { "Missing test input." }
        val input = MediaInput(inputPath, MediaInputKind.FILE, isLive = false, isEncrypted = false)
        val request =
            BridgeRequest(
                output = BridgeOutput.CMAF_FRAGMENT_STREAM,
                videoHandling = VideoHandling.TONE_MAP_TO_SDR,
                audioHandling = AudioHandling.COPY,
                subtitleHandling = SubtitleHandling.OMIT,
                fragmentDurationUs = 500_000L,
            )
        Log.i(TAG, "KMB_SELF_TEST=LOAD_RUNTIME")
        val driver = AndroidFfmpegNativeDriver.load()
        Log.i(TAG, "KMB_SELF_TEST=EVALUATE")
        check(driver.evaluate(input, request) is BridgeSupport.Supported) { "Unsupported test request." }
        Log.i(TAG, "KMB_SELF_TEST=OPEN")
        val session = driver.open(input, request)
        return try {
            Log.i(TAG, "KMB_SELF_TEST=COLLECT")
            collectVerifiedOutput(session)
        } finally {
            session.close()
        }
    }

    private suspend fun collectVerifiedOutput(session: MediaBridgeSession): Result {
        var outputConfigured = false
        var initializationFragments = 0
        var mediaFragments = 0
        var totalBytes = 0L
        session.events.first { event ->
            when (event) {
                is MediaBridgeEvent.OutputConfigured -> {
                    val color = event.value.outputColorInfo
                    check(event.value.videoHandling == VideoHandling.TONE_MAP_TO_SDR)
                    check(color?.dynamicRange == DynamicRangeFormat.SDR)
                    check(color.range == ColorRange.LIMITED)
                    check(color.primaries == ColorPrimaries.BT709)
                    check(color.transfer == ColorTransfer.BT709)
                    check(color.matrix == ColorMatrix.BT709)
                    outputConfigured = true
                }
                is MediaBridgeEvent.Fragment -> {
                    check(outputConfigured)
                    check(event.value.bytes.isNotEmpty())
                    totalBytes += event.value.bytes.size
                    if (event.value.isInitialization) initializationFragments++ else mediaFragments++
                }
                is MediaBridgeEvent.Discontinuity -> error("Unexpected discontinuity.")
                MediaBridgeEvent.EndOfStream -> Unit
            }
            event is MediaBridgeEvent.EndOfStream
        }
        check(outputConfigured)
        check(initializationFragments == 1)
        check(mediaFragments > 0)
        return Result(initializationFragments, mediaFragments, totalBytes)
    }

    private data class Result(
        val initializationFragments: Int,
        val mediaFragments: Int,
        val totalBytes: Long,
    )

    private companion object {
        const val TAG = "KMediaBridgeSelfTest"
        const val EXTRA_INPUT_PATH = "sample.app.extra.KMEDIABRIDGE_SELF_TEST_PATH"
        const val SELF_TEST_TIMEOUT_MS = 45_000L
        const val MAXIMUM_LOGGED_DETAIL_LENGTH = 320
        const val RESULT_FILE_NAME = "kmb-self-test-result.txt"
    }
}
