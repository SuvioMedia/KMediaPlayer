package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MediaChapter
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableException
import io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableReason
import io.github.kdroidfilter.composemediaplayer.PlaybackDiagnostics
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.renderingInfoLabel
import io.github.kdroidfilter.composemediaplayer.desktop.TaoPlaybackSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.mpv.internal.LibMpvEngine
import io.github.kdroidfilter.composemediaplayer.mpv.internal.LibMpvLibrary
import io.github.kdroidfilter.composemediaplayer.mpv.internal.MpvLoadFailure
import io.github.kdroidfilter.composemediaplayer.mpv.internal.NativeMpvEvent
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Stable
@OptIn(ExperimentalComposeMediaPlayerBackendApi::class)
internal class MpvVideoPlayerState(
    internal val runtimeConfig: MpvRuntimeConfig,
    private val engine: LibMpvEngine,
) : AbstractMpvVideoPlayerState(),
    VideoPlayerSurfaceProvider,
    TaoPlaybackSurfaceProvider {
    private val disposed = AtomicBoolean(false)
    private val renderLock = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventJob: Job
    private val frameState = mutableStateOf<ImageBitmap?>(null)
    private val framePool = MpvFramePool(runtimeConfig.maxRenderPixels)

    @Volatile
    private var nativeMacRenderer = 0L
    private var nativeMacColorMode = MpvMacOutputColorMode.SDR
    private var nativeMacProjectionEnabled = false
    private var cropMode = false

    @Volatile
    private var nativeMacDecodeRoute = MpvMacDecodeRoute.HARDWARE_AUTO

    @Volatile
    private var nativeMacSurfaceAttached = false

    @Volatile
    private var resumePlaybackAfterNativeSurfaceAttach = false

    @Volatile
    private var nativeMacDecodeReady = false

    @Volatile
    private var awaitingNativeMacDecodeRestart = false

    @Volatile
    private var renderContextSwitchDeadlineNanos = 0L

    @Volatile
    private var droppedVideoFrames: Long? = null

    @Volatile
    private var mpvTimingNotes: String = ""

    @Volatile
    private var maximumAvSyncOffsetMs: Float? = null

    private var lastTrackRefreshMs = 0L

    @Composable
    override fun RenderVideoPlayerSurface(
        modifier: Modifier,
        contentScale: ContentScale,
        overlay: @Composable () -> Unit,
    ) {
        MpvVideoPlayerSurface(
            playerState = this,
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
    }

    @Composable
    override fun RenderTaoPlaybackSurface(
        modifier: Modifier,
        contentScale: ContentScale,
        overlay: @Composable () -> Unit,
        onSurfaceAttached: () -> Unit,
    ) {
        MpvVideoPlayerSurface(
            playerState = this,
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
            onSurfaceAttached = onSurfaceAttached,
        )
    }

    override val renderingInfo =
        VideoRenderingInfo(
            backend = "libmpv",
            videoRenderer = "libmpv software render API",
            audioRenderer = "mpv audio output",
            subtitleRenderer = "mpv/libass",
            notes =
                if (usesVerifiedBundledRuntime) {
                    "Verified KMediaMpv runtime with credential-safe loopback HLS input."
                } else {
                    "User-provided libmpv; native runtime license is unverified."
                },
        )

    override val diagnostics: PlaybackDiagnostics
        get() {
            val nativePresentation =
                renderLock.withLock {
                    nativeMacRenderer
                        .takeIf { it != 0L }
                        ?.let { renderer ->
                            runCatching {
                                MpvMacNativeBridge.nGetPresentationDiagnostics(renderer)
                            }.getOrNull()
                        }
                }
            val renderedFrames = nativePresentation?.getOrNull(NEW_VIDEO_FRAME_COUNT_INDEX)
            val droppedFrames = droppedVideoFrames
            val presentationNotes =
                nativePresentation
                    ?.let { values ->
                        val renders = values.getOrNull(2)?.coerceAtLeast(1L) ?: 1L
                        val averageRenderMicros = values.getOrNull(10)?.div(renders)?.div(1_000L)
                        val maximumRenderMicros = values.getOrNull(11)?.div(1_000L)
                        val averageFlushMicros = values.getOrNull(12)?.div(renders)?.div(1_000L)
                        val maximumFlushMicros = values.getOrNull(13)?.div(1_000L)
                        " updates=${values.getOrNull(0)} draws=${values.getOrNull(1)} " +
                            "renders=${values.getOrNull(2)} swaps=${values.getOrNull(3)} " +
                            "new=${values.getOrNull(4)} repeats=${values.getOrNull(5)} " +
                            "redraws=${values.getOrNull(6)} empty=${values.getOrNull(7)} " +
                            "wakeups=${values.getOrNull(8)} liveResizeWakeups=${values.getOrNull(9)} " +
                            "liveResizeGeometry=${values.getOrNull(14)} " +
                            "liveResizeAspectErrorPpm=${values.getOrNull(15)} " +
                            "renderAvgUs=$averageRenderMicros renderMaxUs=$maximumRenderMicros " +
                            "flushAvgUs=$averageFlushMicros flushMaxUs=$maximumFlushMicros"
                    }.orEmpty()
            return PlaybackDiagnostics(
                totalVideoFrames =
                    if (renderedFrames != null && droppedFrames != null) {
                        renderedFrames + droppedFrames
                    } else {
                        renderedFrames
                    },
                renderedVideoFrames = renderedFrames,
                droppedVideoFrames = droppedFrames,
                maximumAvSyncOffsetMs = maximumAvSyncOffsetMs,
                videoWidth = metadata.width,
                videoHeight = metadata.height,
                bitrate = metadata.bitrate?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
                notes = renderingInfo.notes + presentationNotes + mpvTimingNotes,
            )
        }

    internal val canUseNativeMacSurface: Boolean
        get() = !disposed.get() && nativeMacRenderer != 0L

    init {
        initializeNativeMacRendererBeforePlayback()
        eventJob = scope.launch { eventLoop() }
    }

    override val capabilities =
        PlayerCapabilities(
            supportsMkv = true,
            supportedUriSchemes =
                if (usesVerifiedBundledRuntime) {
                    setOf("file")
                } else {
                    setOf("file", "http", "https", "rtmp", "rtsp")
                },
        )

    override val preciseCurrentTime: Duration
        get() =
            if (disposed.get()) {
                _currentTime
            } else {
                engine.getProperty("time-pos").secondsOrNull() ?: _currentTime
            }

    override val backendDisposed: Boolean
        get() = disposed.get()

    override fun setBackendVolume(value: Float) {
        engine.setProperty("volume", (value * MPV_VOLUME_SCALE).toString())
    }

    override fun setBackendLoop(value: Boolean) {
        engine.setProperty("loop-file", if (value) "inf" else "no")
    }

    override fun setBackendPlaybackSpeed(value: Float) {
        engine.setProperty("speed", value.toString())
    }

    override fun setBackendSubtitleOffset(value: Duration) {
        engine.setProperty(
            "sub-delay",
            value.inWholeMilliseconds
                .toDouble()
                .div(1_000.0)
                .toString(),
        )
    }

    override fun playBackend() {
        renderLock.withLock {
            if (nativeMacRenderer != 0L && (!nativeMacSurfaceAttached || !nativeMacDecodeReady)) {
                resumePlaybackAfterNativeSurfaceAttach = true
                engine.setProperty("pause", "yes")
            } else {
                resumePlaybackAfterNativeSurfaceAttach = false
                engine.setProperty("pause", "no")
            }
        }
    }

    override fun pauseBackend() {
        renderLock.withLock {
            resumePlaybackAfterNativeSurfaceAttach = false
            engine.setProperty("pause", "yes")
        }
    }

    override fun seekBackend(time: Duration) {
        val seekMode =
            if (nativeMacDecodeRoute == MpvMacDecodeRoute.SOFTWARE_HIGH_THROUGHPUT_HEVC && _isPlaying) {
                // Decoding a long 8K/60 HEVC GOP solely to reach an exact timestamp makes
                // playback visibly crawl for several seconds. While playing, prefer immediate
                // recovery at the closest keyframe. Paused and ordinary sources stay exact.
                "absolute+keyframes"
            } else {
                "absolute+exact"
            }
        engine.command(
            "seek",
            time.inWholeMilliseconds
                .toDouble()
                .div(1_000.0)
                .toString(),
            seekMode,
        )
    }

    override val seekFailureMessage: String
        get() = "libmpv rejected the seek request."

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        ensureOpen()
        require(uri.isNotBlank()) { "The media URI must not be blank." }
        val normalizedUri = uri.normalizedMpvMediaUri()
        require(requestHeaders.isEmpty()) {
            "The mpv artifact does not accept request headers. Use a credential-safe transport outside this backend."
        }
        require(!usesVerifiedBundledRuntime || normalizedUri.isVerifiedBundledMpvSource()) {
            "The verified KMediaMpv runtime accepts local files and credential-free loopback HTTP only."
        }

        droppedVideoFrames = null
        maximumAvSyncOffsetMs = null
        beginSourcePreparation(normalizedUri, initializePlayerState)

        try {
            renderLock.withLock {
                val wantsPlayback = initializePlayerState == InitialPlayerState.PLAY
                val waitForNativePipeline = nativeMacRenderer != 0L
                nativeMacDecodeReady = !waitForNativePipeline
                awaitingNativeMacDecodeRestart = false
                resumePlaybackAfterNativeSurfaceAttach = wantsPlayback && waitForNativePipeline
                resetNativeMacDecodeRouteBeforeSourceLoad()
                engine.command("loadfile", normalizedUri, "replace")
                engine.setProperty(
                    "pause",
                    if (wantsPlayback && !waitForNativePipeline) "no" else "yes",
                )
            }
        } catch (_: Throwable) {
            mutateSnapshotState {
                _hasMedia = false
            }
            publishError(VideoPlayerError.SourceError("libmpv rejected the media source."))
        }
    }

    override fun prepare(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) = openUri(uri, initializePlayerState, requestHeaders)

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        openUri(file.path, initializePlayerState, emptyMap())
    }

    override fun selectBackendAudioTrack(track: AudioTrack?): TrackSelectionResult =
        try {
            if (track == null) {
                engine.setProperty("aid", "auto")
                TrackSelectionResult.Auto
            } else {
                val mpvId = track.id.removePrefix(AUDIO_TRACK_PREFIX)
                if (mpvId == track.id) {
                    TrackSelectionResult.NotFound(track.id)
                } else {
                    engine.setProperty("aid", mpvId)
                    TrackSelectionResult.Selected(track.id)
                }
            }
        } catch (_: Throwable) {
            TrackSelectionResult.Failed("libmpv rejected the audio-track selection.")
        }

    override fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult {
        return try {
            when {
                track.isExternal -> {
                    val loadedTrackId = findMpvTrackId(type = "sub", externalFilename = track.src)
                    if (loadedTrackId == null) {
                        engine.command("sub-add", track.src, "cached", track.label, track.language)
                    } else {
                        engine.setProperty("sid", loadedTrackId)
                    }
                }
                track.id.startsWith(SUBTITLE_TRACK_PREFIX) ->
                    engine.setProperty("sid", track.id.removePrefix(SUBTITLE_TRACK_PREFIX))
                else -> return TrackSelectionResult.NotFound(track.id)
            }
            TrackSelectionResult.Selected(track.id)
        } catch (_: Throwable) {
            TrackSelectionResult.Failed("libmpv rejected the subtitle-track selection.")
        }
    }

    override fun validateExternalSubtitle(track: SubtitleTrack) {
        require(!usesVerifiedBundledRuntime || track.src.isLocalMpvSource()) {
            "The verified KMediaMpv runtime accepts local subtitle paths or file: URIs only."
        }
    }

    override fun removeBackendExternalSubtitle(track: SubtitleTrack) {
        findMpvTrackId(type = "sub", externalFilename = track.src)?.let { id ->
            runCatching { engine.command("sub-remove", id) }
        }
    }

    override fun disableBackendSubtitles(): TrackSelectionResult =
        try {
            engine.setProperty("sid", "no")
            TrackSelectionResult.Disabled
        } catch (_: Throwable) {
            TrackSelectionResult.Failed("libmpv could not disable subtitles.")
        }

    override fun releaseSource() {
        ensureOpen()
        runCatching { engine.command("stop") }
        nativeMacDecodeReady = false
        awaitingNativeMacDecodeRestart = false
        resumePlaybackAfterNativeSurfaceAttach = false
        frameState.value = null
        resetSourceState()
    }

    internal val currentFrame: State<ImageBitmap?> get() = frameState

    internal fun renderFrame(
        width: Int,
        height: Int,
    ) {
        if (disposed.get() || !_hasMedia || width <= 0 || height <= 0) return
        try {
            renderLock.withLock {
                if (disposed.get() || !_hasMedia) return
                if (nativeMacRenderer != 0L) {
                    // A headless consumer or a failed native-view attachment can still request the
                    // software frame API. The eagerly-created macOS renderer owns mpv's only render
                    // context, so hand it back before rendering instead of leaving playback paused
                    // forever with neither a mounted NSView nor a software frame.
                    if (nativeMacSurfaceAttached) return
                    nativeMacDecodeReady = false
                    awaitingNativeMacDecodeRestart = false
                    resumePlaybackAfterNativeSurfaceAttach = false
                    detachNativeMacRendererLocked(restoreSoftwareRenderer = true)
                }
                val target = framePool.next(width, height)
                engine.render(
                    width = target.width,
                    height = target.height,
                    rowBytes = target.rowBytes,
                    pixelsAddress = target.pixelsAddress,
                )
                mutateSnapshotState {
                    frameState.value = target.bitmap.asComposeImageBitmap()
                }
            }
        } catch (_: Exception) {
            if (!disposed.get()) {
                mutateSnapshotState { _hasMedia = false }
                publishError(VideoPlayerError.UnknownError("libmpv software rendering failed."))
            }
        }
    }

    internal fun setCropMode(crop: Boolean) {
        cropMode = crop
        if (!disposed.get()) applyNativeMacInputGeometry()
    }

    /** Pushes the public projection model into the native libmpv OpenGL post-process pass. */
    internal fun updateNativeMacProjection() {
        if (disposed.get()) return
        val configuration =
            mpvMacProjectionConfiguration(
                projection = projection,
                projectionView = projectionView,
                textureCrop = projectionTextureCrop,
            )
        nativeMacProjectionEnabled = configuration.enabled
        applyNativeMacInputGeometry()
        renderLock.withLock {
            if (disposed.get() || nativeMacRenderer == 0L) return@withLock
            MpvMacNativeBridge.nSetProjection(
                nativeRenderer = nativeMacRenderer,
                parameters = configuration.toNativeArray(),
            )
        }
        mutateSnapshotState {
            renderingInfo.videoProjection = projection.renderingInfoLabel()
        }
    }

    /** Creates the renderer-owned `NSView*` that Nucleus Tao mounts in its native hierarchy. */
    internal fun createNativeMacView(): Long {
        if (!canUseNativeMacSurface) return 0L
        return renderLock.withLock {
            if (disposed.get()) return@withLock 0L
            runCatching {
                MpvMacNativeBridge.nGetViewHandle(nativeMacRenderer)
            }.getOrDefault(0L)
        }
    }

    /**
     * libmpv requires its render context to exist before loading media creates the video output.
     * Creating it lazily from the Compose surface meant the first seek had to rebuild the video
     * chain before native playback became usable, and left the initial chain with uneven pacing.
     */
    private fun initializeNativeMacRendererBeforePlayback() {
        if (!MpvMacNativeBridge.isAvailable || disposed.get()) return
        renderLock.withLock {
            if (nativeMacRenderer != 0L || disposed.get()) return
            val target =
                try {
                    engine.beginExternalRendering()
                } catch (_: RuntimeException) {
                    return
                }
            val renderer =
                try {
                    MpvMacNativeBridge.nCreateRenderer(
                        mpvHandle = target.mpvHandle,
                        libraryLoadName = target.libraryLoadName,
                        colorMode = nativeMacColorMode.nativeValue,
                    )
                } catch (_: Throwable) {
                    0L
                }
            val nativeView =
                if (renderer != 0L) {
                    runCatching { MpvMacNativeBridge.nGetViewHandle(renderer) }.getOrDefault(0L)
                } else {
                    0L
                }
            if (renderer == 0L || nativeView == 0L) {
                if (renderer != 0L) runCatching { MpvMacNativeBridge.nDetach(renderer) }
                runCatching { engine.endExternalRendering(restoreSoftwareRenderer = true) }
                runCatching { engine.setProperty("hwdec", SOFTWARE_RENDER_HWDEC) }
                return
            }

            nativeMacRenderer = renderer
            frameState.value = null
            applyNativeMacOutputColorMode(nativeMacColorMode)
            val projectionConfiguration =
                mpvMacProjectionConfiguration(
                    projection = projection,
                    projectionView = projectionView,
                    textureCrop = projectionTextureCrop,
                )
            nativeMacProjectionEnabled = projectionConfiguration.enabled
            applyNativeMacInputGeometry()
            MpvMacNativeBridge.nSetProjection(
                nativeRenderer = nativeMacRenderer,
                parameters = projectionConfiguration.toNativeArray(),
            )
            configureNativeMacPlaybackProfile()
            runCatching { engine.setProperty("hwdec", nativeMacDecodeRoute.mpvHwdec) }
            mutateSnapshotState {
                renderingInfo.videoRenderer = "libmpv OpenGL in a native macOS EDR surface"
                renderingInfo.notes = nativeMacRenderingNotes()
            }
        }
    }

    internal fun disposeNativeMacView(
        @Suppress("UNUSED_PARAMETER") nativeView: Long,
    ) {
        nativeMacSurfaceAttached = false
        nativeMacDecodeReady = false
        awaitingNativeMacDecodeRestart = false
        resumePlaybackAfterNativeSurfaceAttach = false

        // Nucleus invokes this callback inside its native-view interop transaction. Blocking that
        // transaction on renderLock can deadlock with session disposal: dispose() owns renderLock
        // while nDetach synchronously enters the AppKit main queue, and Tao cannot finish the frame
        // that lets the main queue advance until this callback returns. Retire a still-live renderer
        // after leaving the interop transaction; a concurrently disposed state owns the teardown.
        if (disposed.get()) return
        scope.launch {
            if (disposed.get()) return@launch
            renderLock.withLock {
                if (disposed.get()) return@withLock
                detachNativeMacRendererLocked(restoreSoftwareRenderer = true)
            }
        }
    }

    internal fun onNativeMacSurfaceAttached() {
        renderLock.withLock {
            if (disposed.get() || nativeMacRenderer == 0L) return
            nativeMacSurfaceAttached = true
            runCatching { MpvMacNativeBridge.nGetDisplayRefreshRate(nativeMacRenderer) }
                .getOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { refreshRate ->
                    runCatching {
                        engine.setProperty("display-fps-override", refreshRate.toString())
                    }
                }
            MpvMacNativeBridge.nRequestRedraw(nativeMacRenderer)
            resumeNativeMacPlaybackIfReady()
        }
    }

    private fun detachNativeMacRendererLocked(restoreSoftwareRenderer: Boolean) {
        val renderer = nativeMacRenderer
        if (renderer == 0L) return
        val selectedVideo =
            if (restoreSoftwareRenderer) {
                suspendVideoOutputForContextSwitch()
            } else {
                null
            }
        nativeMacRenderer = 0L
        applyNativeMacInputGeometry()
        try {
            MpvMacNativeBridge.nDetach(renderer)
        } finally {
            engine.endExternalRendering(restoreSoftwareRenderer)
        }
        if (restoreSoftwareRenderer) {
            resetNativeMacTargetColorProperties()
            resumeVideoOutputAfterContextSwitch(selectedVideo, SOFTWARE_RENDER_HWDEC)
            mutateSnapshotState {
                renderingInfo.videoRenderer = "libmpv software render API"
                renderingInfo.notes = softwareRenderingNotes()
            }
        }
    }

    private suspend fun eventLoop() {
        try {
            while (scope.isActive && !disposed.get()) {
                when (val event = engine.waitEvent(EVENT_WAIT_SECONDS)) {
                    NativeMpvEvent.None -> Unit
                    NativeMpvEvent.Shutdown -> return
                    NativeMpvEvent.FileLoaded -> onFileLoaded()
                    is NativeMpvEvent.EndFile -> onEndFile(event)
                    NativeMpvEvent.SeekStarted -> mutateSnapshotState { _isSeeking = true }
                    NativeMpvEvent.PlaybackRestarted -> onPlaybackRestarted()
                }
                refreshSnapshot()
                val now = System.currentTimeMillis()
                if (_hasMedia && now - lastTrackRefreshMs >= TRACK_REFRESH_INTERVAL_MS) {
                    refreshTracks()
                    refreshChapters()
                    lastTrackRefreshMs = now
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            if (!disposed.get()) publishError(VideoPlayerError.UnknownError("libmpv event processing failed."))
        }
    }

    private fun onFileLoaded() {
        refreshSnapshot()
        val decodeRestartRequested = configureNativeMacDecodeRouteForLoadedSource()
        refreshSnapshot()
        refreshTracks()
        refreshChapters()
        if (decodeRestartRequested) {
            mutateSnapshotState { _isLoading = true }
        } else {
            completeNativeMacSourceConfiguration()
        }
    }

    private fun onEndFile(event: NativeMpvEvent.EndFile) {
        if (!_hasMedia || event.reason == MPV_END_FILE_REASON_STOP || event.reason == MPV_END_FILE_REASON_QUIT) return
        if ((event.reason == MPV_END_FILE_REASON_ERROR || event.errorCode < 0) &&
            System.nanoTime() < renderContextSwitchDeadlineNanos
        ) {
            return
        }
        if (event.reason == MPV_END_FILE_REASON_ERROR || event.errorCode < 0) {
            mutateSnapshotState {
                _hasMedia = false
            }
            publishError(VideoPlayerError.SourceError("libmpv could not finish the media source."))
            return
        }
        emitPlaybackEnded()
    }

    private fun onPlaybackRestarted() {
        renderContextSwitchDeadlineNanos = 0L
        if (awaitingNativeMacDecodeRestart) {
            awaitingNativeMacDecodeRestart = false
            mutateSnapshotState { _isSeeking = false }
            completeNativeMacSourceConfiguration()
            return
        }
        val completedSeek = _isSeeking
        _isLoading = false
        if (completedSeek) {
            emitSeekCompleted()
        } else {
            _isSeeking = false
        }
    }

    private fun refreshSnapshot() {
        if (!_hasMedia || disposed.get()) return
        val position = engine.getProperty("time-pos").secondsOrNull() ?: _currentTime
        val mediaDuration = engine.getProperty("duration").secondsOrNull() ?: _duration
        val paused = engine.getProperty("pause").yesNoOrNull() ?: !_isPlaying
        val buffering =
            (engine.getProperty("paused-for-cache").yesNoOrNull() ?: false) ||
                awaitingNativeMacDecodeRestart ||
                (nativeMacRenderer != 0L && !nativeMacDecodeReady)
        val seeking = engine.getProperty("seeking").yesNoOrNull() ?: _isSeeking
        val width = engine.getProperty("video-params/w").positiveIntOrNull()
        val height = engine.getProperty("video-params/h").positiveIntOrNull()
        val title = engine.getProperty("media-title")
        val frameRate = engine.getProperty("container-fps").positiveFloatOrNull()
        val container = engine.getProperty("file-format")
        val videoDecoder = engine.getProperty("video-codec")
        val hardwareDecoder =
            engine
                .getProperty("hwdec-current")
                ?.takeUnless { it.equals("no", ignoreCase = true) }
        val audioDecoder = engine.getProperty("audio-codec-name")
        val audioOutput = engine.getProperty("current-ao")
        val audioChannels = engine.getProperty("audio-params/channel-count").positiveIntOrNull()
        val audioSampleRate = engine.getProperty("audio-params/samplerate").positiveIntOrNull()
        val audioOutputChannels = engine.getProperty("audio-out-params/channel-count").positiveIntOrNull()
        val audioOutputSampleRate = engine.getProperty("audio-out-params/samplerate").positiveIntOrNull()
        val audioOutputLayout = engine.getProperty("audio-out-params/hr-channels")
        val transfer = engine.getProperty("video-params/gamma")
        val decoderDroppedFrames = engine.getProperty("decoder-frame-drop-count").nonNegativeLongOrNull()
        val outputDroppedFrames = engine.getProperty("frame-drop-count").nonNegativeLongOrNull()
        droppedVideoFrames =
            when {
                decoderDroppedFrames != null && outputDroppedFrames != null ->
                    decoderDroppedFrames + outputDroppedFrames
                else -> decoderDroppedFrames ?: outputDroppedFrames
            }
        mpvTimingNotes =
            " decoderDrops=$decoderDroppedFrames outputDrops=$outputDroppedFrames" +
            " decodeRoute=${nativeMacDecodeRoute.diagnosticLabel}" +
            " framedrop=${engine.getProperty("framedrop")}" +
            " directRender=${engine.getProperty("vd-lavc-dr")}" +
            " videoSync=${engine.getProperty("video-sync")}" +
            " displayFps=${engine.getProperty("display-fps")}" +
            " mistimed=${engine.getProperty("mistimed-frame-count")}" +
            " delayed=${engine.getProperty("vo-delayed-frame-count")}" +
            " avsync=${engine.getProperty("avsync")}"
        engine
            .getProperty("avsync")
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
            ?.let { seconds ->
                val absoluteMilliseconds = (kotlin.math.abs(seconds) * MILLIS_PER_SECOND).toFloat()
                maximumAvSyncOffsetMs = maxOf(maximumAvSyncOffsetMs ?: 0f, absoluteMilliseconds)
            }

        updateNativeMacOutputColorMode(transfer.toMacOutputColorMode())

        updatePlaybackPosition(
            position = position,
            mediaDuration = mediaDuration,
            playing = !paused,
            loading = buffering,
            seeking = seeking,
        )
        mutateSnapshotState {
            metadata.title = title
            metadata.width = width
            metadata.height = height
            metadata.frameRate = frameRate
            metadata.audioChannels = audioChannels
            metadata.audioSampleRate = audioSampleRate
            renderingInfo.container = container
            renderingInfo.videoDecoder =
                when {
                    videoDecoder != null && hardwareDecoder != null -> "$videoDecoder via $hardwareDecoder"
                    else -> videoDecoder ?: hardwareDecoder
                }
            renderingInfo.audioRenderer =
                when {
                    audioDecoder != null && audioOutput != null ->
                        "$audioDecoder via $audioOutput" +
                            audioOutputDetails(
                                channels = audioOutputChannels,
                                sampleRate = audioOutputSampleRate,
                                layout = audioOutputLayout,
                            )
                    audioDecoder != null -> audioDecoder
                    audioOutput != null -> audioOutput
                    else -> "mpv audio output"
                }
        }
        updateAspectRatio(width, height)
    }

    private fun audioOutputDetails(
        channels: Int?,
        sampleRate: Int?,
        layout: String?,
    ): String {
        val details =
            buildList {
                layout?.takeIf(String::isNotBlank)?.let(::add)
                if (layout.isNullOrBlank()) channels?.let { add("$it ch") }
                sampleRate?.let { add("$it Hz") }
            }
        return details.takeIf(List<String>::isNotEmpty)?.joinToString(prefix = " (", postfix = ")") ?: ""
    }

    private fun refreshTracks() {
        if (disposed.get()) return
        val count = engine.getProperty("track-list/count").positiveIntOrNull(allowZero = true) ?: return
        val discoveredAudio = mutableListOf<AudioTrack>()
        val discoveredSubtitles = mutableListOf<SubtitleTrack>()
        var selectedAudio: AudioTrack? = null
        var selectedSubtitle: SubtitleTrack? = null

        repeat(count) { index ->
            val base = "track-list/$index"
            val type = engine.getProperty("$base/type") ?: return@repeat
            val id = engine.getProperty("$base/id") ?: return@repeat
            val title = engine.getProperty("$base/title").orEmpty()
            val language = engine.getProperty("$base/lang").orEmpty()
            val selected = engine.getProperty("$base/selected").yesNoOrNull() == true
            when (type) {
                "audio" -> {
                    val track =
                        AudioTrack(
                            id = "$AUDIO_TRACK_PREFIX$id",
                            label = title.ifBlank { language.ifBlank { "Audio $id" } },
                            language = language,
                            channels = engine.getProperty("$base/demux-channel-count").positiveIntOrNull(),
                            sampleRate = engine.getProperty("$base/demux-samplerate").positiveIntOrNull(),
                            bitrate = engine.getProperty("$base/demux-bitrate").positiveIntOrNull(),
                            isDefault = engine.getProperty("$base/default").yesNoOrNull() == true,
                        )
                    discoveredAudio += track
                    if (selected) selectedAudio = track
                }
                "sub" -> {
                    val external = engine.getProperty("$base/external").yesNoOrNull() == true
                    val filename = engine.getProperty("$base/external-filename")
                    val registered =
                        filename?.let { path -> registeredExternalSubtitles.values.firstOrNull { it.src == path } }
                    val track =
                        registered ?: SubtitleTrack(
                            id = "$SUBTITLE_TRACK_PREFIX$id",
                            label = title.ifBlank { language.ifBlank { "Subtitle $id" } },
                            language = language,
                            src = filename ?: "mpv://subtitle/$id",
                            isEmbedded = !external,
                        )
                    discoveredSubtitles += track
                    if (selected) selectedSubtitle = track
                }
            }
        }

        replaceDiscoveredTracks(
            discoveredAudio = discoveredAudio,
            discoveredSubtitles = discoveredSubtitles,
            selectedAudio = selectedAudio,
            selectedSubtitle = selectedSubtitle,
        )
    }

    private fun refreshChapters() {
        if (disposed.get()) return
        val count = engine.getProperty("chapter-list/count").positiveIntOrNull(allowZero = true) ?: return
        val discovered =
            buildList {
                repeat(count) { index ->
                    val base = "chapter-list/$index"
                    val start =
                        engine
                            .getProperty("$base/time")
                            ?.toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                            ?.seconds
                            ?: return@repeat
                    add(
                        start to
                            engine
                                .getProperty("$base/title")
                                ?.trim()
                                ?.takeIf(String::isNotEmpty),
                    )
                }
            }.distinct()
                .sortedBy(Pair<Duration, String?>::first)

        replaceDiscoveredChapters(
            discovered.mapIndexed { index, (start, title) ->
                val end =
                    discovered
                        .asSequence()
                        .drop(index + 1)
                        .map(Pair<Duration, String?>::first)
                        .firstOrNull { it > start }
                        ?: _duration.takeIf { it.isFinite() && it > start }
                MediaChapter(
                    start = start,
                    end = end,
                    title = title,
                )
            },
        )
    }

    private fun findMpvTrackId(
        type: String,
        externalFilename: String,
    ): String? {
        val count = engine.getProperty("track-list/count").positiveIntOrNull(allowZero = true) ?: return null
        repeat(count) { index ->
            val base = "track-list/$index"
            if (engine.getProperty("$base/type") == type &&
                engine.getProperty("$base/external-filename") == externalFilename
            ) {
                return engine.getProperty("$base/id")
            }
        }
        return null
    }

    private val usesVerifiedBundledRuntime: Boolean
        get() = runtimeConfig.librarySource == MpvLibrarySource.Bundled

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        runCatching { engine.command("stop") }
        engine.wakeup()
        runBlocking {
            withTimeoutOrNull(DISPOSE_TIMEOUT_MS) {
                eventJob.cancelAndJoin()
            }
        }
        renderLock.withLock {
            detachNativeMacRendererLocked(restoreSoftwareRenderer = false)
            frameState.value = null
            framePool.close()
            engine.close()
        }
        scope.cancel()
    }

    private fun updateNativeMacOutputColorMode(mode: MpvMacOutputColorMode) {
        if (nativeMacColorMode == mode) return
        nativeMacColorMode = mode
        renderLock.withLock {
            if (nativeMacRenderer != 0L) applyNativeMacOutputColorMode(mode)
        }
    }

    private fun applyNativeMacOutputColorMode(mode: MpvMacOutputColorMode) {
        when (mode) {
            MpvMacOutputColorMode.SDR -> resetNativeMacTargetColorProperties()
            MpvMacOutputColorMode.BT2100_PQ -> {
                runCatching { engine.setProperty("target-prim", "bt.2020") }
                runCatching { engine.setProperty("target-trc", "pq") }
            }
            MpvMacOutputColorMode.BT2100_HLG -> {
                runCatching { engine.setProperty("target-prim", "bt.2020") }
                runCatching { engine.setProperty("target-trc", "hlg") }
            }
        }
        MpvMacNativeBridge.nSetColorMode(nativeMacRenderer, mode.nativeValue)
    }

    private fun resetNativeMacTargetColorProperties() {
        runCatching { engine.setProperty("target-prim", "auto") }
        runCatching { engine.setProperty("target-trc", "auto") }
    }

    private fun applyNativeMacInputGeometry() {
        val geometry =
            mpvMacInputGeometry(
                projectionEnabled = nativeMacRenderer != 0L && nativeMacProjectionEnabled,
                crop = cropMode,
            )
        runCatching { engine.setProperty("keepaspect", geometry.keepAspect) }
        runCatching { engine.setProperty("panscan", geometry.panscan) }
    }

    /** Rebuilds mpv's video chain after replacing its sole render context. */
    private fun suspendVideoOutputForContextSwitch(): String? {
        if (!_hasMedia) return null
        renderContextSwitchDeadlineNanos =
            System.nanoTime() + RENDER_CONTEXT_SWITCH_GRACE_NANOS
        val selectedVideo =
            engine
                .getProperty("vid")
                ?.takeUnless { it.equals("no", ignoreCase = true) }
                ?: "auto"
        runCatching { engine.setProperty("vid", "no") }
        return selectedVideo
    }

    private fun resumeVideoOutputAfterContextSwitch(
        selectedVideo: String?,
        hwdec: String,
    ) {
        runCatching { engine.setProperty("hwdec", hwdec) }
        selectedVideo?.let { video -> runCatching { engine.setProperty("vid", video) } }
    }

    private fun nativeMacRenderingNotes(): String =
        buildString {
            if (usesVerifiedBundledRuntime) {
                append("Verified KMediaMpv runtime with automatic pipelined VideoToolbox; ")
            } else {
                append("User-provided libmpv; runtime license is unverified; ")
            }
            append("native macOS OpenGL/EDR output with Compose controls; ")
            append(nativeMacDecodeRoute.description)
            append('.')
        }

    private fun resetNativeMacDecodeRouteBeforeSourceLoad() {
        if (nativeMacRenderer == 0L || nativeMacDecodeRoute == MpvMacDecodeRoute.HARDWARE_AUTO) return
        nativeMacDecodeRoute = MpvMacDecodeRoute.HARDWARE_AUTO
        runCatching { engine.setProperty("hwdec", nativeMacDecodeRoute.mpvHwdec) }
        mutateSnapshotState { renderingInfo.notes = nativeMacRenderingNotes() }
    }

    private fun configureNativeMacDecodeRouteForLoadedSource(): Boolean {
        if (nativeMacRenderer == 0L || disposed.get()) return false
        val selectedRoute =
            selectMpvMacDecodeRoute(
                videoFormat = engine.getProperty("video-format"),
                videoCodec = engine.getProperty("video-codec"),
                width = engine.getProperty("video-params/w").positiveIntOrNull(),
                height = engine.getProperty("video-params/h").positiveIntOrNull(),
                frameRate = engine.getProperty("container-fps").positiveFloatOrNull(),
                transfer = engine.getProperty("video-params/gamma"),
                pipelinedVideoToolboxAvailable = usesVerifiedBundledRuntime,
            )
        if (selectedRoute == nativeMacDecodeRoute) return false

        var switched = false
        renderLock.withLock {
            if (nativeMacRenderer == 0L || disposed.get() || selectedRoute == nativeMacDecodeRoute) {
                return@withLock
            }
            val restartPosition = engine.getProperty("time-pos").secondsOrNull() ?: Duration.ZERO
            runCatching { engine.setProperty("pause", "yes") }
            val selectedVideo = suspendVideoOutputForContextSwitch()
            nativeMacDecodeRoute = selectedRoute
            nativeMacDecodeReady = false
            awaitingNativeMacDecodeRestart = true
            resumeVideoOutputAfterContextSwitch(selectedVideo, selectedRoute.mpvHwdec)
            runCatching {
                engine.command(
                    "seek",
                    restartPosition.inWholeMilliseconds
                        .toDouble()
                        .div(MILLIS_PER_SECOND)
                        .toString(),
                    "absolute+keyframes",
                )
            }
            switched = true
        }
        mutateSnapshotState { renderingInfo.notes = nativeMacRenderingNotes() }
        return switched
    }

    private fun completeNativeMacSourceConfiguration() {
        nativeMacDecodeReady = true
        sourceLoaded()
        resumeNativeMacPlaybackIfReady()
    }

    private fun resumeNativeMacPlaybackIfReady() {
        if (!resumePlaybackAfterNativeSurfaceAttach ||
            !nativeMacDecodeReady ||
            !nativeMacSurfaceAttached ||
            nativeMacRenderer == 0L
        ) {
            return
        }
        resumePlaybackAfterNativeSurfaceAttach = false
        runCatching { engine.setProperty("pause", "no") }
    }

    private fun configureNativeMacPlaybackProfile() {
        mapOf(
            "framedrop" to "decoder+vo",
            "vd-lavc-framedrop" to "nonref",
            "video-sync" to "audio",
            "interpolation" to "no",
            "hwdec-extra-frames" to "8",
        ).forEach { (name, value) ->
            runCatching { engine.setProperty(name, value) }
        }
    }

    private fun softwareRenderingNotes(): String =
        if (usesVerifiedBundledRuntime) {
            "Verified KMediaMpv runtime with credential-safe loopback HLS input."
        } else {
            "User-provided libmpv; native runtime license is unverified."
        }

    companion object {
        private const val MPV_VOLUME_SCALE = 100f
        private const val EVENT_WAIT_SECONDS = 0.05
        private const val TRACK_REFRESH_INTERVAL_MS = 1_000L
        private const val DISPOSE_TIMEOUT_MS = 2_000L
        private const val RENDER_CONTEXT_SWITCH_GRACE_NANOS = 2_000_000_000L
        private const val MILLIS_PER_SECOND = 1_000.0
        private const val NEW_VIDEO_FRAME_COUNT_INDEX = 4
        private const val SOFTWARE_RENDER_HWDEC = "no"
        private const val MPV_END_FILE_REASON_STOP = 2
        private const val MPV_END_FILE_REASON_QUIT = 3
        private const val MPV_END_FILE_REASON_ERROR = 4
        private const val AUDIO_TRACK_PREFIX = "mpv:audio:"
        private const val SUBTITLE_TRACK_PREFIX = "mpv:subtitle:"
    }
}

