package sample.app

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
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
import io.github.shusek.kmediampv.runtime.android.MpvAndroidDecodeMode
import io.github.shusek.kmediampv.runtime.android.MpvAndroidPlayer
import io.github.shusek.kmediampv.runtime.android.MpvAndroidTrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File

/** Intent-driven native integration test used by the local hardware matrix. */
internal class AndroidKMediaBridgeSelfTest(
    private val activity: Activity,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startIfRequested(): Boolean {
        val inputPath = activity.intent.getStringExtra(EXTRA_INPUT_PATH) ?: return false
        val subtitlePath = activity.intent.getStringExtra(EXTRA_MPV_SUBTITLE_PATH)
        val mpvInputPath = activity.intent.getStringExtra(EXTRA_MPV_INPUT_PATH) ?: inputPath
        val mpvDecodeMode =
            if (activity.intent.getBooleanExtra(EXTRA_MPV_SOFTWARE_ONLY, false)) {
                MpvAndroidDecodeMode.SOFTWARE_ONLY
            } else {
                MpvAndroidDecodeMode.MEDIA_CODEC_COPY
            }
        val expectMpvSoftware =
            when {
                mpvDecodeMode == MpvAndroidDecodeMode.SOFTWARE_ONLY -> true
                activity.intent.hasExtra(EXTRA_EXPECT_MPV_SOFTWARE) ->
                    activity.intent.getBooleanExtra(EXTRA_EXPECT_MPV_SOFTWARE, false)
                else -> null
            }
        val mpvOnly = activity.intent.getBooleanExtra(EXTRA_MPV_ONLY, false)
        val mpvSurfaceView = subtitlePath?.let { SurfaceView(activity).also(activity::setContentView) }
        if ((activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            Log.e(TAG, "KMB_SELF_TEST=REJECTED_NON_DEBUGGABLE")
            activity.finish()
            return true
        }
        Log.i(TAG, "KMB_SELF_TEST=START")
        val resultFile = File(activity.filesDir, RESULT_FILE_NAME).also { it.delete() }
        scope.launch {
            val outcome =
                runCatching {
                    withTimeout(SELF_TEST_TIMEOUT_MS) {
                        run(
                            inputPath = inputPath,
                            subtitlePath = subtitlePath,
                            mpvInputPath = mpvInputPath,
                            mpvDecodeMode = mpvDecodeMode,
                            expectMpvSoftware = expectMpvSoftware,
                            runBridgeConcurrently = !mpvOnly,
                            mpvSurfaceView = mpvSurfaceView,
                        )
                    }
                }
            outcome
                .onSuccess { result ->
                    val summary =
                        "PASS init=${result.initializationFragments} " +
                            "media=${result.mediaFragments} bytes=${result.totalBytes} " +
                            "mpv=${result.mpvVerified}"
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

    private suspend fun run(
        inputPath: String,
        subtitlePath: String?,
        mpvInputPath: String,
        mpvDecodeMode: MpvAndroidDecodeMode,
        expectMpvSoftware: Boolean?,
        runBridgeConcurrently: Boolean,
        mpvSurfaceView: SurfaceView?,
    ): Result {
        check(File(inputPath).isFile) { "Missing test input." }
        if (subtitlePath != null) check(File(subtitlePath).isFile) { "Missing MPV subtitle input." }
        if (subtitlePath != null) check(File(mpvInputPath).isFile) { "Missing MPV media input." }
        val mpv =
            subtitlePath?.let {
                ConcurrentMpvSession(
                    activity = activity,
                    inputPath = mpvInputPath,
                    subtitlePath = it,
                    decodeMode = mpvDecodeMode,
                    expectSoftwareDecode = expectMpvSoftware,
                    surfaceView = checkNotNull(mpvSurfaceView),
                )
            }
        try {
            mpv?.start()
            return coroutineScope {
                val mpvVerification = mpv?.let { session -> async { session.awaitVerified() } }
                val bridgeResult =
                    if (runBridgeConcurrently) {
                        runBridge(inputPath)
                    } else {
                        Result(0, 0, 0, mpvVerified = false)
                    }
                mpvVerification?.await()
                bridgeResult.copy(mpvVerified = mpv != null)
            }
        } finally {
            mpv?.close()
        }
    }

    private suspend fun runBridge(inputPath: String): Result {
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
        when (val support = driver.evaluate(input, request)) {
            is BridgeSupport.Supported -> Unit
            is BridgeSupport.Unsupported -> error("Unsupported test request: ${support.reason}")
        }
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
        return Result(initializationFragments, mediaFragments, totalBytes, mpvVerified = false)
    }

    private data class Result(
        val initializationFragments: Int,
        val mediaFragments: Int,
        val totalBytes: Long,
        val mpvVerified: Boolean,
    )

    private class ConcurrentMpvSession(
        activity: Activity,
        private val inputPath: String,
        private val subtitlePath: String,
        decodeMode: MpvAndroidDecodeMode,
        private val expectSoftwareDecode: Boolean?,
        surfaceView: SurfaceView,
    ) : AutoCloseable {
        private val activity = activity
        private var surfaceView = surfaceView
        private val player = MpvAndroidPlayer.create(activity, null, decodeMode)

        suspend fun start() {
            attachWhenReady()
            player.loadFile(inputPath)
            player.setPaused(false)
        }

        suspend fun awaitVerified() {
            Log.i(TAG, "KMB_SELF_TEST=MPV_WAIT_VIDEO")
            try {
                withTimeout(MPV_VERIFICATION_TIMEOUT_MS) {
                    while (true) {
                        val snapshot = player.playbackSnapshot()
                        if (snapshot.videoWidth > 0 && snapshot.videoHeight > 0) {
                            val hardwareDecoder = snapshot.currentHardwareDecoder
                            val softwareDecodeActive =
                                hardwareDecoder.isNullOrBlank() || hardwareDecoder == "no"
                            val decodePolicyVerified =
                                if (expectSoftwareDecode == true) {
                                    softwareDecodeActive &&
                                        snapshot.timePositionSeconds.isFinite() &&
                                        snapshot.timePositionSeconds >= MINIMUM_DECODE_POLICY_SAMPLE_SECONDS
                                } else if (expectSoftwareDecode == false) {
                                    !softwareDecodeActive
                                } else {
                                    snapshot.timePositionSeconds.isFinite()
                                }
                            if (decodePolicyVerified) {
                                return@withTimeout
                            }
                        }
                        delay(MPV_POLL_INTERVAL_MS)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                val snapshot = player.playbackSnapshot()
                error(
                    "MPV verification timed out: dimensions=${snapshot.videoWidth}x${snapshot.videoHeight} " +
                        "hwdec=${snapshot.currentHardwareDecoder ?: "none"} " +
                        "vo=${snapshot.currentVideoOutput ?: "none"} " +
                        "time=${snapshot.timePositionSeconds} eof=${snapshot.isEndOfFileReached} " +
                        "idle=${snapshot.isIdleActive}",
                )
            }
            Log.i(TAG, "KMB_SELF_TEST=MPV_ADD_SUBTITLE")
            player.addSubtitle(subtitlePath, true)
            withTimeout(MPV_VERIFICATION_TIMEOUT_MS) {
                while (true) {
                    val subtitleReady =
                        player.tracks().any { track ->
                            track.type == MpvAndroidTrackInfo.Type.SUBTITLE && track.isExternal
                        }
                    if (subtitleReady) return@withTimeout
                    delay(MPV_POLL_INTERVAL_MS)
                }
            }
            Log.i(TAG, "KMB_SELF_TEST=MPV_RECREATE_SURFACE")
            val positionBeforeRecreate = player.playbackSnapshot().timePositionSeconds
            recreateSurface()
            awaitRenderedFrame(positionBeforeRecreate)
            Log.i(TAG, "KMB_SELF_TEST=MPV_VERIFIED")
        }

        private suspend fun recreateSurface() {
            player.detachSurface()
            val replacement = CompletableDeferred<SurfaceView>()
            activity.runOnUiThread {
                SurfaceView(activity).also { view ->
                    surfaceView = view
                    activity.setContentView(view)
                    replacement.complete(view)
                }
            }
            replacement.await()
            attachWhenReady()
        }

        private suspend fun awaitRenderedFrame(positionBeforeRecreate: Double) {
            withTimeout(MPV_VERIFICATION_TIMEOUT_MS) {
                while (true) {
                    val width = surfaceView.width
                    val height = surfaceView.height
                    if (width > 0 && height > 0 && surfaceView.holder.surface.isValid) {
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val result = CompletableDeferred<Int>()
                        PixelCopy.request(
                            surfaceView,
                            bitmap,
                            result::complete,
                            Handler(Looper.getMainLooper()),
                        )
                        val copied = result.await() == PixelCopy.SUCCESS
                        val containsVideo = copied && bitmapHasVisibleVariation(bitmap)
                        bitmap.recycle()
                        if (containsVideo) return@withTimeout
                    }
                    val snapshot = player.playbackSnapshot()
                    val playbackAdvanced =
                        positionBeforeRecreate.isFinite() &&
                            snapshot.timePositionSeconds.isFinite() &&
                            snapshot.timePositionSeconds >=
                            positionBeforeRecreate + MINIMUM_POST_SURFACE_PLAYBACK_SECONDS
                    if (
                        playbackAdvanced &&
                        snapshot.isVideoOutputConfigured &&
                        !snapshot.currentVideoOutput.isNullOrBlank()
                    ) {
                        return@withTimeout
                    }
                    delay(MPV_POLL_INTERVAL_MS)
                }
            }
        }

        private fun bitmapHasVisibleVariation(bitmap: Bitmap): Boolean {
            var minimumLuma = 255
            var maximumLuma = 0
            val horizontalStep = maxOf(1, bitmap.width / PIXEL_SAMPLE_GRID_SIZE)
            val verticalStep = maxOf(1, bitmap.height / PIXEL_SAMPLE_GRID_SIZE)
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val luma =
                        ((pixel shr 16) and 0xff) * 54 / 256 +
                            ((pixel shr 8) and 0xff) * 183 / 256 +
                            (pixel and 0xff) * 19 / 256
                    minimumLuma = minOf(minimumLuma, luma)
                    maximumLuma = maxOf(maximumLuma, luma)
                    x += horizontalStep
                }
                y += verticalStep
            }
            return maximumLuma - minimumLuma >= MINIMUM_FRAME_LUMA_RANGE
        }

        private suspend fun attachWhenReady() {
            withTimeout(MPV_SURFACE_TIMEOUT_MS) {
                while (true) {
                    val surface = surfaceView.holder.surface
                    if (surface.isValid && surfaceView.width > 0 && surfaceView.height > 0) {
                        player.attachSurface(surface, surfaceView.width, surfaceView.height)
                        return@withTimeout
                    }
                    delay(MPV_POLL_INTERVAL_MS)
                }
            }
        }

        override fun close() {
            player.close()
        }
    }

    private companion object {
        const val TAG = "KMediaBridgeSelfTest"
        const val EXTRA_INPUT_PATH = "sample.app.extra.KMEDIABRIDGE_SELF_TEST_PATH"
        const val EXTRA_MPV_SUBTITLE_PATH = "sample.app.extra.MPV_SELF_TEST_SUBTITLE_PATH"
        const val EXTRA_MPV_INPUT_PATH = "sample.app.extra.MPV_SELF_TEST_INPUT_PATH"
        const val EXTRA_MPV_SOFTWARE_ONLY = "sample.app.extra.MPV_SELF_TEST_SOFTWARE_ONLY"
        const val EXTRA_EXPECT_MPV_SOFTWARE = "sample.app.extra.MPV_SELF_TEST_EXPECT_SOFTWARE"
        const val EXTRA_MPV_ONLY = "sample.app.extra.MPV_SELF_TEST_ONLY"
        const val SELF_TEST_TIMEOUT_MS = 45_000L
        const val MPV_VERIFICATION_TIMEOUT_MS = 15_000L
        const val MPV_SURFACE_TIMEOUT_MS = 5_000L
        const val MPV_POLL_INTERVAL_MS = 50L
        const val PIXEL_SAMPLE_GRID_SIZE = 24
        const val MINIMUM_FRAME_LUMA_RANGE = 8
        const val MINIMUM_POST_SURFACE_PLAYBACK_SECONDS = 0.25
        const val MINIMUM_DECODE_POLICY_SAMPLE_SECONDS = 0.5
        const val MAXIMUM_LOGGED_DETAIL_LENGTH = 320
        const val RESULT_FILE_NAME = "kmb-self-test-result.txt"
    }
}
