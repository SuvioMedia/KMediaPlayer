package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.nucleusframework.window.tao.LinuxTextureViewProducerInfo
import dev.nucleusframework.window.tao.NucleusDrmFormat
import dev.nucleusframework.window.tao.TextureColorEncoding
import dev.nucleusframework.window.tao.TextureColorInfo
import dev.nucleusframework.window.tao.TextureViewFrame
import dev.nucleusframework.window.tao.TextureViewHostCapabilities
import dev.nucleusframework.window.tao.TextureViewHostDynamicRange
import dev.nucleusframework.window.tao.TextureViewHostPixelFormat
import dev.nucleusframework.window.tao.TextureViewHostPresentationState
import dev.nucleusframework.window.tao.TextureViewStreamController
import dev.nucleusframework.window.tao.WindowsTextureViewProducerInfo
import dev.nucleusframework.window.tao.nucleusDmaBufTextureSource
import dev.nucleusframework.window.tao.nucleusIOSurfaceTextureSource
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.ColorPipelineRenderer
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.DecoderColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MediaChapter
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableException
import io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableReason
import io.github.kdroidfilter.composemediaplayer.PlaybackDiagnostics
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.RendererColorCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorMatrix
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoColorPrimaries
import io.github.kdroidfilter.composemediaplayer.VideoColorRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.VideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.desktop.TaoPlaybackSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.mpv.internal.LibMpvEngine
import io.github.kdroidfilter.composemediaplayer.mpv.internal.LibMpvLibrary
import io.github.kdroidfilter.composemediaplayer.mpv.internal.MpvLoadFailure
import io.github.kdroidfilter.composemediaplayer.mpv.internal.NativeMpvEvent
import io.github.kdroidfilter.composemediaplayer.renderingInfoLabel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    private val windowsTextureExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "KMediaPlayer-MPV-Windows-Texture").apply { isDaemon = true }
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventJob: Job
    private val frameState = mutableStateOf<ImageBitmap?>(null)
    private val framePool = MpvFramePool(runtimeConfig.maxRenderPixels)
    private val windowsTextureOutputState = mutableStateOf<MpvWindowsTextureOutput?>(null)
    internal val macTextureStreamController = TextureViewStreamController()
    internal val linuxTextureStreamController = TextureViewStreamController()
    private val mutableColorPipelineStatus =
        MutableStateFlow(
            VideoColorPipelineStatus(
                requestedDynamicRangePolicy = runtimeConfig.dynamicRangePolicy,
                requestedDolbyVisionPolicy = runtimeConfig.dolbyVisionPolicy,
                surface =
                    if (runtimeConfig.desktopVideoSurfaceMode == DesktopVideoSurfaceMode.COMPOSE) {
                        VideoSurfaceKind.COMPOSE_CANVAS
                    } else {
                        VideoSurfaceKind.TEXTURE_VIEW
                    },
            ),
        )

    @Volatile
    private var nativeWindowsRenderer = 0L

    @Volatile
    private var windowsTextureHostCapabilities = TextureViewHostCapabilities.UNAVAILABLE

    @Volatile
    private var windowsTextureProducerSerial = 0L

    @Volatile
    private var windowsTextureSubmittedSerial = 0L

    @Volatile
    private var windowsTextureSubmittedHostGeneration = 0L

    @Volatile
    private var windowsTextureSubmittedPresentCount = 0L

    @Volatile
    private var windowsTextureConfirmedSerial = 0L

    @Volatile
    private var windowsTextureConfirmedHostGeneration = 0L

    @Volatile
    private var sourceColorInfo = VideoColorInfo()

    private var windowsTextureAdapterLuid = 0L
    private var windowsTextureRendererExtendedLinear = false
    private var windowsTextureConfiguration: MpvWindowsTextureConfiguration? = null
    private var desktopTextureFailurePublished = false

    private fun <T> onWindowsTextureThread(block: () -> T): T =
        windowsTextureExecutor.submit<T> { block() }.get()

    @Volatile
    private var desktopTextureFailureDetail: String? = null

    @Volatile
    private var nativeMacRenderer = 0L
    private var nativeMacColorMode = MpvMacOutputColorMode.SDR
    private var macTextureConfiguration: MpvMacTextureConfiguration? = null
    private var nativeMacProjectionEnabled = false
    private var nativeMacContentScaleMode = MpvMacContentScaleMode.FIT

    @Volatile
    private var nativeMacDecodeRoute = MpvMacDecodeRoute.HARDWARE_AUTO

    @Volatile
    private var nativeMacSurfaceAttached = false

    @Volatile
    private var macTextureHostCapabilities = TextureViewHostCapabilities.UNAVAILABLE

    @Volatile
    private var macTextureProducerSerial = 0L

    @Volatile
    private var macTextureSubmittedSerial = 0L

    @Volatile
    private var macTextureSubmittedHostGeneration = 0L

    @Volatile
    private var macTextureSubmittedPresentCount = 0L

    @Volatile
    private var macTextureConfirmedSerial = 0L

    @Volatile
    private var macTextureConfirmedHostGeneration = 0L

    @Volatile
    private var nativeLinuxRenderer = 0L

    @Volatile
    private var linuxTextureHostCapabilities = TextureViewHostCapabilities.UNAVAILABLE

    private var linuxTextureConfiguration: MpvLinuxTextureConfiguration? = null

    @Volatile
    private var linuxTextureProducerSerial = 0L

    @Volatile
    private var linuxTextureSubmittedSerial = 0L

    @Volatile
    private var linuxTextureSubmittedHostGeneration = 0L

    @Volatile
    private var linuxTextureSubmittedPresentCount = 0L

    @Volatile
    private var linuxTextureConfirmedSerial = 0L

    @Volatile
    private var linuxTextureConfirmedHostGeneration = 0L

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

    internal val usesMacColorManagedTexture: Boolean
        get() =
            isMacHost() &&
                runtimeConfig.desktopVideoSurfaceMode != DesktopVideoSurfaceMode.COMPOSE &&
                !disposed.get() &&
                nativeMacRenderer != 0L

    internal val usesWindowsColorManagedTexture: Boolean
        get() =
            isWindowsHost() &&
                runtimeConfig.desktopVideoSurfaceMode != DesktopVideoSurfaceMode.COMPOSE

    internal val usesLinuxColorManagedTexture: Boolean
        get() =
            isLinuxHost() &&
                runtimeConfig.desktopVideoSurfaceMode != DesktopVideoSurfaceMode.COMPOSE

    internal val currentWindowsTextureOutput: State<MpvWindowsTextureOutput?>
        get() = windowsTextureOutputState

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

    override val colorPipelineStatus: StateFlow<VideoColorPipelineStatus> =
        mutableColorPipelineStatus.asStateFlow()

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
        windowsTextureProducerSerial = 0L
        windowsTextureSubmittedSerial = 0L
        windowsTextureConfirmedSerial = 0L
        desktopTextureFailurePublished = false
        desktopTextureFailureDetail = null
        windowsTextureOutputState.value = null
        macTextureStreamController.clear()
        macTextureProducerSerial = 0L
        macTextureSubmittedSerial = 0L
        macTextureSubmittedHostGeneration = 0L
        macTextureSubmittedPresentCount = 0L
        macTextureConfirmedSerial = 0L
        macTextureConfirmedHostGeneration = 0L
        linuxTextureStreamController.clear()
        linuxTextureProducerSerial = 0L
        linuxTextureSubmittedSerial = 0L
        linuxTextureSubmittedHostGeneration = 0L
        linuxTextureSubmittedPresentCount = 0L
        linuxTextureConfirmedSerial = 0L
        linuxTextureConfirmedHostGeneration = 0L
        sourceColorInfo = VideoColorInfo()
        updateDesktopColorPipelineStatus()
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
                if (nativeMacRenderer != 0L || nativeWindowsRenderer != 0L || nativeLinuxRenderer != 0L) {
                    // Texture mode owns mpv's sole render context. CPU rendering is available only
                    // through the explicit COMPOSE option, where this renderer is never created.
                    return
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

    /** Renders libmpv directly into the shared D3D11 texture consumed by Nucleus. */
    internal fun renderWindowsTextureFrame(
        requestedWidth: Int,
        requestedHeight: Int,
    ): Boolean {
        if (!usesWindowsColorManagedTexture || disposed.get() || !_hasMedia) return false
        if (requestedWidth <= 0 || requestedHeight <= 0) return false
        val host = windowsTextureHostCapabilities
        val producer = host.producerInfo as? WindowsTextureViewProducerInfo ?: return false
        if (host.presentationState == TextureViewHostPresentationState.UNAVAILABLE) return false
        if (runtimeConfig.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR &&
            host.actualDynamicRange != TextureViewHostDynamicRange.HDR
        ) {
            publishDesktopTextureFailure(
                message = "The active Windows output cannot present the required HDR texture pipeline.",
                reason = ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
            )
            return false
        }

        val (width, height) = constrainMpvRenderSize(requestedWidth, requestedHeight, runtimeConfig.maxRenderPixels)
        val configuration = windowsTextureConfiguration(host)
        // Ordinary libmpv property calls can wait for the core. Never make
        // them while holding renderLock: diagnostics and host callbacks also
        // need that lock, and render control may request an update while a
        // property call is in flight.
        configureWindowsTextureOutput(configuration)
        return try {
            renderLock.withLock {
                if (disposed.get() || !_hasMedia) return@withLock false
                ensureWindowsTextureRendererLocked(
                    adapterLuid = producer.adapterLuid,
                    extendedLinear = configuration.extendedLinear,
                    width = width,
                    height = height,
                ) ?: return@withLock false
                // Recreating a context clears the negotiation marker while
                // detaching the old producer. The properties were already
                // applied above, outside the render critical section.
                windowsTextureConfiguration = configuration
                val serial =
                    onWindowsTextureThread {
                        MpvWindowsTextureBridge.nRenderFrame(
                            nativeRenderer = nativeWindowsRenderer,
                            width = width,
                            height = height,
                        )
                    }
                if (serial <= 0L) {
                    onWindowsTextureThread {
                        MpvWindowsTextureBridge.nGetFailure(nativeWindowsRenderer)
                    }?.let(::publishDesktopTextureFailure)
                    return@withLock false
                }
                val values =
                    onWindowsTextureThread {
                        MpvWindowsTextureBridge.nGetTextureOutputInfo(nativeWindowsRenderer)
                    }
                        ?: return@withLock false
                val output = values.toWindowsTextureOutput(configuration.extendedLinear) ?: return@withLock false
                if (output.adapterLuid != producer.adapterLuid) {
                    publishDesktopTextureFailure(
                        "The MPV texture and Nucleus composition surface use different Windows adapters.",
                    )
                    return@withLock false
                }
                if (windowsTextureOutputState.value != output) {
                    mutateSnapshotState { windowsTextureOutputState.value = output }
                }
                val isNewFrame = serial > windowsTextureProducerSerial
                windowsTextureProducerSerial = maxOf(windowsTextureProducerSerial, serial)
                isNewFrame
            }
        } catch (_: Throwable) {
            publishDesktopTextureFailure("libmpv could not render the shared Windows video texture.")
            false
        }
    }

    /** Records that Compose invalidated TextureView for the producer frame just rendered. */
    internal fun onWindowsTextureFrameSubmitted() {
        val serial = windowsTextureProducerSerial
        if (serial <= 0L) return
        val host = windowsTextureHostCapabilities
        windowsTextureSubmittedSerial = serial
        windowsTextureSubmittedHostGeneration = host.generation
        windowsTextureSubmittedPresentCount = host.presentedFrameCount
    }

    /** Applies output changes and confirms a producer frame only after a later system Present. */
    internal fun onWindowsTextureHostCapabilitiesChanged(capabilities: TextureViewHostCapabilities) {
        windowsTextureHostCapabilities = capabilities
        updateDesktopColorPipelineStatus()
        val producer = capabilities.producerInfo as? WindowsTextureViewProducerInfo
        if (!renderLock.tryLock()) return
        try {
            if (nativeWindowsRenderer != 0L &&
                (producer == null || producer.adapterLuid != windowsTextureAdapterLuid)
            ) {
                detachWindowsTextureRendererLocked(restoreSoftwareRenderer = false)
            }
            val submitted = windowsTextureSubmittedSerial
            if (nativeWindowsRenderer != 0L &&
                submitted > 0L &&
                submitted > windowsTextureConfirmedSerial &&
                capabilities.hasPresentedMpvTextureFrameAfter(
                    submittedGeneration = windowsTextureSubmittedHostGeneration,
                    submittedPresentCount = windowsTextureSubmittedPresentCount,
                    outputRequirement =
                        if (windowsTextureConfiguration?.extendedLinear == true) {
                            MpvTextureOutputRequirement.FP16_SCRGB_OUTPUT
                        } else {
                            MpvTextureOutputRequirement.KNOWN_OUTPUT
                        },
                )
            ) {
                onWindowsTextureThread {
                    MpvWindowsTextureBridge.nReportPresented(nativeWindowsRenderer, submitted)
                }
                windowsTextureConfirmedSerial = submitted
                windowsTextureConfirmedHostGeneration = capabilities.generation
                mutateSnapshotState {
                    renderingInfo.notes = windowsTextureRenderingNotes(confirmedPresent = true)
                }
                updateDesktopColorPipelineStatus()
            }
        } finally {
            renderLock.unlock()
        }
    }

    private fun ensureWindowsTextureRendererLocked(
        adapterLuid: Long,
        extendedLinear: Boolean,
        width: Int,
        height: Int,
    ): Long? {
        if (!MpvWindowsTextureBridge.isAvailable || adapterLuid == 0L) {
            publishDesktopTextureFailure("The Windows MPV TextureView bridge is unavailable.")
            return null
        }
        if (nativeWindowsRenderer != 0L &&
            windowsTextureAdapterLuid == adapterLuid &&
            windowsTextureRendererExtendedLinear == extendedLinear
        ) {
            return nativeWindowsRenderer
        }

        val selectedVideo = suspendVideoOutputForContextSwitch()
        if (nativeWindowsRenderer != 0L) {
            detachWindowsTextureRendererLocked(restoreSoftwareRenderer = false)
        }
        val target =
            try {
                engine.beginExternalRendering()
            } catch (_: RuntimeException) {
                resumeVideoOutputAfterContextSwitch(selectedVideo, SOFTWARE_RENDER_HWDEC)
                publishDesktopTextureFailure("libmpv rejected the Windows GPU render context.")
                return null
            }
        val renderer =
            runCatching {
                onWindowsTextureThread {
                    MpvWindowsTextureBridge.createRenderer(
                        mpvHandle = target.mpvHandle,
                        libraryLoadName = target.libraryLoadName,
                        adapterLuid = adapterLuid,
                        extendedLinear = extendedLinear,
                        width = width,
                        height = height,
                    )
                }
            }.getOrDefault(0L)
        if (renderer == 0L) {
            runCatching { engine.endExternalRendering(restoreSoftwareRenderer = true) }
            resumeVideoOutputAfterContextSwitch(selectedVideo, SOFTWARE_RENDER_HWDEC)
            publishDesktopTextureFailure("The Windows MPV GPU texture renderer could not be created.")
            return null
        }

        nativeWindowsRenderer = renderer
        desktopTextureFailurePublished = false
        desktopTextureFailureDetail = null
        windowsTextureAdapterLuid = adapterLuid
        windowsTextureRendererExtendedLinear = extendedLinear
        windowsTextureConfiguration = null
        windowsTextureProducerSerial = 0L
        windowsTextureSubmittedSerial = 0L
        windowsTextureConfirmedSerial = 0L
        windowsTextureConfirmedHostGeneration = 0L
        frameState.value = null
        // ANGLE is the correct Windows render API, but direct D3D11VA surface
        // interop in third-party libmpv builds is not uniformly stable for
        // Main10/HEVC. Keep hardware decode while taking mpv's explicit copy
        // route into the color-managed RGBA16F target.
        runCatching { engine.setProperty("hwdec", WINDOWS_TEXTURE_HWDEC) }
        resumeVideoOutputAfterContextSwitch(selectedVideo, WINDOWS_TEXTURE_HWDEC)
        mutateSnapshotState {
            renderingInfo.videoRenderer =
                if (extendedLinear) {
                    "libmpv GPU TextureView (D3D11 RGBA16F/scRGB)"
                } else {
                    "libmpv GPU TextureView (D3D11 RGBA8/sRGB)"
                }
            renderingInfo.notes = windowsTextureRenderingNotes(confirmedPresent = false)
        }
        return renderer
    }

    private fun windowsTextureConfiguration(host: TextureViewHostCapabilities): MpvWindowsTextureConfiguration {
        val wantsExtended =
            sourceColorInfo.isHdr &&
                runtimeConfig.dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR &&
                host.actualDynamicRange == TextureViewHostDynamicRange.HDR
        val peak =
            if (wantsExtended) {
                host.maximumLuminanceNits?.coerceAtLeast(MPV_SDR_REFERENCE_WHITE_NITS)
                    ?: MPV_DEFAULT_HDR_PEAK_NITS
            } else {
                MPV_SDR_REFERENCE_WHITE_NITS
            }
        return MpvWindowsTextureConfiguration(wantsExtended, peak)
    }

    private fun configureWindowsTextureOutput(requested: MpvWindowsTextureConfiguration) {
        if (windowsTextureConfiguration == requested) return

        runCatching {
            engine.setProperty("fbo-format", if (requested.extendedLinear) "rgba16f" else "rgba8")
        }
        runCatching { engine.setProperty("target-prim", "bt.709") }
        runCatching { engine.setProperty("hdr-reference-white", MPV_SDR_REFERENCE_WHITE_NITS.toString()) }
        runCatching { engine.setProperty("target-peak", requested.targetPeakNits.toString()) }
        if (requested.extendedLinear) {
            runCatching { engine.setProperty("target-trc", "scrgb") }
        } else {
            runCatching { engine.setProperty("target-trc", "srgb") }
        }
        windowsTextureConfiguration = requested
    }

    private fun detachWindowsTextureRendererLocked(restoreSoftwareRenderer: Boolean) {
        val renderer = nativeWindowsRenderer
        if (renderer == 0L) return
        nativeWindowsRenderer = 0L
        windowsTextureAdapterLuid = 0L
        windowsTextureRendererExtendedLinear = false
        windowsTextureConfiguration = null
        windowsTextureProducerSerial = 0L
        windowsTextureSubmittedSerial = 0L
        windowsTextureConfirmedSerial = 0L
        windowsTextureConfirmedHostGeneration = 0L
        mutateSnapshotState { windowsTextureOutputState.value = null }
        try {
            onWindowsTextureThread {
                MpvWindowsTextureBridge.nDetach(renderer)
            }
        } finally {
            engine.endExternalRendering(restoreSoftwareRenderer)
        }
    }

    /** Renders libmpv into one GBM buffer from the rotating DMA-BUF stream. */
    internal fun renderLinuxTextureFrame(
        requestedWidth: Int,
        requestedHeight: Int,
    ): Boolean {
        if (!usesLinuxColorManagedTexture || disposed.get() || !_hasMedia) return false
        if (requestedWidth <= 0 || requestedHeight <= 0) return false
        val host = linuxTextureHostCapabilities
        if (host.presentationState == TextureViewHostPresentationState.UNAVAILABLE) return false
        if (runtimeConfig.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR &&
            host.actualDynamicRange != TextureViewHostDynamicRange.HDR
        ) {
            publishDesktopTextureFailure(
                message = "The active Linux output cannot present the required HDR texture pipeline.",
                reason = ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
            )
            return false
        }
        val configuration = resolveLinuxTextureConfiguration(host) ?: return false
        val (width, height) =
            constrainMpvRenderSize(requestedWidth, requestedHeight, runtimeConfig.maxRenderPixels)
        return try {
            renderLock.withLock {
                if (disposed.get() || !_hasMedia) return@withLock false
                val renderer = ensureLinuxTextureRendererLocked(configuration) ?: return@withLock false
                applyLinuxTextureOutputProperties(configuration)
                val serial = MpvLinuxTextureBridge.nRenderFrame(renderer, width, height)
                if (serial <= 0L) {
                    MpvLinuxTextureBridge.nGetFailure(renderer)?.let(::publishDesktopTextureFailure)
                    return@withLock false
                }
                if (serial <= linuxTextureProducerSerial) return@withLock false
                val values = MpvLinuxTextureBridge.nAcquireTextureFrame(renderer, serial)
                val output = values?.toLinuxTextureOutput()
                if (output == null) {
                    MpvLinuxTextureBridge.nDiscardTextureFrame(renderer, serial)
                    return@withLock false
                }
                if (output.fourcc != configuration.fourcc ||
                    output.extendedLinear != configuration.extendedLinear
                ) {
                    MpvLinuxTextureBridge.nReleaseTextureFrame(
                        renderer,
                        output.generation,
                        output.serial,
                        output.frameFd,
                        output.acquireFenceFd,
                    )
                    publishDesktopTextureFailure("The Linux MPV texture does not match the negotiated host format.")
                    return@withLock false
                }
                val frame =
                    TextureViewFrame(
                        source =
                            nucleusDmaBufTextureSource(
                                fd = output.frameFd,
                                widthPx = output.width,
                                heightPx = output.height,
                                stride = output.stride,
                                fourcc = output.fourcc,
                                offset = output.offset,
                                modifier = output.modifier,
                                colorInfo =
                                    if (output.extendedLinear) {
                                        TextureColorInfo(
                                            encoding = TextureColorEncoding.EXTENDED_LINEAR_SRGB,
                                            premultipliedAlpha = true,
                                            sdrWhiteLevelNits = MPV_SDR_REFERENCE_WHITE_NITS,
                                        )
                                    } else {
                                        TextureColorInfo.SRGB_PREMULTIPLIED
                                    },
                            ),
                        acquireFenceFd = output.acquireFenceFd,
                        onReleased = { releaseFenceFd ->
                            MpvLinuxTextureBridge.nReleaseTextureFrame(
                                nativeRenderer = renderer,
                                generation = output.generation,
                                serial = output.serial,
                                frameFd = output.frameFd,
                                releaseFenceFd = releaseFenceFd,
                            )
                        },
                    )
                val submitted = runCatching { linuxTextureStreamController.submitFrame(frame) }.isSuccess
                if (!submitted) {
                    MpvLinuxTextureBridge.nReleaseTextureFrame(
                        renderer,
                        output.generation,
                        output.serial,
                        output.frameFd,
                        output.acquireFenceFd,
                    )
                    return@withLock false
                }
                linuxTextureProducerSerial = serial
                linuxTextureSubmittedSerial = serial
                linuxTextureSubmittedHostGeneration = host.generation
                linuxTextureSubmittedPresentCount = host.presentedFrameCount
                true
            }
        } catch (_: Throwable) {
            publishDesktopTextureFailure("libmpv could not render the shared Linux video texture.")
            false
        }
    }

    /** Confirms the exact producer generation only after Nucleus reports a later Present. */
    internal fun onLinuxTextureHostCapabilitiesChanged(capabilities: TextureViewHostCapabilities) {
        linuxTextureHostCapabilities = capabilities
        updateDesktopColorPipelineStatus()
        val producer = capabilities.producerInfo as? LinuxTextureViewProducerInfo
        if (!renderLock.tryLock()) return
        try {
            if (nativeLinuxRenderer != 0L &&
                (
                    producer?.renderNode == null ||
                        producer.renderNode != linuxTextureConfiguration?.renderNode
                )
            ) {
                detachLinuxTextureRendererLocked(restoreSoftwareRenderer = false)
            }
            val submitted = linuxTextureSubmittedSerial
            if (nativeLinuxRenderer != 0L &&
                submitted > linuxTextureConfirmedSerial &&
                capabilities.hasPresentedMpvTextureFrameAfter(
                    submittedGeneration = linuxTextureSubmittedHostGeneration,
                    submittedPresentCount = linuxTextureSubmittedPresentCount,
                    outputRequirement =
                        if (linuxTextureConfiguration?.extendedLinear == true) {
                            MpvTextureOutputRequirement.HDR_TEN_BIT_OUTPUT
                        } else {
                            MpvTextureOutputRequirement.KNOWN_OUTPUT
                        },
                )
            ) {
                MpvLinuxTextureBridge.nReportPresented(nativeLinuxRenderer, submitted)
                linuxTextureConfirmedSerial = submitted
                linuxTextureConfirmedHostGeneration = capabilities.generation
                mutateSnapshotState {
                    renderingInfo.notes = linuxTextureRenderingNotes(confirmedPresent = true)
                }
                updateDesktopColorPipelineStatus()
            }
        } finally {
            renderLock.unlock()
        }
    }

    private fun resolveLinuxTextureConfiguration(host: TextureViewHostCapabilities): MpvLinuxTextureConfiguration? {
        val producer = host.producerInfo as? LinuxTextureViewProducerInfo
        val renderNode = producer?.renderNode
        if (producer == null || renderNode == null) {
            publishDesktopTextureFailure("The active Linux TextureView host did not expose its DRM render node.")
            return null
        }
        val hdrFormat = producer.formats.firstOrNull { it.format == NucleusDrmFormat.ABGR16161616F }
        val sdrFormat = producer.formats.firstOrNull { it.format == NucleusDrmFormat.ARGB8888 }
        val wantsExtended =
            sourceColorInfo.isHdr &&
                runtimeConfig.dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR &&
                host.actualDynamicRange == TextureViewHostDynamicRange.HDR
        if (wantsExtended &&
            hdrFormat == null &&
            runtimeConfig.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR
        ) {
            publishDesktopTextureFailure(
                message = "The active Linux GPU cannot import the required FP16 video texture.",
                reason = ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
            )
            return null
        }
        val selected = if (wantsExtended && hdrFormat != null) hdrFormat else sdrFormat
        if (selected == null) {
            publishDesktopTextureFailure("The active Linux GPU exposes no compatible TextureView DMA-BUF format.")
            return null
        }
        val extended = selected.format == NucleusDrmFormat.ABGR16161616F
        val peak =
            if (extended) {
                host.maximumLuminanceNits?.coerceAtLeast(MPV_SDR_REFERENCE_WHITE_NITS)
                    ?: MPV_DEFAULT_HDR_PEAK_NITS
            } else {
                MPV_SDR_REFERENCE_WHITE_NITS
            }
        return MpvLinuxTextureConfiguration(
            renderNode = renderNode,
            fourcc = selected.format,
            modifiers = selected.modifiers,
            extendedLinear = extended,
            targetPeakNits = peak,
        )
    }

    private fun ensureLinuxTextureRendererLocked(configuration: MpvLinuxTextureConfiguration): Long? {
        if (!MpvLinuxTextureBridge.isAvailable) {
            publishDesktopTextureFailure("The Linux MPV TextureView bridge is unavailable.")
            return null
        }
        if (nativeLinuxRenderer != 0L && linuxTextureConfiguration == configuration) {
            return nativeLinuxRenderer
        }
        val selectedVideo = suspendVideoOutputForContextSwitch()
        if (nativeLinuxRenderer != 0L) {
            detachLinuxTextureRendererLocked(restoreSoftwareRenderer = false)
        }
        val target =
            try {
                engine.beginExternalRendering()
            } catch (_: RuntimeException) {
                resumeVideoOutputAfterContextSwitch(selectedVideo, SOFTWARE_RENDER_HWDEC)
                publishDesktopTextureFailure("libmpv rejected the Linux GPU render context.")
                return null
            }
        val renderer =
            runCatching {
                MpvLinuxTextureBridge.nCreateRenderer(
                    mpvHandle = target.mpvHandle,
                    libraryLoadName = target.libraryLoadName,
                    renderNode = configuration.renderNode,
                    fourcc = configuration.fourcc,
                    modifiers = configuration.modifiers.toLongArray(),
                    extendedLinear = configuration.extendedLinear,
                )
            }.getOrDefault(0L)
        if (renderer == 0L) {
            runCatching { engine.endExternalRendering(restoreSoftwareRenderer = true) }
            resumeVideoOutputAfterContextSwitch(selectedVideo, SOFTWARE_RENDER_HWDEC)
            publishDesktopTextureFailure("The Linux MPV DMA-BUF texture renderer could not be created.")
            return null
        }
        nativeLinuxRenderer = renderer
        linuxTextureConfiguration = configuration
        desktopTextureFailurePublished = false
        desktopTextureFailureDetail = null
        linuxTextureProducerSerial = 0L
        linuxTextureSubmittedSerial = 0L
        linuxTextureConfirmedSerial = 0L
        linuxTextureConfirmedHostGeneration = 0L
        frameState.value = null
        applyLinuxTextureOutputProperties(configuration)
        runCatching { engine.setProperty("hwdec", "auto-safe") }
        resumeVideoOutputAfterContextSwitch(selectedVideo, "auto-safe")
        mutateSnapshotState {
            renderingInfo.videoRenderer =
                if (configuration.extendedLinear) {
                    "libmpv GPU TextureView (GBM RGBA16F/scRGB)"
                } else {
                    "libmpv GPU TextureView (GBM RGBA8/sRGB)"
                }
            renderingInfo.notes = linuxTextureRenderingNotes(confirmedPresent = false)
        }
        return renderer
    }

    private fun applyLinuxTextureOutputProperties(configuration: MpvLinuxTextureConfiguration) {
        runCatching { engine.setProperty("fbo-format", if (configuration.extendedLinear) "rgba16f" else "rgba8") }
        runCatching { engine.setProperty("target-prim", "bt.709") }
        runCatching { engine.setProperty("hdr-reference-white", MPV_SDR_REFERENCE_WHITE_NITS.toString()) }
        runCatching { engine.setProperty("target-peak", configuration.targetPeakNits.toString()) }
        runCatching { engine.setProperty("target-trc", if (configuration.extendedLinear) "scrgb" else "srgb") }
    }

    private fun detachLinuxTextureRendererLocked(restoreSoftwareRenderer: Boolean) {
        val renderer = nativeLinuxRenderer
        if (renderer == 0L) return
        nativeLinuxRenderer = 0L
        linuxTextureConfiguration = null
        linuxTextureStreamController.clear()
        linuxTextureProducerSerial = 0L
        linuxTextureSubmittedSerial = 0L
        linuxTextureConfirmedSerial = 0L
        linuxTextureConfirmedHostGeneration = 0L
        try {
            MpvLinuxTextureBridge.nDetach(renderer)
        } finally {
            engine.endExternalRendering(restoreSoftwareRenderer)
        }
    }

    private fun linuxTextureRenderingNotes(confirmedPresent: Boolean): String =
        "MPV render API exports a fenced GBM DMA-BUF into the Nucleus color-managed scene; " +
            "systemPresentConfirmed=$confirmedPresent."

    private fun publishDesktopTextureFailure(
        message: String,
        reason: ColorPipelineFallbackReason = ColorPipelineFallbackReason.RENDERER_CONFIGURATION_FAILED,
    ) {
        if (desktopTextureFailurePublished || disposed.get()) return
        desktopTextureFailurePublished = true
        desktopTextureFailureDetail = message
        updateDesktopColorPipelineStatus()
        publishError(VideoPlayerError.ColorPipelineError(reason, message))
    }

    private fun windowsTextureRenderingNotes(confirmedPresent: Boolean): String =
        "MPV render API exports a keyed-mutex D3D11 texture into the Nucleus color-managed scene; " +
            "systemPresentConfirmed=$confirmedPresent."

    private fun updateDesktopColorPipelineStatus(failureDetail: String? = desktopTextureFailureDetail) {
        val host =
            when {
                isWindowsHost() -> windowsTextureHostCapabilities
                isMacHost() -> macTextureHostCapabilities
                isLinuxHost() -> linuxTextureHostCapabilities
                else -> return
            }
        val hostAvailable =
            host.presentationState != TextureViewHostPresentationState.UNAVAILABLE &&
                host.producerInfo != null &&
                host.outputPixelFormat != TextureViewHostPixelFormat.UNKNOWN
        val hostHdr = hostAvailable && host.actualDynamicRange == TextureViewHostDynamicRange.HDR
        val displayRanges =
            if (hostHdr) {
                setOf(
                    VideoDynamicRange.SDR,
                    VideoDynamicRange.HDR10,
                    VideoDynamicRange.HDR10_PLUS,
                    VideoDynamicRange.HLG,
                )
            } else if (hostAvailable) {
                setOf(VideoDynamicRange.SDR)
            } else {
                emptySet()
            }
        val display =
            DisplayColorCapabilities(
                isKnown = hostAvailable,
                supportedDynamicRanges = displayRanges,
                maxLuminanceNits = host.maximumLuminanceNits,
                referenceWhiteNits = host.sdrWhiteLevelNits,
            )
        val source = sourceColorInfo
        val nativeDolbyVisionRequired =
            source.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                runtimeConfig.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE
        val requiresMissingHdr =
            source.isHdr &&
                runtimeConfig.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR &&
                !hostHdr
        val plannedOutput =
            when {
                failureDetail != null || nativeDolbyVisionRequired || requiresMissingHdr ->
                    VideoDynamicRange.UNKNOWN
                runtimeConfig.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR ->
                    VideoDynamicRange.SDR
                !source.isHdr -> source.dynamicRange
                !hostHdr -> VideoDynamicRange.SDR
                source.dynamicRange == VideoDynamicRange.DOLBY_VISION -> VideoDynamicRange.HDR10
                source.dynamicRange == VideoDynamicRange.HDR10_PLUS -> VideoDynamicRange.HDR10
                else -> source.dynamicRange
            }
        val confirmedSerial =
            when {
                isWindowsHost() -> windowsTextureConfirmedSerial
                isMacHost() -> macTextureConfirmedSerial
                else -> linuxTextureConfirmedSerial
            }
        val confirmedGeneration =
            when {
                isWindowsHost() -> windowsTextureConfirmedHostGeneration
                isMacHost() -> macTextureConfirmedHostGeneration
                else -> linuxTextureConfirmedHostGeneration
            }
        val confirmed =
            confirmedSerial > 0L &&
                confirmedGeneration == host.generation &&
                (
                    !plannedOutput.isHdrSignal() ||
                        (
                            host.actualDynamicRange == TextureViewHostDynamicRange.HDR &&
                                host.outputPixelFormat.componentBitDepth >= 10
                        )
                ) &&
                plannedOutput != VideoDynamicRange.UNKNOWN
        val fallback =
            when {
                failureDetail != null -> ColorPipelineFallbackReason.RENDERER_CONFIGURATION_FAILED
                nativeDolbyVisionRequired -> ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_UNAVAILABLE
                requiresMissingHdr -> ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
                source.dynamicRange == VideoDynamicRange.UNKNOWN -> ColorPipelineFallbackReason.SOURCE_COLOR_UNKNOWN
                runtimeConfig.dynamicRangePolicy == DynamicRangePolicy.FORCE_SDR && source.isHdr ->
                    ColorPipelineFallbackReason.EXPLICIT_SDR_REQUEST
                source.isHdr && !hostHdr -> ColorPipelineFallbackReason.DISPLAY_DOES_NOT_SUPPORT_SOURCE
                source.dynamicRange == VideoDynamicRange.DOLBY_VISION ->
                    ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_USED
                source.dynamicRange == VideoDynamicRange.SDR -> ColorPipelineFallbackReason.SOURCE_IS_SDR
                else -> ColorPipelineFallbackReason.NONE
            }
        val metadataHandling =
            when (source.dynamicRange) {
                VideoDynamicRange.HDR10_PLUS -> DynamicMetadataHandling.APPLIED_BY_RENDERER
                VideoDynamicRange.DOLBY_VISION -> DynamicMetadataHandling.CONVERTED
                else -> DynamicMetadataHandling.NONE
            }
        mutableColorPipelineStatus.value =
            VideoColorPipelineStatus(
                requestedDynamicRangePolicy = runtimeConfig.dynamicRangePolicy,
                requestedDolbyVisionPolicy = runtimeConfig.dolbyVisionPolicy,
                source = source,
                display = display,
                decoderName = renderingInfo.videoDecoder,
                decoderCapabilities = MPV_DECODER_COLOR_CAPABILITIES,
                surface =
                    if (runtimeConfig.desktopVideoSurfaceMode == DesktopVideoSurfaceMode.COMPOSE) {
                        VideoSurfaceKind.COMPOSE_CANVAS
                    } else {
                        VideoSurfaceKind.TEXTURE_VIEW
                    },
                renderer =
                    if (plannedOutput.isHdrSignal()) {
                        ColorPipelineRenderer.CONTROLLED_HDR
                    } else if (plannedOutput == VideoDynamicRange.SDR) {
                        ColorPipelineRenderer.CONTROLLED_SDR
                    } else {
                        ColorPipelineRenderer.UNKNOWN
                    },
                rendererCapabilities = MPV_TEXTURE_RENDERER_COLOR_CAPABILITIES,
                plannedOutputDynamicRange = plannedOutput,
                outputDynamicRange = plannedOutput.takeIf { confirmed } ?: VideoDynamicRange.UNKNOWN,
                plannedMetadataHandling = metadataHandling,
                metadataHandling = metadataHandling.takeIf { confirmed } ?: DynamicMetadataHandling.NONE,
                verification =
                    if (confirmed) {
                        ColorPipelineVerification.RENDERER_CONFIGURED
                    } else {
                        ColorPipelineVerification.NONE
                    },
                requestHonored = confirmed && !nativeDolbyVisionRequired && !requiresMissingHdr,
                fallbackReason = fallback,
                detail = failureDetail,
            )
    }

    internal fun setContentScaleMode(contentScale: ContentScale) {
        nativeMacContentScaleMode = contentScale.toMpvMacContentScaleMode()
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

    /**
     * libmpv requires its render context to exist before loading media creates the video output.
     * Creating it lazily from the Compose surface meant the first seek had to rebuild the video
     * chain before native playback became usable, and left the initial chain with uneven pacing.
     */
    private fun initializeNativeMacRendererBeforePlayback() {
        if (!MpvMacNativeBridge.isAvailable ||
            runtimeConfig.desktopVideoSurfaceMode == DesktopVideoSurfaceMode.COMPOSE ||
            disposed.get()
        ) {
            return
        }
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
            if (renderer == 0L) {
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
                renderingInfo.videoRenderer = "libmpv GPU TextureView (IOSurface)"
                renderingInfo.notes = nativeMacRenderingNotes()
            }
        }
    }

    internal fun renderMacTextureFrame(
        requestedWidth: Int,
        requestedHeight: Int,
    ): Boolean {
        if (!usesMacColorManagedTexture || !_hasMedia || requestedWidth <= 0 || requestedHeight <= 0) {
            return false
        }
        val host = macTextureHostCapabilities
        if (runtimeConfig.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR &&
            host.actualDynamicRange != TextureViewHostDynamicRange.HDR
        ) {
            publishDesktopTextureFailure(
                message = "The active macOS output cannot present the required HDR texture pipeline.",
                reason = ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
            )
            return false
        }
        val (width, height) = constrainMpvRenderSize(requestedWidth, requestedHeight, runtimeConfig.maxRenderPixels)
        return renderLock.withLock {
            val renderer = nativeMacRenderer
            if (renderer == 0L || disposed.get()) return@withLock false
            configureMacTextureOutputLocked(host)
            val serial =
                runCatching { MpvMacNativeBridge.nRenderFrame(renderer, width, height) }
                    .getOrDefault(-1L)
            if (serial <= macTextureProducerSerial) return@withLock false
            val values = MpvMacNativeBridge.nGetTextureOutputInfo(renderer) ?: return@withLock false
            val output = values.toMacTextureOutput() ?: return@withLock false
            if (output.serial != serial) return@withLock false
            val frame =
                TextureViewFrame(
                    source =
                        nucleusIOSurfaceTextureSource(
                            ioSurface = output.ioSurface,
                            widthPx = output.width,
                            heightPx = output.height,
                            colorInfo =
                                if (output.extendedLinear) {
                                    TextureColorInfo(
                                        encoding = TextureColorEncoding.EXTENDED_LINEAR_SRGB,
                                        premultipliedAlpha = true,
                                        sdrWhiteLevelNits = MPV_SDR_REFERENCE_WHITE_NITS,
                                    )
                                } else {
                                    TextureColorInfo.SRGB_PREMULTIPLIED
                                },
                        ),
                    onReleased = {
                        MpvMacNativeBridge.nReleaseTextureFrame(
                            nativeRenderer = renderer,
                            generation = output.generation,
                            serial = output.serial,
                        )
                    },
                )
            val submitted =
                runCatching { macTextureStreamController.submitFrame(frame) }
                    .isSuccess
            if (!submitted) {
                MpvMacNativeBridge.nReleaseTextureFrame(renderer, output.generation, output.serial)
                return@withLock false
            }
            macTextureProducerSerial = serial
            macTextureSubmittedSerial = serial
            macTextureSubmittedHostGeneration = host.generation
            macTextureSubmittedPresentCount = host.presentedFrameCount
            true
        }
    }

    internal fun onMacTextureHostCapabilitiesChanged(capabilities: TextureViewHostCapabilities) {
        macTextureHostCapabilities = capabilities
        updateDesktopColorPipelineStatus()
        nativeMacSurfaceAttached =
            capabilities.presentationState != TextureViewHostPresentationState.UNAVAILABLE
        resumeNativeMacPlaybackIfReady()
        val submitted = macTextureSubmittedSerial
        if (submitted <= macTextureConfirmedSerial ||
            !capabilities.hasPresentedMpvTextureFrameAfter(
                submittedGeneration = macTextureSubmittedHostGeneration,
                submittedPresentCount = macTextureSubmittedPresentCount,
                outputRequirement =
                    if (macTextureConfiguration?.extended == true) {
                        MpvTextureOutputRequirement.FP16_SCRGB_OUTPUT
                    } else {
                        MpvTextureOutputRequirement.KNOWN_OUTPUT
                    },
            ) ||
            !renderLock.tryLock()
        ) {
            return
        }
        try {
            val renderer = nativeMacRenderer
            if (renderer != 0L) {
                MpvMacNativeBridge.nReportPresented(renderer, submitted)
                macTextureConfirmedSerial = submitted
                macTextureConfirmedHostGeneration = capabilities.generation
                updateDesktopColorPipelineStatus()
            }
        } finally {
            renderLock.unlock()
        }
    }

    internal fun onMacTextureSurfaceDetached() {
        nativeMacSurfaceAttached = false
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
        macTextureConfiguration = null
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
        val primaries = engine.getProperty("video-params/primaries")
        val matrix = engine.getProperty("video-params/colormatrix")
        val colorLevels = engine.getProperty("video-params/colorlevels")
        val pixelFormat = engine.getProperty("video-params/pixelformat")
        val videoFormat = engine.getProperty("video-format")
        val dolbyVisionProfile = engine.getProperty("video-params/dolby-vision-profile").positiveIntOrNull()
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
        val refreshedColorInfo =
            mpvVideoColorInfo(
                transfer = transfer,
                primaries = primaries,
                matrix = matrix,
                colorLevels = colorLevels,
                pixelFormat = pixelFormat,
                codecDescription = listOfNotNull(videoDecoder, videoFormat).joinToString(" "),
                dolbyVisionProfile = dolbyVisionProfile,
            )
        if (sourceColorInfo != refreshedColorInfo) {
            sourceColorInfo = refreshedColorInfo
            updateDesktopColorPipelineStatus()
            if (refreshedColorInfo.dynamicRange == VideoDynamicRange.DOLBY_VISION &&
                runtimeConfig.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE
            ) {
                publishDesktopTextureFailure(
                    message = "Native Dolby Vision presentation is unsupported by desktop TextureView backends.",
                    reason = ColorPipelineFallbackReason.DOLBY_VISION_BASE_LAYER_UNAVAILABLE,
                )
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
        macTextureStreamController.close()
        linuxTextureStreamController.close()
        renderLock.withLock {
            detachWindowsTextureRendererLocked(restoreSoftwareRenderer = false)
            detachNativeMacRendererLocked(restoreSoftwareRenderer = false)
            detachLinuxTextureRendererLocked(restoreSoftwareRenderer = false)
            frameState.value = null
            framePool.close()
            engine.close()
        }
        windowsTextureExecutor.shutdown()
        scope.cancel()
    }

    private fun applyNativeMacOutputColorMode(mode: MpvMacOutputColorMode) {
        when (mode) {
            MpvMacOutputColorMode.SDR -> {
                runCatching { engine.setProperty("fbo-format", "rgba8") }
                runCatching { engine.setProperty("target-prim", "bt.709") }
                runCatching { engine.setProperty("target-trc", "srgb") }
                runCatching { engine.setProperty("target-peak", MPV_SDR_REFERENCE_WHITE_NITS.toString()) }
            }
            MpvMacOutputColorMode.EXTENDED_LINEAR -> {
                runCatching { engine.setProperty("fbo-format", "rgba16f") }
                runCatching { engine.setProperty("target-prim", "bt.709") }
                runCatching { engine.setProperty("target-trc", "scrgb") }
            }
        }
        runCatching { engine.setProperty("hdr-reference-white", MPV_SDR_REFERENCE_WHITE_NITS.toString()) }
        MpvMacNativeBridge.nSetColorMode(nativeMacRenderer, mode.nativeValue)
    }

    private fun configureMacTextureOutputLocked(host: TextureViewHostCapabilities) {
        val extended =
            sourceColorInfo.isHdr &&
                runtimeConfig.dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR &&
                host.actualDynamicRange == TextureViewHostDynamicRange.HDR
        val peak =
            if (extended) {
                host.maximumLuminanceNits?.coerceAtLeast(MPV_SDR_REFERENCE_WHITE_NITS)
                    ?: MPV_DEFAULT_HDR_PEAK_NITS
            } else {
                MPV_SDR_REFERENCE_WHITE_NITS
            }
        val configuration = MpvMacTextureConfiguration(extended = extended, peakNits = peak)
        if (macTextureConfiguration == configuration) return
        nativeMacColorMode =
            if (extended) MpvMacOutputColorMode.EXTENDED_LINEAR else MpvMacOutputColorMode.SDR
        applyNativeMacOutputColorMode(nativeMacColorMode)
        runCatching { engine.setProperty("target-peak", peak.toString()) }
        macTextureConfiguration = configuration
    }

    private fun resetNativeMacTargetColorProperties() {
        runCatching { engine.setProperty("target-prim", "auto") }
        runCatching { engine.setProperty("target-trc", "auto") }
    }

    private fun applyNativeMacInputGeometry() {
        val geometry =
            mpvMacInputGeometry(
                projectionEnabled = nativeMacRenderer != 0L && nativeMacProjectionEnabled,
                contentScaleMode = nativeMacContentScaleMode,
            )
        runCatching { engine.setProperty("keepaspect", geometry.keepAspect) }
        runCatching { engine.setProperty("panscan", geometry.panscan) }
        val renderer = nativeMacRenderer
        if (renderer != 0L) {
            runCatching {
                MpvMacNativeBridge.nSetContentScale(
                    nativeRenderer = renderer,
                    contentScaleMode = nativeMacContentScaleMode.nativeValue,
                    mediaAspect = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f),
                )
            }
        }
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
        private const val WINDOWS_TEXTURE_HWDEC = "d3d11va-copy"
        private const val MPV_SDR_REFERENCE_WHITE_NITS = 203f
        private const val MPV_DEFAULT_HDR_PEAK_NITS = 1_000f
        private const val MPV_END_FILE_REASON_STOP = 2
        private const val MPV_END_FILE_REASON_QUIT = 3
        private const val MPV_END_FILE_REASON_ERROR = 4
        private const val AUDIO_TRACK_PREFIX = "mpv:audio:"
        private const val SUBTITLE_TRACK_PREFIX = "mpv:subtitle:"
    }
}

internal data class MpvWindowsTextureOutput(
    val sharedHandle: Long,
    val width: Int,
    val height: Int,
    val dxgiFormat: Int,
    val producerGeneration: Long,
    val adapterLuid: Long,
    val extendedLinear: Boolean,
)

private data class MpvMacTextureOutput(
    val ioSurface: Long,
    val width: Int,
    val height: Int,
    val pixelFormat: Int,
    val generation: Long,
    val serial: Long,
    val extendedLinear: Boolean,
)

private data class MpvMacTextureConfiguration(
    val extended: Boolean,
    val peakNits: Float,
)

private data class MpvWindowsTextureConfiguration(
    val extendedLinear: Boolean,
    val targetPeakNits: Float,
)

private data class MpvLinuxTextureOutput(
    val frameFd: Int,
    val width: Int,
    val height: Int,
    val stride: Int,
    val fourcc: Int,
    val offset: Int,
    val modifier: Long,
    val generation: Long,
    val serial: Long,
    val acquireFenceFd: Int,
    val extendedLinear: Boolean,
)

private data class MpvLinuxTextureConfiguration(
    val renderNode: String,
    val fourcc: Int,
    val modifiers: List<Long>,
    val extendedLinear: Boolean,
    val targetPeakNits: Float,
)

private fun LongArray.toMacTextureOutput(): MpvMacTextureOutput? {
    if (size < MPV_MAC_TEXTURE_INFO_SIZE) return null
    val output =
        MpvMacTextureOutput(
            ioSurface = get(0),
            width = get(1).toInt(),
            height = get(2).toInt(),
            pixelFormat = get(3).toInt(),
            generation = get(4),
            serial = get(5),
            extendedLinear = get(6) != 0L,
        )
    return output.takeIf {
        it.ioSurface != 0L && it.width > 0 && it.height > 0 && it.generation > 0L && it.serial > 0L
    }
}

private fun LongArray.toWindowsTextureOutput(extendedLinear: Boolean): MpvWindowsTextureOutput? {
    if (size < MPV_WINDOWS_TEXTURE_INFO_SIZE) return null
    val handle = get(MPV_WINDOWS_TEXTURE_HANDLE_INDEX)
    val width = get(MPV_WINDOWS_TEXTURE_WIDTH_INDEX).toInt()
    val height = get(MPV_WINDOWS_TEXTURE_HEIGHT_INDEX).toInt()
    val format = get(MPV_WINDOWS_TEXTURE_FORMAT_INDEX).toInt()
    val generation = get(MPV_WINDOWS_TEXTURE_GENERATION_INDEX)
    val adapterLuid = get(MPV_WINDOWS_TEXTURE_ADAPTER_LUID_INDEX)
    if (handle == 0L || width <= 0 || height <= 0 || generation <= 0L || adapterLuid == 0L) return null
    val expectedFormat =
        if (extendedLinear) DXGI_FORMAT_R16G16B16A16_FLOAT else DXGI_FORMAT_R8G8B8A8_UNORM
    if (format != expectedFormat) return null
    return MpvWindowsTextureOutput(
        sharedHandle = handle,
        width = width,
        height = height,
        dxgiFormat = format,
        producerGeneration = generation,
        adapterLuid = adapterLuid,
        extendedLinear = extendedLinear,
    )
}

private fun LongArray.toLinuxTextureOutput(): MpvLinuxTextureOutput? {
    if (size < MPV_LINUX_TEXTURE_INFO_SIZE) return null
    val output =
        MpvLinuxTextureOutput(
            frameFd = get(0).toInt(),
            width = get(1).toInt(),
            height = get(2).toInt(),
            stride = get(3).toInt(),
            fourcc = get(4).toInt(),
            offset = get(5).toInt(),
            modifier = get(6),
            generation = get(7),
            serial = get(8),
            acquireFenceFd = get(9).toInt(),
            extendedLinear = get(10) != 0L,
        )
    return output.takeIf {
        it.frameFd >= 0 &&
            it.width > 0 &&
            it.height > 0 &&
            it.stride > 0 &&
            it.offset >= 0 &&
            it.fourcc in setOf(NucleusDrmFormat.ARGB8888, NucleusDrmFormat.ABGR16161616F) &&
            it.generation > 0L &&
            it.serial > 0L &&
            it.acquireFenceFd >= TextureViewFrame.NO_FENCE
    }
}

private fun constrainMpvRenderSize(
    requestedWidth: Int,
    requestedHeight: Int,
    maxRenderPixels: Int,
): Pair<Int, Int> {
    val pixels = requestedWidth.toLong() * requestedHeight.toLong()
    if (pixels <= maxRenderPixels.toLong()) return requestedWidth to requestedHeight
    val scale = sqrt(maxRenderPixels.toDouble() / pixels.toDouble())
    return (requestedWidth * scale).toInt().coerceAtLeast(1) to
        (requestedHeight * scale).toInt().coerceAtLeast(1)
}

private fun isWindowsHost(): Boolean = System.getProperty("os.name", "").lowercase().contains("windows")

private fun isMacHost(): Boolean = System.getProperty("os.name", "").lowercase().let { "mac" in it || "darwin" in it }

private fun isLinuxHost(): Boolean = System.getProperty("os.name", "").lowercase().contains("linux")

private const val MPV_WINDOWS_TEXTURE_INFO_SIZE = 8
private const val MPV_MAC_TEXTURE_INFO_SIZE = 7
private const val MPV_LINUX_TEXTURE_INFO_SIZE = 11
private const val MPV_WINDOWS_TEXTURE_HANDLE_INDEX = 0
private const val MPV_WINDOWS_TEXTURE_WIDTH_INDEX = 1
private const val MPV_WINDOWS_TEXTURE_HEIGHT_INDEX = 2
private const val MPV_WINDOWS_TEXTURE_FORMAT_INDEX = 3
private const val MPV_WINDOWS_TEXTURE_GENERATION_INDEX = 4
private const val MPV_WINDOWS_TEXTURE_ADAPTER_LUID_INDEX = 6
private const val DXGI_FORMAT_R16G16B16A16_FLOAT = 10
private const val DXGI_FORMAT_R8G8B8A8_UNORM = 28

private val MPV_DECODER_COLOR_CAPABILITIES =
    DecoderColorCapabilities(
        isKnown = true,
        supportedDynamicRanges =
            setOf(
                VideoDynamicRange.SDR,
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HDR10_PLUS,
                VideoDynamicRange.HLG,
                VideoDynamicRange.DOLBY_VISION,
            ),
        maxBitDepth = 16,
        isDolbyVisionProfileSupportKnown = false,
    )

private val MPV_TEXTURE_RENDERER_COLOR_CAPABILITIES =
    RendererColorCapabilities(
        controlledHdrDynamicRanges =
            setOf(
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HDR10_PLUS,
                VideoDynamicRange.HLG,
            ),
        controlledOutputConversions =
            mapOf(
                VideoDynamicRange.HDR10_PLUS to VideoDynamicRange.HDR10,
                VideoDynamicRange.DOLBY_VISION to VideoDynamicRange.HDR10,
            ),
        supportsToneMappingToSdr = true,
        supportsHdr10PlusApplication = true,
        supportsDolbyVisionMetadata = false,
        supportsDolbyVisionToneMappingToSdr = true,
    )

private fun mpvVideoColorInfo(
    transfer: String?,
    primaries: String?,
    matrix: String?,
    colorLevels: String?,
    pixelFormat: String?,
    codecDescription: String,
    dolbyVisionProfile: Int?,
): VideoColorInfo {
    val normalizedTransfer = transfer?.lowercase()
    val isDolbyVision =
        dolbyVisionProfile != null ||
            codecDescription.lowercase().let { "dolby vision" in it || "dovi" in it }
    val dynamicRange =
        when {
            isDolbyVision -> VideoDynamicRange.DOLBY_VISION
            normalizedTransfer in setOf("pq", "st2084", "smpte2084") -> VideoDynamicRange.HDR10
            normalizedTransfer in setOf("hlg", "arib-std-b67") -> VideoDynamicRange.HLG
            normalizedTransfer == null -> VideoDynamicRange.UNKNOWN
            else -> VideoDynamicRange.SDR
        }
    return VideoColorInfo(
        dynamicRange = dynamicRange,
        bitDepth = pixelFormat.mpvPixelBitDepth(),
        primaries =
            when (primaries?.lowercase()) {
                "bt.2020", "bt2020" -> VideoColorPrimaries.BT2020
                "bt.709", "bt709" -> VideoColorPrimaries.BT709
                "display-p3", "dci-p3" -> VideoColorPrimaries.DISPLAY_P3
                "bt.601-525", "smpte-170m" -> VideoColorPrimaries.BT601_525
                "bt.601-625", "bt.470bg" -> VideoColorPrimaries.BT601_625
                else -> VideoColorPrimaries.UNKNOWN
            },
        transfer =
            when (normalizedTransfer) {
                "pq", "st2084", "smpte2084" -> VideoColorTransfer.PQ
                "hlg", "arib-std-b67" -> VideoColorTransfer.HLG
                "linear" -> VideoColorTransfer.LINEAR
                "srgb" -> VideoColorTransfer.SRGB
                null -> VideoColorTransfer.UNKNOWN
                else -> VideoColorTransfer.SDR
            },
        matrix =
            when (matrix?.lowercase()) {
                "rgb" -> VideoColorMatrix.RGB
                "bt.709", "bt709" -> VideoColorMatrix.BT709
                "bt.2020-ncl", "bt2020nc" -> VideoColorMatrix.BT2020_NCL
                "bt.2020-cl", "bt2020c" -> VideoColorMatrix.BT2020_CL
                "ictcp" -> VideoColorMatrix.ICTCP
                "bt.601", "smpte-170m" -> VideoColorMatrix.BT601
                else -> VideoColorMatrix.UNKNOWN
            },
        range =
            when (colorLevels?.lowercase()) {
                "full", "pc" -> VideoColorRange.FULL
                "limited", "tv" -> VideoColorRange.LIMITED
                else -> VideoColorRange.UNKNOWN
            },
        dolbyVision =
            if (isDolbyVision) {
                DolbyVisionInfo(
                    profile = dolbyVisionProfile,
                    hasHdr10CompatibleBaseLayer =
                        normalizedTransfer in setOf("pq", "st2084", "smpte2084"),
                    hasHlgCompatibleBaseLayer = normalizedTransfer in setOf("hlg", "arib-std-b67"),
                )
            } else {
                null
            },
    )
}

private fun String?.mpvPixelBitDepth(): Int? {
    val value = this?.lowercase() ?: return null
    if ("p010" in value || "p016" in value) return value.substringAfter("p0").take(2).toIntOrNull()
    Regex("p(\\d{2})(?:le|be)?")
        .find(value)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let { return it }
    return when {
        "rgb48" in value || "rgba64" in value -> 16
        "rgb30" in value || "x2rgb10" in value -> 10
        value.isNotBlank() -> 8
        else -> null
    }
}

private fun VideoDynamicRange.isHdrSignal(): Boolean =
    this != VideoDynamicRange.UNKNOWN && this != VideoDynamicRange.SDR

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
        Path
            .of(parsed)
            .toAbsolutePath()
            .normalize()
            .toUri()
            .toASCIIString()
    }.getOrDefault(this)
}

private fun String.isNumericLoopbackHttpMpvSource(): Boolean {
    val uri = runCatching { URI.create(this) }.getOrNull() ?: return false
    if (!uri.scheme.equals("http", ignoreCase = true) || uri.userInfo != null) return false
    if (uri.port !in 1..65535) return false
    val host = uri.host?.removeSurrounding("[", "]")
    return host == "127.0.0.1" || host == "::1"
}