internal enum class MpvMacDecodeRoute(
    val mpvHwdec: String,
    val diagnosticLabel: String,
    val description: String,
) {
    HARDWARE_AUTO(
        mpvHwdec = "auto-safe",
        diagnosticLabel = "hardware-auto",
        description = "automatic safe hardware decoding",
    ),
    SOFTWARE_HIGH_THROUGHPUT_HEVC(
        mpvHwdec = "no",
        diagnosticLabel = "software-heavy-hevc",
        description = "adaptive software decoding for high-throughput SDR HEVC",
    ),
}

/**
 * FFmpeg's VideoToolbox interop can fall below real time for SDR HEVC streams around two
 * gigapixels/second even when the same host can decode them in software. KMediaMpv rc.8 replaces
 * that path with its pipelined decoder, so the software workaround is retained only for external
 * runtimes that do not carry the bundled decoder.
 */
internal fun selectMpvMacDecodeRoute(
    videoFormat: String?,
    videoCodec: String?,
    width: Int?,
    height: Int?,
    frameRate: Float?,
    transfer: String?,
    pipelinedVideoToolboxAvailable: Boolean,
): MpvMacDecodeRoute {
    if (pipelinedVideoToolboxAvailable) return MpvMacDecodeRoute.HARDWARE_AUTO
    val codecDescription = listOfNotNull(videoFormat, videoCodec).joinToString(" ").lowercase()
    val isHevc = "hevc" in codecDescription || "h.265" in codecDescription || "h265" in codecDescription
    val isHdrTransfer =
        transfer?.lowercase() in setOf("pq", "st2084", "smpte2084", "hlg", "arib-std-b67")
    val validWidth = width?.takeIf { it > 0 } ?: return MpvMacDecodeRoute.HARDWARE_AUTO
    val validHeight = height?.takeIf { it > 0 } ?: return MpvMacDecodeRoute.HARDWARE_AUTO
    val validFrameRate =
        frameRate?.takeIf { it.isFinite() && it > 0f }
            ?: return MpvMacDecodeRoute.HARDWARE_AUTO
    val pixelRate = validWidth.toDouble() * validHeight.toDouble() * validFrameRate.toDouble()
    return if (isHevc && !isHdrTransfer && pixelRate >= SOFTWARE_HEVC_PIXEL_RATE_THRESHOLD) {
        MpvMacDecodeRoute.SOFTWARE_HIGH_THROUGHPUT_HEVC
    } else {
        MpvMacDecodeRoute.HARDWARE_AUTO
    }
}

