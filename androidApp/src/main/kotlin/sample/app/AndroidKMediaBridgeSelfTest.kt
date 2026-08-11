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
import io.github.shusek.kmediampv.runtime.android.MpvAndroidPlaybackSnapshot
import io.github.shusek.kmediampv.runtime.android.MpvAndroidPlayer
import io.github.shusek.kmediampv.runtime.android.MpvAndroidSurfaceDynamicRange
import io.github.shusek.kmediampv.runtime.android.MpvAndroidTrackInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        val skipMpvSurfaceRecreation =
            activity.intent.getBooleanExtra(EXTRA_MPV_SKIP_SURFACE_RECREATION, false)
        val mpvSustainedTestSeconds =
            activity.intent.getLongExtra(EXTRA_MPV_SUSTAINED_TEST_SECONDS, 0L).coerceIn(0L, 60L)
        val mpvTransitionInputPath = activity.intent.getStringExtra(EXTRA_MPV_TRANSITION_INPUT_PATH)
        val mpvTransitionTestSeconds =
            activity.intent.getLongExtra(EXTRA_MPV_TRANSITION_TEST_SECONDS, 0L).coerceIn(0L, 60L)
        val mpvColorOutputOnly = activity.intent.getBooleanExtra(EXTRA_MPV_COLOR_OUTPUT_ONLY, false)
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
                            skipMpvSurfaceRecreation = skipMpvSurfaceRecreation,
                            mpvSustainedTestSeconds = mpvSustainedTestSeconds,
                            mpvTransitionInputPath = mpvTransitionInputPath,
                            mpvTransitionTestSeconds = mpvTransitionTestSeconds,
                            mpvColorOutputOnly = mpvColorOutputOnly,
                            mpvSurfaceView = mpvSurfaceView,
                        )
                    }
                }
            outcome
                .onSuccess { result ->
                    val summary =
                        "PASS init=${result.initializationFragments} " +
                            "media=${result.mediaFragments} bytes=${result.totalBytes} " +
                            "mpv=${result.mpvVerified} color=${result.mpvColorOutputVerified} " +
                            "audio=${result.mpvAudioVerified} seek=${result.mpvSeekVerified} " +
                            "ass=${result.mpvAssVerified} " +
                            "surface=${result.mpvSurfaceVerified}"
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
        skipMpvSurfaceRecreation: Boolean,
        mpvSustainedTestSeconds: Long,
        mpvTransitionInputPath: String?,
        mpvTransitionTestSeconds: Long,
        mpvColorOutputOnly: Boolean,
        mpvSurfaceView: SurfaceView?,
    ): Result {
        check(File(inputPath).isFile) { "Missing test input." }
        if (subtitlePath != null) check(File(subtitlePath).isFile) { "Missing MPV subtitle input." }
        if (subtitlePath != null) check(File(mpvInputPath).isFile) { "Missing MPV media input." }
        if (mpvTransitionInputPath != null) {
            check(File(mpvTransitionInputPath).isFile) { "Missing MPV transition media input." }
        }
        val mpv =
            subtitlePath?.let {
                ConcurrentMpvSession(
                    activity = activity,
                    inputPath = mpvInputPath,
                    subtitlePath = it,
                    decodeMode = mpvDecodeMode,
                    expectSoftwareDecode = expectMpvSoftware,
                    skipSurfaceRecreation = skipMpvSurfaceRecreation,
                    sustainedTestSeconds = mpvSustainedTestSeconds,
                    transitionInputPath = mpvTransitionInputPath,
                    transitionTestSeconds = mpvTransitionTestSeconds,
                    colorOutputOnly = mpvColorOutputOnly,
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
                val verified = mpvVerification?.await() ?: MpvVerification()
                bridgeResult.copy(
                    mpvVerified = mpv != null,
                    mpvColorOutputVerified = verified.colorOutputVerified,
                    mpvAudioVerified = verified.audioVerified,
                    mpvSeekVerified = verified.seekVerified,
                    mpvAssVerified = verified.assVerified,
                    mpvSurfaceVerified = verified.surfaceVerified,
                )
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
        val mpvColorOutputVerified: Boolean = false,
        val mpvAudioVerified: Boolean = false,
        val mpvSeekVerified: Boolean = false,
        val mpvAssVerified: Boolean = false,
        val mpvSurfaceVerified: Boolean = false,
    )

    private data class MpvVerification(
        val colorOutputVerified: Boolean = false,
        val audioVerified: Boolean = false,
        val seekVerified: Boolean = false,
        val assVerified: Boolean = false,
        val surfaceVerified: Boolean = false,
    )

    private class ConcurrentMpvSession(
        activity: Activity,
        private val inputPath: String,
        private val subtitlePath: String,
        decodeMode: MpvAndroidDecodeMode,
        private val expectSoftwareDecode: Boolean?,
        private val skipSurfaceRecreation: Boolean,
        private val sustainedTestSeconds: Long,
        private val transitionInputPath: String?,
        private val transitionTestSeconds: Long,
        private val colorOutputOnly: Boolean,
        surfaceView: SurfaceView,
    ) : AutoCloseable {
        private val activity = activity
        private var surfaceView = surfaceView
        private val player = MpvAndroidPlayer.create(activity, null, decodeMode)

        suspend fun start() {
            attachWhenReady()
            player.setLoop(true)
            player.loadFile(inputPath)
            player.setPaused(false)
        }

        suspend fun awaitVerified(): MpvVerification {
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
                            if (decodePolicyVerified && snapshot.hasConsistentColorOutput()) {
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
            player.playbackSnapshot().also { snapshot ->
                logColorSnapshot("MPV_RENDERER", snapshot)
            }
            if (sustainedTestSeconds > 0L) delay(sustainedTestSeconds * 1_000L)
            transitionInputPath?.let { transition ->
                val before = player.playbackSnapshot()
                Log.i(TAG, "KMB_SELF_TEST=MPV_TRANSITION_LOAD")
                player.loadFile(transition)
                player.setPaused(false)
                val transitioned =
                    withTimeout(MPV_VERIFICATION_TIMEOUT_MS) {
                        while (true) {
                            val snapshot = player.playbackSnapshot()
                            val sourceChanged =
                                snapshot.sourceColorInfo.transfer != before.sourceColorInfo.transfer ||
                                    snapshot.surfaceOutputInfo.dataSpace != before.surfaceOutputInfo.dataSpace
                            if (sourceChanged && snapshot.hasConsistentColorOutput()) return@withTimeout snapshot
                            delay(MPV_POLL_INTERVAL_MS)
                        }
                        error("Unreachable MPV color-transition state.")
                    }
                logColorSnapshot("MPV_TRANSITION_VERIFIED", transitioned)
                if (transitionTestSeconds > 0L) delay(transitionTestSeconds * 1_000L)
            }
            if (colorOutputOnly) {
                Log.i(TAG, "KMB_SELF_TEST=MPV_COLOR_OUTPUT_VERIFIED")
                return MpvVerification(colorOutputVerified = true)
            }
            Log.i(TAG, "KMB_SELF_TEST=MPV_WAIT_AUDIO")
            withTimeout(MPV_VERIFICATION_TIMEOUT_MS) {
                while (true) {
                    if (player.tracks().any { track -> track.type == MpvAndroidTrackInfo.Type.AUDIO }) {
                        return@withTimeout
                    }
                    delay(MPV_POLL_INTERVAL_MS)
                }
            }
            Log.i(TAG, "KMB_SELF_TEST=MPV_SEEK")
            val seekTargetSeconds =
                withTimeout(MPV_VERIFICATION_TIMEOUT_MS) {
                    while (true) {
                        val duration = player.playbackSnapshot().durationSeconds
                        if (duration.isFinite() && duration > MINIMUM_SEEKABLE_DURATION_SECONDS) {
                            return@withTimeout minOf(TARGET_SEEK_SECONDS, duration / 2.0)
                        }
                        delay(MPV_POLL_INTERVAL_MS)
                    }
                    error("Unreachable seek-duration state.")
                }
            player.seekTo(seekTargetSeconds)
            withTimeout(MPV_VERIFICATION_TIMEOUT_MS) {
                while (true) {
                    val snapshot = player.playbackSnapshot()
                    if (
                        !snapshot.isSeeking &&
                        snapshot.timePositionSeconds.isFinite() &&
                        snapshot.timePositionSeconds >= seekTargetSeconds - SEEK_TOLERANCE_SECONDS
                    ) {
                        return@withTimeout
                    }
                    delay(MPV_POLL_INTERVAL_MS)
                }
            }
            Log.i(TAG, "KMB_SELF_TEST=MPV_SEEK_VERIFIED")
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
            if (skipSurfaceRecreation) {
                Log.i(TAG, "KMB_SELF_TEST=MPV_VERIFIED_WITHOUT_SURFACE_RECREATION")
                return MpvVerification(
                    colorOutputVerified = true,
                    audioVerified = true,
                    seekVerified = true,
                    assVerified = true,
                )
            }
            Log.i(TAG, "KMB_SELF_TEST=MPV_RECREATE_SURFACE")
            val positionBeforeRecreate = player.playbackSnapshot().timePositionSeconds
            recreateSurface()
            awaitRenderedFrame(positionBeforeRecreate)
            Log.i(TAG, "KMB_SELF_TEST=MPV_VERIFIED")
            return MpvVerification(
                colorOutputVerified = true,
                audioVerified = true,
                seekVerified = true,
                assVerified = true,
                surfaceVerified = true,
            )
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

        private fun MpvAndroidPlaybackSnapshot.hasConsistentColorOutput(): Boolean {
            if (
                currentVideoOutput != "gpu-next" ||
                currentGpuContext != "androidvk" ||
                !isVideoOutputConfigured ||
                surfaceOutputInfo.pixelFormat <= 0
            ) {
                return false
            }
            val expected =
                when (sourceColorInfo.transfer?.lowercase()) {
                    "pq" -> MpvAndroidSurfaceDynamicRange.HDR10
                    "hlg" -> MpvAndroidSurfaceDynamicRange.HLG
                    "bt.1886", "srgb", "linear", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4",
                    "gamma2.6", "gamma2.8",
                    -> MpvAndroidSurfaceDynamicRange.SDR
                    else -> return false
                }
            return surfaceOutputInfo.dynamicRange == expected &&
                (
                    expected == MpvAndroidSurfaceDynamicRange.SDR ||
                        surfaceOutputInfo.isHdrCapablePixelFormat
                )
        }

        private fun logColorSnapshot(
            phase: String,
            snapshot: MpvAndroidPlaybackSnapshot,
        ) {
            Log.i(
                TAG,
                "KMB_SELF_TEST=$phase " +
                    "vo=${snapshot.currentVideoOutput ?: "none"} " +
                    "gpu=${snapshot.currentGpuContext ?: "none"} " +
                    "hwdec=${snapshot.currentHardwareDecoder ?: "none"} " +
                    "transfer=${snapshot.sourceColorInfo.transfer ?: "none"} " +
                    "primaries=${snapshot.sourceColorInfo.primaries ?: "none"} " +
                    "minLuma=${snapshot.sourceColorInfo.minimumLuminanceNits} " +
                    "maxLuma=${snapshot.sourceColorInfo.maximumLuminanceNits} " +
                    "redX=${snapshot.sourceColorInfo.primaryRedX} " +
                    "redY=${snapshot.sourceColorInfo.primaryRedY} " +
                    "range=${snapshot.surfaceOutputInfo.dynamicRange} " +
                    "dataspace=${snapshot.surfaceOutputInfo.dataSpace} " +
                    "format=${snapshot.surfaceOutputInfo.pixelFormat}",
            )
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
        const val EXTRA_MPV_SKIP_SURFACE_RECREATION =
            "sample.app.extra.MPV_SELF_TEST_SKIP_SURFACE_RECREATION"
        const val EXTRA_MPV_SUSTAINED_TEST_SECONDS =
            "sample.app.extra.MPV_SELF_TEST_SUSTAINED_SECONDS"
        const val EXTRA_MPV_TRANSITION_INPUT_PATH =
            "sample.app.extra.MPV_SELF_TEST_TRANSITION_INPUT_PATH"
        const val EXTRA_MPV_TRANSITION_TEST_SECONDS =
            "sample.app.extra.MPV_SELF_TEST_TRANSITION_SECONDS"
        const val EXTRA_MPV_COLOR_OUTPUT_ONLY =
            "sample.app.extra.MPV_SELF_TEST_COLOR_OUTPUT_ONLY"
        const val SELF_TEST_TIMEOUT_MS = 150_000L
        const val MPV_VERIFICATION_TIMEOUT_MS = 15_000L
        const val MPV_SURFACE_TIMEOUT_MS = 5_000L
        const val MPV_POLL_INTERVAL_MS = 50L
        const val PIXEL_SAMPLE_GRID_SIZE = 24
        const val MINIMUM_FRAME_LUMA_RANGE = 8
        const val MINIMUM_POST_SURFACE_PLAYBACK_SECONDS = 0.25
        const val MINIMUM_DECODE_POLICY_SAMPLE_SECONDS = 0.5
        const val MINIMUM_SEEKABLE_DURATION_SECONDS = 2.0
        const val TARGET_SEEK_SECONDS = 2.0
        const val SEEK_TOLERANCE_SECONDS = 0.25
        const val MAXIMUM_LOGGED_DETAIL_LENGTH = 320
        const val RESULT_FILE_NAME = "kmb-self-test-result.txt"
    }
}