private const val SOFTWARE_HEVC_PIXEL_RATE_THRESHOLD = 1_500_000_000.0

private fun String?.toMacOutputColorMode(): MpvMacOutputColorMode =
    when (this?.lowercase()) {
        "pq", "st2084", "smpte2084" -> MpvMacOutputColorMode.BT2100_PQ
        "hlg", "arib-std-b67" -> MpvMacOutputColorMode.BT2100_HLG
        else -> MpvMacOutputColorMode.SDR
    }

internal fun mpvInitializationOptions(config: MpvRuntimeConfig): Map<String, String> {
    val subtitleFontsDirectory =
        config.subtitleFontsDirectory?.let { directory ->
            require(
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(directory),
            ) {
                "subtitleFontsDirectory must identify an existing, non-symbolic-link directory."
            }
            directory.normalize().toString()
        }

    return buildMap {
        put("vo", "libmpv")
        put("config", "no")
        put("load-scripts", "no")
        put("input-default-bindings", "no")
        put("input-vo-keyboard", "no")
        put("hwdec", "no")
        put("framedrop", "vo")
        put("keep-open", "yes")
        put("sub-ass-override", if (config.preserveAssStyles) "no" else "strip")
        put("embeddedfonts", if (config.useEmbeddedFonts) "yes" else "no")
        subtitleFontsDirectory?.let { put("sub-fonts-dir", it) }
    }
}

internal fun createDesktopMpvVideoPlayerState(config: MpvRuntimeConfig): MpvVideoPlayerState {
    val resolved =
        try {
            resolveMpvRuntime(config)
        } catch (failure: MpvRuntimeResolutionFailure) {
            throw MpvBackendUnavailableException(
                availability =
                    MpvBackendAvailability.Unavailable(
                        reason = failure.reason.toPublicReason(),
                        guidance = failure.guidance,
                    ),
                cause = failure,
            )
        }
    val options = mpvInitializationOptions(config) + resolved.requiredOptions
    val library =
        try {
            LibMpvLibrary.open(resolved.librarySource)
        } catch (failure: MpvLoadFailure) {
            throw MpvBackendUnavailableException(
                availability =
                    MpvBackendAvailability.Unavailable(
                        reason = failure.reason.toPublicReason(),
                        guidance = failure.guidance,
                    ),
                cause = failure,
            )
        }
    val engine =
        try {
            library.createEngine(options)
        } catch (failure: MpvLoadFailure) {
            library.close()
            throw MpvBackendUnavailableException(
                availability =
                    MpvBackendAvailability.Unavailable(
                        reason = failure.reason.toPublicReason(),
                        guidance = failure.guidance,
                    ),
                cause = failure,
            )
        } catch (failure: RuntimeException) {
            library.close()
            throw MpvBackendUnavailableException(
                availability =
                    MpvBackendAvailability.Unavailable(
                        reason = MpvBackendUnavailableReason.INITIALIZATION_FAILED,
                        guidance =
                            "The verified libmpv runtime could not initialize the software backend.",
                    ),
                cause = failure,
            )
        }
    return try {
        MpvVideoPlayerState(config, engine)
    } catch (failure: RuntimeException) {
        engine.close()
        throw failure
    }
}

private fun MpvUnavailableReason.toPublicReason(): MpvBackendUnavailableReason =
    when (this) {
        MpvUnavailableReason.RUNTIME_DEPENDENCY_MISSING ->
            MpvBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING
        MpvUnavailableReason.UNSUPPORTED_PLATFORM ->
            MpvBackendUnavailableReason.UNSUPPORTED_PLATFORM
        MpvUnavailableReason.BUNDLED_RUNTIME_REJECTED,
        MpvUnavailableReason.NATIVE_ACCESS_DISABLED,
        MpvUnavailableReason.LIBRARY_NOT_FOUND,
        MpvUnavailableReason.REQUIRED_SYMBOL_MISSING,
        MpvUnavailableReason.INCOMPATIBLE_CLIENT_API,
        MpvUnavailableReason.LOAD_FAILED,
        -> MpvBackendUnavailableReason.INVALID_RUNTIME
    }

private class MpvFramePool(
    private val maxRenderPixels: Int,
) : AutoCloseable {
    private val active = arrayOfNulls<Bitmap>(3)
    private val retired = ArrayDeque<RetiredBitmap>()
    private var width = 0
    private var height = 0
    private var nextIndex = 0

    fun next(
        requestedWidth: Int,
        requestedHeight: Int,
    ): FrameTarget {
        val (renderWidth, renderHeight) = constrainSize(requestedWidth, requestedHeight)
        if (renderWidth != width || renderHeight != height || active.any { it == null }) {
            active.forEach { bitmap -> bitmap?.let { retired += RetiredBitmap(it, RETIRE_GRACE_FRAMES) } }
            val imageInfo = ImageInfo(renderWidth, renderHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
            active.indices.forEach { index -> active[index] = Bitmap().apply { allocPixels(imageInfo) } }
            width = renderWidth
            height = renderHeight
            nextIndex = 0
        }
        drainRetired()
        val bitmap = requireNotNull(active[nextIndex])
        nextIndex = (nextIndex + 1) % active.size
        val pixmap = requireNotNull(bitmap.peekPixels()) { "Skia did not expose the mpv render target." }
        require(pixmap.addr != 0L) { "Skia returned a null mpv render target." }
        return FrameTarget(
            bitmap = bitmap,
            width = width,
            height = height,
            rowBytes = pixmap.rowBytes.toLong(),
            pixelsAddress = pixmap.addr,
        )
    }

    private fun constrainSize(
        requestedWidth: Int,
        requestedHeight: Int,
    ): Pair<Int, Int> {
        val pixels = requestedWidth.toLong() * requestedHeight.toLong()
        if (pixels <= maxRenderPixels.toLong()) return requestedWidth to requestedHeight
        val scale = sqrt(maxRenderPixels.toDouble() / pixels.toDouble())
        return (requestedWidth * scale).toInt().coerceAtLeast(1) to
            (requestedHeight * scale).toInt().coerceAtLeast(1)
    }

    private fun drainRetired() {
        repeat(retired.size) {
            val item = retired.removeFirst()
            if (item.framesRemaining <=
                1
            ) {
                item.bitmap.close()
            } else {
                retired += item.copy(framesRemaining = item.framesRemaining - 1)
            }
        }
    }

    override fun close() {
        active.indices.forEach { index ->
            active[index]?.close()
            active[index] = null
        }
        while (retired.isNotEmpty()) retired.removeFirst().bitmap.close()
    }

    private data class RetiredBitmap(
        val bitmap: Bitmap,
        val framesRemaining: Int,
    )

    companion object {
        private const val RETIRE_GRACE_FRAMES = 6
    }
}

private data class FrameTarget(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val rowBytes: Long,
    val pixelsAddress: Long,
)

private fun String?.secondsOrNull(): Duration? = this?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.seconds

private fun String?.yesNoOrNull(): Boolean? =
    when (this?.lowercase()) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }

private fun String?.positiveIntOrNull(allowZero: Boolean = false): Int? =
    this?.toIntOrNull()?.takeIf { if (allowZero) it >= 0 else it > 0 }

private fun String?.positiveFloatOrNull(): Float? = this?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

private fun String?.nonNegativeLongOrNull(): Long? = this?.toLongOrNull()?.takeIf { it >= 0L }

internal fun String.isLocalMpvSource(): Boolean {
    val separator = indexOf(':')
    if (separator <= 0) return true
    val candidate = substring(0, separator)
    if (!candidate.first().isLetter() ||
        candidate.any { !it.isLetterOrDigit() && it !in "+-." }
    ) {
        return true
    }
    return candidate.equals("file", ignoreCase = true)
}

internal fun String.isVerifiedBundledMpvSource(): Boolean = isLocalMpvSource() || isNumericLoopbackHttpMpvSource()

/**
 * Java's legacy `File.toURI()` emits valid local URIs such as `file:/movie.mp4`, while libmpv's
 * macOS input path is reliable with the canonical `file:///movie.mp4` spelling. Round-tripping
 * through [Path] also preserves percent-encoding without treating a URI as a literal file name.
 */
internal fun String.normalizedMpvMediaUri(): String {
    val parsed = runCatching { URI.create(this) }.getOrNull() ?: return this
    if (!parsed.scheme.equals("file", ignoreCase = true) || !parsed.authority.isNullOrEmpty()) return this
    return runCatching {
        Path.of(parsed).toAbsolutePath().normalize().toUri().toASCIIString()
    }.getOrDefault(this)
}

private fun String.isNumericLoopbackHttpMpvSource(): Boolean {
    val uri = runCatching { URI.create(this) }.getOrNull() ?: return false
    if (!uri.scheme.equals("http", ignoreCase = true) || uri.userInfo != null) return false
    if (uri.port !in 1..65535) return false
    val host = uri.host?.removeSurrounding("[", "]")
    return host == "127.0.0.1" || host == "::1"
}
