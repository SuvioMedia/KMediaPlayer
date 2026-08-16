@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.layout.ContentScale
import dev.nucleusframework.window.tao.LinuxTextureViewProducerInfo
import dev.nucleusframework.window.tao.MacTextureViewProducerInfo
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
import dev.nucleusframework.window.tao.nucleusD3D11SharedTextureSource
import dev.nucleusframework.window.tao.nucleusDmaBufTextureSource
import dev.nucleusframework.window.tao.nucleusIOSurfaceTextureSource
import io.github.kdroidfilter.composemediaplayer.AbstractBackendVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.ColorPipelineRenderer
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DynamicMetadataHandling
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.JvmMediaAdvancedControls
import io.github.kdroidfilter.composemediaplayer.JvmMediaThumbnail
import io.github.kdroidfilter.composemediaplayer.LibVlcFrameDeliveryPolicy
import io.github.kdroidfilter.composemediaplayer.LibVlcPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.PlaybackDiagnostics
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.VideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.desktop.tao.desktopCanvasRendererLabel
import io.github.kdroidfilter.composemediaplayer.desktop.tao.usesDesktopCanvasProjectionRenderer
import io.github.kdroidfilter.composemediaplayer.effectiveDeliveryMode
import io.github.kdroidfilter.composemediaplayer.renderingInfoLabel
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopFrame
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopPlayer
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopPlayerConfig
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopRuntimeResolution
import io.github.shusek.kmediavlc.runtime.desktop.VlcFrameDeliveryMode
import io.github.shusek.kmediavlc.runtime.desktop.VlcLinuxOutputTarget
import io.github.shusek.kmediavlc.runtime.desktop.VlcMacIOSurface
import io.github.shusek.kmediavlc.runtime.desktop.VlcMacOutputTarget
import io.github.shusek.kmediavlc.runtime.desktop.VlcNativeHandleType
import io.github.shusek.kmediavlc.runtime.desktop.VlcOutputTarget
import io.github.shusek.kmediavlc.runtime.desktop.VlcPlaybackState
import io.github.shusek.kmediavlc.runtime.desktop.VlcPlayerListener
import io.github.shusek.kmediavlc.runtime.desktop.VlcPlayerSnapshot
import io.github.shusek.kmediavlc.runtime.desktop.VlcSourceDynamicRange
import io.github.shusek.kmediavlc.runtime.desktop.VlcUnavailableOutputTarget
import io.github.shusek.kmediavlc.runtime.desktop.VlcWindowsOutputTarget
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val LIBVLC_THUMBNAIL_JPEG_QUALITY = 72
private const val LIBVLC_THUMBNAIL_MAXIMUM_HEIGHT_MULTIPLIER = 2
private val LIBVLC_THUMBNAIL_OPEN_TIMEOUT = 30.seconds
private val LIBVLC_THUMBNAIL_SEEK_TIMEOUT = 10.seconds
private val LIBVLC_THUMBNAIL_POSITION_TOLERANCE = 1.seconds

private fun libVlcThumbnailHeight(
    maximumWidth: Int,
    aspectRatio: Float,
): Int {
    val normalizedAspectRatio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
    return (maximumWidth / normalizedAspectRatio)
        .roundToInt()
        .coerceIn(1, maximumWidth * LIBVLC_THUMBNAIL_MAXIMUM_HEIGHT_MULTIPLIER)
}

private fun ImageBitmap.toLibVlcMediaThumbnail(
    timestamp: Duration,
    maximumWidth: Int,
): JvmMediaThumbnail? =
    runCatching {
        val targetWidth = width.coerceAtMost(maximumWidth)
        val targetHeight =
            (height.toDouble() * targetWidth / width)
                .roundToInt()
                .coerceAtLeast(1)
        Image.makeFromBitmap(asSkiaBitmap()).use { source ->
            val encodedBytes =
                if (targetWidth == width && targetHeight == height) {
                    source.encodeToData(EncodedImageFormat.JPEG, LIBVLC_THUMBNAIL_JPEG_QUALITY)?.use { it.bytes }
                } else {
                    Surface.makeRasterN32Premul(targetWidth, targetHeight).use { surface ->
                        surface.canvas.drawImageRect(
                            source,
                            Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                        )
                        surface.makeImageSnapshot().use { scaled ->
                            scaled
                                .encodeToData(
                                    EncodedImageFormat.JPEG,
                                    LIBVLC_THUMBNAIL_JPEG_QUALITY,
                                )?.use { it.bytes }
                        }
                    }
                }
            encodedBytes?.let { bytes ->
                JvmMediaThumbnail(
                    bytes = bytes,
                    mimeType = "image/jpeg",
                    timestamp = timestamp,
                    width = targetWidth,
                    height = targetHeight,
                )
            }
        }
    }.getOrNull()

internal class LibVlcVideoPlayerState(
    private val runtime: VlcDesktopRuntimeResolution,
    private val options: LibVlcPlaybackOptions,
) : AbstractBackendVideoPlayerState(),
    VideoPlayerSurfaceProvider,
    JvmMediaAdvancedControls {
    private val disposed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outputMutex = Mutex()
    private val frameSignals = Channel<Unit>(Channel.CONFLATED)
    private val renderedFrames = AtomicLong()
    private val droppedFrames = AtomicLong()
    private val lastAcquiredFrameSerial = AtomicLong()
    private val frameRateEstimator = LibVlcFrameRateEstimator()

    @Volatile
    private var authoritativeFrameRate = false
    private var outputConfigurationJob: Job? = null
    private val textureStreamController = TextureViewStreamController()
    private val cpuFrameState = mutableStateOf<ImageBitmap?>(null)
    private val cpuFrameLock = Any()
    private val pendingOpenLock = Any()
    private val presentationEvidenceLock = Any()
    private val pipelineState = MutableStateFlow(initialPipelineStatus())

    @Volatile
    private var hostCapabilities = TextureViewHostCapabilities.UNAVAILABLE

    @Volatile
    private var surfaceWidth = 0

    @Volatile
    private var surfaceHeight = 0

    @Volatile
    private var sourceVideoWidth = 0

    @Volatile
    private var sourceVideoHeight = 0

    @Volatile
    private var lastSubmittedFrame: SubmittedFrame? = null
    private var confirmedPresentationKey: PresentationKey? = null
    private var configuredOutputTarget: VlcOutputTarget? = null
    private var pendingOpen: PendingOpen? = null
    private var activeOpen: PendingOpen? = null
    private var pendingTransport: PendingTransport? = null
    private var desiredPlayWhenReady = false
    private var sourceLoadedPublished = false
    private var lastPlaybackState = VlcPlaybackState.IDLE
    private var gpuProjectionRejected = false
    private val nativeListener =
        object : VlcPlayerListener {
            override fun onFrameAvailable(
                serial: Long,
                outputGeneration: Long,
            ) {
                frameSignals.trySend(Unit)
            }

            override fun onPlaybackStateChanged(
                state: VlcPlaybackState,
                mediaGeneration: Long,
            ) {
                // State is copied by the bounded polling loop. Avoid re-entering a player while it is created.
            }
        }

    private val player =
        VlcDesktopPlayer.create(
            runtime,
            VlcDesktopPlayerConfig(
                options.effectiveDeliveryMode(),
                options.dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR,
                DEFAULT_SDR_WHITE_NITS,
                DEFAULT_HDR_PEAK_NITS,
                nativeListener,
            ),
        )

    override val renderingInfo =
        VideoRenderingInfo(
            backend = "KMediaVlc ${runtime.capabilities().libVlcVersion()}",
            videoRenderer =
                if (options.effectiveDeliveryMode() == VlcFrameDeliveryMode.GPU_PUSH) {
                    GPU_RENDERER_LABEL
                } else {
                    options.projection.desktopCanvasRendererLabel(
                        baseRenderer = CPU_RENDERER_LABEL,
                        textureCrop = options.projectionTextureCrop,
                    )
                },
            videoProjection = options.projection.renderingInfoLabel(),
        )

    override val diagnostics: PlaybackDiagnostics
        get() =
            PlaybackDiagnostics(
                renderedVideoFrames = renderedFrames.get(),
                droppedVideoFrames = droppedFrames.get(),
                videoWidth = metadata.width,
                videoHeight = metadata.height,
                notes = renderingInfo.notes,
            )

    override val colorPipelineStatus: StateFlow<VideoColorPipelineStatus> = pipelineState.asStateFlow()

    override val capabilities =
        PlayerCapabilities(
            supportsMkv = true,
            supportsHls = true,
            supportedUriSchemes = setOf("file", "http", "https", "rtsp", "rtmp", "smb"),
        )

    override val preciseCurrentTime: Duration
        get() =
            if (disposed.get()) {
                _currentTime
            } else {
                runCatching { player.snapshot().positionMicroseconds().microseconds }.getOrDefault(_currentTime)
            }

    override val backendDisposed: Boolean
        get() = disposed.get()

    init {
        projection = options.projection.normalized()
        projectionView = options.projectionView.normalized()
        projectionViewControlMode = options.projectionViewControlMode
        projectionTextureCrop = options.projectionTextureCrop.normalized()
        scope.launch {
            while (isActive) {
                frameSignals.receiveCatching().getOrNull() ?: break
                pullLatestFrame()
            }
        }
        scope.launch {
            while (isActive) {
                pollSnapshotOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    @Composable
    override fun RenderVideoPlayerSurface(
        modifier: Modifier,
        contentScale: ContentScale,
        overlay: @Composable () -> Unit,
    ) {
        LibVlcVideoPlayerSurface(this, modifier, contentScale, overlay)
    }

    internal val usesGpuTexture: Boolean
        get() = options.effectiveDeliveryMode() == VlcFrameDeliveryMode.GPU_PUSH

    internal val streamController: TextureViewStreamController
        get() = textureStreamController

    internal val currentCpuFrame: State<ImageBitmap?>
        get() = cpuFrameState

    internal val projectionRequiresCpuCanvas: Boolean
        get() = projection.usesDesktopCanvasProjectionRenderer(projectionTextureCrop)

    internal fun updateProjectionRenderingRoute() {
        val projectionRequired = projectionRequiresCpuCanvas
        renderingInfo.videoProjection = projection.renderingInfoLabel()
        if (!usesGpuTexture) {
            renderingInfo.videoRenderer =
                projection.desktopCanvasRendererLabel(
                    baseRenderer = CPU_RENDERER_LABEL,
                    textureCrop = projectionTextureCrop,
                )
            gpuProjectionRejected = false
            return
        }
        if (!projectionRequired) {
            renderingInfo.videoRenderer = GPU_RENDERER_LABEL
            val restoreGpuOutput = gpuProjectionRejected
            gpuProjectionRejected = false
            if (restoreGpuOutput) configureLatestOutput()
            return
        }
        renderingInfo.videoRenderer = "$GPU_RENDERER_LABEL (projection unavailable)"
        if (gpuProjectionRejected) return
        gpuProjectionRejected = true
        textureStreamController.clear()
        configureLatestOutput()
        runCatching { player.pause() }
        publishError(VideoPlayerError.UnknownError(GPU_PROJECTION_ERROR))
    }

    internal fun updateSurfaceSize(
        width: Int,
        height: Int,
    ) {
        val nextWidth = width.coerceAtLeast(0)
        val nextHeight = height.coerceAtLeast(0)
        if (surfaceWidth == nextWidth && surfaceHeight == nextHeight) return
        surfaceWidth = nextWidth
        surfaceHeight = nextHeight
        if (!usesGpuTexture) {
            if (nextWidth > 0 && nextHeight > 0) runCatching { player.resize(nextWidth, nextHeight) }
            return
        }
        configureLatestOutput(OUTPUT_RESIZE_DEBOUNCE_MS)
    }

    internal fun updateHostCapabilities(capabilities: TextureViewHostCapabilities) {
        val outputConfigurationChanged = !capabilities.hasSameOutputConfigurationAs(hostCapabilities)
        hostCapabilities = capabilities
        if (outputConfigurationChanged) clearPresentationEvidence()
        confirmSystemPresent(capabilities)
        if (outputConfigurationChanged) configureLatestOutput()
    }

    internal fun detachSurface() {
        hostCapabilities = TextureViewHostCapabilities.UNAVAILABLE
        clearPresentationEvidence()
        configureLatestOutput()
    }

    override fun setBackendVolume(value: Float) {
        if (updatePendingTransport { it.volume = value }) return
        check(player.setVolume(value)) { "libVLC rejected volume." }
    }

    override fun setBackendLoop(value: Boolean) {
        check(player.setLoop(value)) { "libVLC rejected loop mode." }
    }

    override fun setBackendPlaybackSpeed(value: Float) {
        if (updatePendingTransport { it.playbackRate = value }) return
        check(player.setRate(value)) { "libVLC rejected playback rate." }
    }

    override fun setBackendSubtitleOffset(value: Duration) = Unit

    override fun playBackend() {
        val shouldDispatch =
            synchronized(pendingOpenLock) {
                val alreadyRequested = desiredPlayWhenReady
                desiredPlayWhenReady = true
                pendingTransport?.let { pending ->
                    if (!pending.playWhenReady) {
                        pending.playWhenReady = true
                        pending.playbackCommandIssued = false
                    }
                    return@synchronized false
                }
                !alreadyRequested
            }
        if (shouldDispatch && !player.play()) {
            synchronized(pendingOpenLock) { desiredPlayWhenReady = false }
            error("libVLC rejected play.")
        }
    }

    override fun pauseBackend() {
        val shouldDispatch =
            synchronized(pendingOpenLock) {
                val alreadyRequested = !desiredPlayWhenReady
                desiredPlayWhenReady = false
                pendingTransport?.let { pending ->
                    if (pending.playWhenReady) {
                        pending.playWhenReady = false
                        pending.playbackCommandIssued = false
                    }
                    return@synchronized false
                }
                !alreadyRequested
            }
        if (shouldDispatch && !player.pause()) {
            synchronized(pendingOpenLock) { desiredPlayWhenReady = true }
            error("libVLC rejected pause.")
        }
    }

    override fun seekBackend(time: Duration) {
        if (updatePendingTransport { it.seekMicroseconds = time.inWholeMicroseconds }) return
        check(player.seek(time.inWholeMicroseconds, false)) { "libVLC rejected seek." }
    }

    private inline fun updatePendingTransport(update: (PendingTransport) -> Unit): Boolean =
        synchronized(pendingOpenLock) {
            pendingTransport?.let(update) != null
        }

    override fun selectBackendAudioTrack(track: AudioTrack?): TrackSelectionResult = TrackSelectionResult.NotSupported

    override fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult =
        TrackSelectionResult.NotSupported

    override fun disableBackendSubtitles(): TrackSelectionResult = TrackSelectionResult.NotSupported

    override fun validateExternalSubtitle(track: SubtitleTrack): Unit =
        throw UnsupportedOperationException("External subtitles are not exposed by the libVLC 4 bridge yet.")

    override fun removeBackendExternalSubtitle(track: SubtitleTrack) = Unit

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        ensureOpen()
        beginSourcePreparation(uri, initializePlayerState)
        resetSourceGeometry()
        resetFrameRateEstimator()
        sourceLoadedPublished = false
        synchronized(pendingOpenLock) {
            activeOpen = null
            desiredPlayWhenReady = initializePlayerState == InitialPlayerState.PLAY
            pendingOpen =
                PendingOpen(
                    uri = uri,
                    requestHeaders = requestHeaders.sanitizedRequestHeaders(),
                )
            pendingTransport =
                PendingTransport(
                    playWhenReady = initializePlayerState == InitialPlayerState.PLAY,
                )
        }
        openPendingSourceIfReady()
    }

    private fun openPendingSourceIfReady() {
        val request =
            synchronized(pendingOpenLock) {
                if (usesGpuTexture && configuredOutputTarget?.isAvailable() != true) return
                pendingOpen.also { pendingOpen = null }
            } ?: return
        val opened =
            runCatching {
                player.open(
                    request.uri,
                    request.requestHeaders,
                    true,
                )
            }.getOrElse {
                publishError(VideoPlayerError.SourceError("libVLC 4 rejected the media source."))
                false
            }
        if (!opened) {
            synchronized(pendingOpenLock) {
                activeOpen = null
                pendingTransport = null
                desiredPlayWhenReady = false
            }
            publishError(VideoPlayerError.SourceError("libVLC 4 rejected the media source."))
        } else {
            synchronized(pendingOpenLock) {
                activeOpen = request
                // open(..., autoplay = true) already issued the initial PLAY.
                // Sending PLAY again while libVLC 4 is opening/prerolling restarts the input.
                pendingTransport?.playbackCommandIssued = pendingTransport?.playWhenReady == true
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        openUri(file.path, initializePlayerState, emptyMap())
    }

    override fun releaseSource() {
        ensureOpen()
        synchronized(pendingOpenLock) {
            activeOpen = null
            pendingOpen = null
            pendingTransport = null
            desiredPlayWhenReady = false
        }
        runCatching { player.stop() }
        textureStreamController.clear()
        clearCpuFrame()
        clearPresentationEvidence()
        sourceLoadedPublished = false
        resetSourceGeometry()
        resetSourceState()
    }

    override suspend fun thumbnails(
        positions: List<Duration>,
        maximumWidth: Int,
        emit: suspend (index: Int, thumbnail: JvmMediaThumbnail?) -> Unit,
    ) {
        require(maximumWidth > 0) { "maximumWidth must be positive." }
        if (positions.isEmpty()) return

        val request = synchronized(pendingOpenLock) { activeOpen ?: pendingOpen }
        if (request == null) {
            positions.indices.forEach { index -> emit(index, null) }
            return
        }
        val preview =
            LibVlcVideoPlayerState(
                runtime = runtime,
                options =
                    options.copy(
                        frameDeliveryPolicy = LibVlcFrameDeliveryPolicy.CPU_PULL,
                        dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                        desktopVideoSurfaceMode = DesktopVideoSurfaceMode.COMPOSE,
                    ),
            )
        try {
            preview.updateSurfaceSize(maximumWidth, libVlcThumbnailHeight(maximumWidth, aspectRatio))
            preview.openUri(request.uri, InitialPlayerState.PAUSE, request.requestHeaders)
            val opened =
                withTimeoutOrNull(LIBVLC_THUMBNAIL_OPEN_TIMEOUT) {
                    while (!preview.hasMedia && preview.error == null) delay(25.milliseconds)
                    preview.hasMedia
                } == true
            if (!opened) {
                positions.indices.forEach { index -> emit(index, null) }
                return
            }

            preview.pause()
            preview.updateSurfaceSize(maximumWidth, libVlcThumbnailHeight(maximumWidth, preview.aspectRatio))
            positions.forEachIndexed { index, requestedPosition ->
                currentCoroutineContext().ensureActive()
                val maximumPosition = (preview.duration - 1.milliseconds).coerceAtLeast(Duration.ZERO)
                val position = requestedPosition.coerceIn(Duration.ZERO, maximumPosition)
                val previousFrame = preview.currentCpuFrame.value
                preview.seekTo(position)
                val frame =
                    withTimeoutOrNull<ImageBitmap>(LIBVLC_THUMBNAIL_SEEK_TIMEOUT) {
                        var settledFrame: ImageBitmap? = null
                        while (settledFrame == null) {
                            currentCoroutineContext().ensureActive()
                            val candidate = preview.currentCpuFrame.value
                            val positionSettled =
                                abs((preview.currentTime - position).inWholeMilliseconds) <=
                                    LIBVLC_THUMBNAIL_POSITION_TOLERANCE.inWholeMilliseconds
                            if (candidate != null &&
                                candidate !== previousFrame &&
                                positionSettled &&
                                !preview.isSeeking
                            ) {
                                settledFrame = candidate
                            } else {
                                delay(25.milliseconds)
                            }
                        }
                        settledFrame
                    }
                val thumbnail =
                    frame?.let { image ->
                        withContext(Dispatchers.Default) {
                            image.toLibVlcMediaThumbnail(position, maximumWidth)
                        }
                    }
                emit(index, thumbnail)
            }
        } finally {
            preview.dispose()
        }
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        synchronized(pendingOpenLock) {
            activeOpen = null
            pendingOpen = null
            pendingTransport = null
            desiredPlayWhenReady = false
        }
        frameSignals.close()
        textureStreamController.close()
        clearCpuFrame()
        clearPresentationEvidence()
        player.close()
        scope.cancel()
        resetSourceGeometry()
        resetSourceState()
    }

    private fun configureLatestOutput(delayMillis: Long = 0L) {
        if (disposed.get()) return
        outputConfigurationJob?.cancel()
        outputConfigurationJob =
            scope.launch {
                if (delayMillis > 0L) delay(delayMillis)
                outputMutex.withLock {
                    val target = buildOutputTarget()
                    if (target.hasSameConfigurationAs(configuredOutputTarget)) return@withLock
                    clearPresentationEvidence()
                    val configured =
                        runCatching {
                            player.updateOutput(target)
                        }.getOrDefault(false)
                    if (configured) {
                        configuredOutputTarget = target
                        if (target.isAvailable()) openPendingSourceIfReady()
                    } else if (target !is VlcUnavailableOutputTarget) {
                        publishColorFailure("libVLC 4 could not configure the active TextureView producer target.")
                    }
                }
            }
    }

    private fun buildOutputTarget(): VlcOutputTarget {
        val host = hostCapabilities
        if (!usesGpuTexture ||
            projectionRequiresCpuCanvas ||
            surfaceWidth <= 0 ||
            surfaceHeight <= 0 ||
            host.presentationState == TextureViewHostPresentationState.UNAVAILABLE
        ) {
            return VlcUnavailableOutputTarget(host.generation)
        }
        val hdr =
            options.dynamicRangePolicy != DynamicRangePolicy.FORCE_SDR &&
                host.actualDynamicRange == TextureViewHostDynamicRange.HDR &&
                host.outputPixelFormat != TextureViewHostPixelFormat.RGBA8_SRGB
        if (options.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR && !hdr) {
            publishColorFailure("The active desktop output cannot satisfy required HDR presentation.")
            return VlcUnavailableOutputTarget(host.generation)
        }
        val white = host.sdrWhiteLevelNits ?: DEFAULT_SDR_WHITE_NITS
        val peak = (host.maximumLuminanceNits ?: (white * host.headroom)).coerceAtLeast(white)
        // libVLC's custom OpenGL output renders into the framebuffer dimensions reported by its
        // window callback. Keep that framebuffer at the source display geometry; asking libVLC to
        // render directly into a smaller viewport can leave the IOSurface containing only the
        // corresponding source sub-rectangle. TextureView performs the final fit/crop scaling on
        // the GPU, so this does not introduce a CPU copy.
        val outputSize =
            sourceSizedLibVlcOutputSize(
                viewportWidth = surfaceWidth,
                viewportHeight = surfaceHeight,
                sourceWidth = sourceVideoWidth,
                sourceHeight = sourceVideoHeight,
            )
        return when (val producer = host.producerInfo) {
            is WindowsTextureViewProducerInfo ->
                VlcWindowsOutputTarget(
                    host.generation,
                    outputSize.width,
                    outputSize.height,
                    hdr,
                    white,
                    peak,
                    producer.adapterLuid,
                )
            is MacTextureViewProducerInfo ->
                VlcMacOutputTarget(
                    host.generation,
                    outputSize.width,
                    outputSize.height,
                    hdr,
                    white,
                    peak,
                    producer.device,
                    producer.commandQueue,
                )
            is LinuxTextureViewProducerInfo -> {
                val formatModifiers =
                    producer.formats.flatMap { format ->
                        (format.modifiers.ifEmpty { listOf(NucleusDrmFormat.MODIFIER_INVALID) })
                            .map { modifier -> format.format to modifier }
                    }
                if (producer.renderNode == null || formatModifiers.isEmpty()) {
                    VlcUnavailableOutputTarget(host.generation)
                } else {
                    VlcLinuxOutputTarget(
                        host.generation,
                        outputSize.width,
                        outputSize.height,
                        hdr,
                        white,
                        peak,
                        producer.renderNode,
                        formatModifiers.map { it.first }.toIntArray(),
                        formatModifiers.map { it.second }.toLongArray(),
                        producer.supportsAcquireFences,
                        producer.supportsReleaseFences,
                    )
                }
            }
            else -> VlcUnavailableOutputTarget(host.generation)
        }
    }

    private fun pullLatestFrame() {
        if (disposed.get()) return
        val frame = runCatching { player.acquireLatestFrame().orElse(null) }.getOrNull() ?: return
        recordAcquiredFrameSerial(frame.serial())
        updateFrameRateEstimate(frame.serial(), frame.ptsMicroseconds())
        if (frame.handleType() == VlcNativeHandleType.CPU_ADDRESS) {
            publishCpuFrame(frame)
        } else {
            publishGpuFrame(frame)
        }
    }

    private fun publishGpuFrame(frame: VlcDesktopFrame) {
        val host = hostCapabilities
        if (!usesGpuTexture || projectionRequiresCpuCanvas || frame.generation() != host.generation) {
            droppedFrames.incrementAndGet()
            frame.close()
            return
        }
        val colorInfo =
            if (frame.pixelFormat().extendedLinear()) {
                TextureColorInfo(
                    encoding = TextureColorEncoding.EXTENDED_LINEAR_SRGB,
                    premultipliedAlpha = frame.premultipliedAlpha(),
                    sdrWhiteLevelNits = frame.sdrWhiteNits(),
                )
            } else {
                TextureColorInfo.SRGB_PREMULTIPLIED
            }
        var macIOSurface: VlcMacIOSurface? = null
        val source =
            try {
                when (frame.handleType()) {
                    VlcNativeHandleType.D3D11_SHARED_HANDLE ->
                        nucleusD3D11SharedTextureSource(
                            frame.platformHandle(),
                            frame.width(),
                            frame.height(),
                            colorInfo,
                        )
                    VlcNativeHandleType.IOSURFACE -> {
                        val retainedSurface =
                            frame.retainMacIOSurface().orElse(null) ?: error("IOSurface lookup failed.")
                        macIOSurface = retainedSurface
                        nucleusIOSurfaceTextureSource(
                            retainedSurface.address(),
                            frame.width(),
                            frame.height(),
                            colorInfo,
                        )
                    }
                    VlcNativeHandleType.DMABUF ->
                        nucleusDmaBufTextureSource(
                            fd = frame.platformHandle().toInt(),
                            widthPx = frame.width(),
                            heightPx = frame.height(),
                            stride = frame.stride(),
                            fourcc = frame.fourcc(),
                            offset = frame.offset(),
                            modifier = frame.modifier(),
                            colorInfo = colorInfo,
                        )
                    VlcNativeHandleType.CPU_ADDRESS -> error("CPU frames use the pull-copy path.")
                }
            } catch (_: RuntimeException) {
                droppedFrames.incrementAndGet()
                macIOSurface?.close()
                frame.close()
                return
            }
        val sourceColorInfo = frame.sourceDynamicRange().toVideoColorInfo()
        val outputDynamicRange = frame.outputDynamicRange(sourceColorInfo)
        val submittedFrame =
            SubmittedFrame(
                serial = frame.serial(),
                outputGeneration = frame.generation(),
                hostGeneration = host.generation,
                source = sourceColorInfo,
                outputDynamicRange = outputDynamicRange,
            )
        val previousEvidence = recordSubmittedFrame(submittedFrame)
        try {
            textureStreamController.submitFrame(
                TextureViewFrame(
                    source = source,
                    acquireFenceFd = frame.acquireFenceFd(),
                    onReleased = { releaseFenceFd ->
                        try {
                            frame.release(releaseFenceFd)
                        } finally {
                            macIOSurface?.close()
                        }
                    },
                ),
            )
        } catch (_: RuntimeException) {
            restorePresentationEvidence(submittedFrame, previousEvidence)
            droppedFrames.incrementAndGet()
            macIOSurface?.close()
            frame.close()
            return
        }
        renderedFrames.incrementAndGet()
        // Presentation diagnostics must never revoke a frame whose ownership
        // has already transferred to TextureViewStreamController.
        runCatching { confirmSystemPresent(hostCapabilities) }
    }

    private fun publishCpuFrame(frame: VlcDesktopFrame) {
        var image: Bitmap? = null
        var published = false
        try {
            val buffer = frame.cpuPixels().orElseThrow()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image = Bitmap()
            check(
                image.installPixels(
                    ImageInfo(frame.width(), frame.height(), ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                    bytes,
                    frame.stride(),
                ),
            ) { "Skia rejected the libVLC CPU frame buffer." }
            val composeImage = image.asComposeImageBitmap()
            synchronized(cpuFrameLock) {
                if (disposed.get()) return
                mutateSnapshotState { cpuFrameState.value = composeImage }
                published = true
            }
            val source = frame.sourceDynamicRange().toVideoColorInfo()
            clearPresentationEvidence()
            renderedFrames.incrementAndGet()
            updateCpuPipeline(source)
        } catch (_: RuntimeException) {
            droppedFrames.incrementAndGet()
            publishError(VideoPlayerError.UnknownError("libVLC 4 CPU frame conversion failed."))
        } finally {
            // ImageBitmap keeps the backing Skia Bitmap reachable for as long as Compose can draw it.
            // Closing a published Bitmap here or during teardown races an in-flight draw on the UI thread.
            if (!published) image?.close()
            frame.close()
        }
    }

    private fun recordAcquiredFrameSerial(serial: Long) {
        if (serial <= 0L) return
        val previous = lastAcquiredFrameSerial.getAndSet(serial)
        val skipped = skippedLibVlcFrameCount(previous, serial)
        if (skipped > 0L) droppedFrames.addAndGet(skipped)
    }

    private fun updateFrameRateEstimate(
        serial: Long,
        ptsMicroseconds: Long,
    ) {
        if (authoritativeFrameRate) return
        val framesPerSecond = frameRateEstimator.observe(serial, ptsMicroseconds) ?: return
        mutateSnapshotState { metadata.frameRate = framesPerSecond }
    }

    private fun resetFrameRateEstimator() {
        authoritativeFrameRate = false
        frameRateEstimator.reset()
    }

    private fun resetSourceGeometry() {
        sourceVideoWidth = 0
        sourceVideoHeight = 0
    }

    private suspend fun pollSnapshotOnce() {
        if (disposed.get()) return
        val snapshot = runCatching { player.snapshot() }.getOrNull() ?: return
        val state = snapshot.state()
        val snapshotVideoWidth = snapshot.videoWidth().takeIf { it > 0 }
        val snapshotVideoHeight = snapshot.videoHeight().takeIf { it > 0 }
        val sourceGeometryChanged =
            snapshotVideoWidth != null &&
                snapshotVideoHeight != null &&
                (sourceVideoWidth != snapshotVideoWidth || sourceVideoHeight != snapshotVideoHeight)
        if (sourceGeometryChanged) {
            sourceVideoWidth = snapshotVideoWidth
            sourceVideoHeight = snapshotVideoHeight
        }
        val nativeFrameRate =
            snapshot
                .videoFrameRateDenominator()
                .takeIf { it > 0 }
                ?.let { denominator -> snapshot.videoFrameRateNumerator().toDouble() / denominator }
                ?.takeIf { it.isFinite() && it in 1.0..240.0 }
                ?.toFloat()
        if (nativeFrameRate != null) authoritativeFrameRate = true
        updatePlaybackPosition(
            position = snapshot.positionMicroseconds().microseconds,
            mediaDuration = snapshot.durationMicroseconds().microseconds,
            playing = state.playingSnapshot(),
            loading = state == VlcPlaybackState.OPENING || state == VlcPlaybackState.BUFFERING,
            seeking = if (_isSeeking && state != VlcPlaybackState.BUFFERING) false else null,
        )
        mutateSnapshotState {
            metadata.width = snapshotVideoWidth
            metadata.height = snapshotVideoHeight
            if (nativeFrameRate != null) metadata.frameRate = nativeFrameRate
            updateAspectRatio(metadata.width, metadata.height)
        }
        if (sourceGeometryChanged) configureLatestOutput()
        val transportReady = applyPendingTransport(snapshot, state)
        if (!sourceLoadedPublished &&
            transportReady &&
            _hasMedia &&
            state in setOf(VlcPlaybackState.PLAYING, VlcPlaybackState.PAUSED, VlcPlaybackState.BUFFERING)
        ) {
            sourceLoadedPublished = true
            sourceLoaded()
        }
        if (_isSeeking && state != VlcPlaybackState.BUFFERING) emitSeekCompleted()
        if (state == VlcPlaybackState.ENDED && lastPlaybackState != VlcPlaybackState.ENDED) {
            if (_loop) {
                val request = synchronized(pendingOpenLock) { activeOpen }
                val restarted =
                    request != null &&
                        runCatching {
                            player.open(
                                request.uri,
                                request.requestHeaders,
                                true,
                            )
                        }.getOrDefault(false)
                if (restarted) {
                    emitPlaybackEnded(looping = true)
                } else {
                    clearPlaybackIntent()
                    publishError(VideoPlayerError.SourceError("libVLC 4 could not restart loop playback."))
                }
            } else {
                clearPlaybackIntent()
                emitPlaybackEnded(looping = false)
            }
        }
        if (state == VlcPlaybackState.ERROR && lastPlaybackState != VlcPlaybackState.ERROR) {
            clearPlaybackIntent()
            publishError(VideoPlayerError.UnknownError("libVLC 4 reported a playback failure."))
        }
        if (state == VlcPlaybackState.STOPPED && lastPlaybackState in PLAYABLE_VLC_STATES) {
            clearPlaybackIntent()
        }
        lastPlaybackState = state
    }

    private fun clearPlaybackIntent() {
        synchronized(pendingOpenLock) {
            desiredPlayWhenReady = false
            pendingTransport = null
        }
    }

    private fun applyPendingTransport(
        snapshot: VlcPlayerSnapshot,
        state: VlcPlaybackState,
    ): Boolean {
        if (state !in PLAYABLE_VLC_STATES) return false
        val requested = synchronized(pendingOpenLock) { pendingTransport?.copy() } ?: return true
        val requestedVolume = requested.volume
        val volumeApplied = requestedVolume == null || player.setVolume(requestedVolume)
        val requestedPlaybackRate = requested.playbackRate
        val playbackRateApplied = requestedPlaybackRate == null || player.setRate(requestedPlaybackRate)
        val requestedSeekMicroseconds = requested.seekMicroseconds
        val seekApplied =
            when {
                requestedSeekMicroseconds == null -> true
                !snapshot.seekable() -> false
                else -> player.seek(requestedSeekMicroseconds, false)
            }
        var playbackCommandIssued = requested.playbackCommandIssued
        val playbackApplied =
            if (!volumeApplied || !playbackRateApplied) {
                false
            } else {
                when (state.pendingTransportAction(requested.playWhenReady, requested.playbackCommandIssued)) {
                    VlcPendingTransportAction.APPLIED -> true
                    VlcPendingTransportAction.PLAY -> {
                        playbackCommandIssued = player.play()
                        false
                    }
                    VlcPendingTransportAction.PAUSE -> {
                        playbackCommandIssued = player.pause()
                        false
                    }
                    VlcPendingTransportAction.WAIT -> false
                }
            }
        return synchronized(pendingOpenLock) {
            val current = pendingTransport ?: return@synchronized true
            if (volumeApplied && current.volume == requestedVolume) {
                current.volume = null
            }
            if (playbackRateApplied && current.playbackRate == requestedPlaybackRate) {
                current.playbackRate = null
            }
            if (seekApplied && current.seekMicroseconds == requestedSeekMicroseconds) {
                current.seekMicroseconds = null
            }
            if (playbackCommandIssued && current.playWhenReady == requested.playWhenReady) {
                current.playbackCommandIssued = true
            }
            if (playbackApplied &&
                current.playWhenReady == requested.playWhenReady &&
                current.volume == null &&
                current.playbackRate == null &&
                current.seekMicroseconds == null
            ) {
                pendingTransport = null
            }
            pendingTransport == null
        }
    }

    private fun confirmSystemPresent(host: TextureViewHostCapabilities) {
        if (!usesGpuTexture) return
        synchronized(presentationEvidenceLock) {
            val submitted = lastSubmittedFrame ?: return
            val key = submitted.presentationKey()
            if (confirmedPresentationKey != key &&
                host.generation == submitted.hostGeneration &&
                host.presentationState == TextureViewHostPresentationState.PRESENTED &&
                host.presentedFrameCount > 0L
            ) {
                confirmedPresentationKey = key
            }
            if (isPresentationConfirmed(host, submitted)) {
                updateConfirmedPipeline(host, submitted)
            } else {
                updatePendingPipeline(submitted.source, submitted.outputDynamicRange)
            }
        }
    }

    private fun recordSubmittedFrame(submitted: SubmittedFrame): PresentationEvidenceSnapshot =
        synchronized(presentationEvidenceLock) {
            val snapshot =
                PresentationEvidenceSnapshot(
                    lastSubmittedFrame = lastSubmittedFrame,
                    confirmedPresentationKey = confirmedPresentationKey,
                    pipelineStatus = pipelineState.value,
                )
            lastSubmittedFrame = submitted
            val host = hostCapabilities
            if (isPresentationConfirmed(host, submitted)) {
                updateConfirmedPipeline(host, submitted)
            } else {
                updatePendingPipeline(submitted.source, submitted.outputDynamicRange)
            }
            snapshot
        }

    private fun restorePresentationEvidence(
        failedSubmission: SubmittedFrame,
        snapshot: PresentationEvidenceSnapshot,
    ) {
        synchronized(presentationEvidenceLock) {
            if (lastSubmittedFrame !== failedSubmission) return
            lastSubmittedFrame = snapshot.lastSubmittedFrame
            confirmedPresentationKey = snapshot.confirmedPresentationKey
            pipelineState.value = snapshot.pipelineStatus
        }
    }

    private fun clearPresentationEvidence() {
        synchronized(presentationEvidenceLock) {
            lastSubmittedFrame = null
            confirmedPresentationKey = null
        }
    }

    private fun isPresentationConfirmed(
        host: TextureViewHostCapabilities,
        submitted: SubmittedFrame,
    ): Boolean =
        confirmedPresentationKey == submitted.presentationKey() &&
            host.generation == submitted.hostGeneration &&
            host.presentationState == TextureViewHostPresentationState.PRESENTED

    private fun updateConfirmedPipeline(
        host: TextureViewHostCapabilities,
        submitted: SubmittedFrame,
    ) {
        val outputRange = submitted.outputDynamicRange
        val hdrOutput =
            outputRange != VideoDynamicRange.SDR &&
                outputRange != VideoDynamicRange.UNKNOWN
        val honored =
            when (options.dynamicRangePolicy) {
                DynamicRangePolicy.REQUIRE_HDR -> outputRange != VideoDynamicRange.SDR
                DynamicRangePolicy.FORCE_SDR -> outputRange == VideoDynamicRange.SDR
                else -> true
            }
        val metadataHandling = submitted.source.libVlcMetadataHandling()
        pipelineState.value =
            initialPipelineStatus().copy(
                display = host.toDisplayCapabilities(),
                source = submitted.source,
                decoderName = LIBVLC_DECODER_LABEL,
                surface =
                    if (usesGpuTexture) VideoSurfaceKind.TEXTURE_VIEW else VideoSurfaceKind.COMPOSE_CANVAS,
                renderer =
                    if (hdrOutput) ColorPipelineRenderer.CONTROLLED_HDR else ColorPipelineRenderer.CONTROLLED_SDR,
                plannedOutputDynamicRange = outputRange,
                outputDynamicRange = outputRange,
                plannedMetadataHandling = metadataHandling,
                metadataHandling = metadataHandling,
                verification = ColorPipelineVerification.RENDERER_CONFIGURED,
                requestHonored = honored,
                fallbackReason =
                    if (honored) {
                        ColorPipelineFallbackReason.NONE
                    } else {
                        ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
                    },
                detail =
                    "libVLC4 submitted frame=${submitted.serial}, " +
                        "producerGeneration=${submitted.outputGeneration}; " +
                        "Nucleus hostGeneration=${host.generation} is system-presented, " +
                        "presentMarker=${host.presentedFrameCount}, " +
                        "format=${host.outputPixelFormat}",
            )
    }

    private fun updatePendingPipeline(
        source: VideoColorInfo,
        outputDynamicRange: VideoDynamicRange,
    ) {
        val host = hostCapabilities
        val hdrOutput =
            outputDynamicRange != VideoDynamicRange.SDR &&
                outputDynamicRange != VideoDynamicRange.UNKNOWN
        val plannedMetadataHandling = source.libVlcMetadataHandling()
        pipelineState.value =
            initialPipelineStatus().copy(
                display = host.toDisplayCapabilities(),
                source = source,
                decoderName =
                    LIBVLC_DECODER_LABEL.takeIf {
                        source.dynamicRange != VideoDynamicRange.UNKNOWN
                    },
                surface =
                    if (usesGpuTexture) VideoSurfaceKind.TEXTURE_VIEW else VideoSurfaceKind.COMPOSE_CANVAS,
                renderer =
                    if (hdrOutput) ColorPipelineRenderer.CONTROLLED_HDR else ColorPipelineRenderer.CONTROLLED_SDR,
                plannedOutputDynamicRange = outputDynamicRange,
                outputDynamicRange = VideoDynamicRange.UNKNOWN,
                plannedMetadataHandling = plannedMetadataHandling,
                metadataHandling = DynamicMetadataHandling.NONE,
                verification = ColorPipelineVerification.NONE,
                requestHonored = false,
                detail =
                    "A libVLC 4 frame is submitted, but its Nucleus host generation has not " +
                        "completed a system Present (host=${host.generation}, " +
                        "presentMarker=${host.presentedFrameCount}).",
            )
    }

    private fun updateCpuPipeline(source: VideoColorInfo) {
        pipelineState.value =
            initialPipelineStatus().copy(
                display = hostCapabilities.toDisplayCapabilities(),
                source = source,
                decoderName = LIBVLC_DECODER_LABEL,
                surface = VideoSurfaceKind.COMPOSE_CANVAS,
                renderer = ColorPipelineRenderer.CONTROLLED_SDR,
                plannedOutputDynamicRange = VideoDynamicRange.SDR,
                outputDynamicRange = VideoDynamicRange.SDR,
                verification = ColorPipelineVerification.RENDERER_CONFIGURED,
                requestHonored = options.dynamicRangePolicy != DynamicRangePolicy.REQUIRE_HDR,
                fallbackReason =
                    cpuPipelineFallbackReason(
                        policy = options.dynamicRangePolicy,
                        source = source,
                        projectionRequired = projectionRequiresCpuCanvas,
                    ),
                detail = "libVLC 4 copied a bounded RGBA8 frame into the Compose/Skia SDR renderer.",
            )
    }

    private fun initialPipelineStatus(): VideoColorPipelineStatus =
        VideoColorPipelineStatus(
            requestedDynamicRangePolicy = options.dynamicRangePolicy,
            requestedDolbyVisionPolicy = options.dolbyVisionPolicy,
            surface =
                if (options.effectiveDeliveryMode() == VlcFrameDeliveryMode.CPU_PULL) {
                    VideoSurfaceKind.COMPOSE_CANVAS
                } else {
                    VideoSurfaceKind.TEXTURE_VIEW
                },
        )

    private fun publishColorFailure(message: String) {
        pipelineState.value =
            initialPipelineStatus().copy(
                display = hostCapabilities.toDisplayCapabilities(),
                fallbackReason = ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
                detail = message,
            )
        if (options.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR) {
            publishError(
                VideoPlayerError.ColorPipelineError(ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE, message),
            )
        }
    }

    private fun clearCpuFrame() {
        synchronized(cpuFrameLock) {
            mutateSnapshotState { cpuFrameState.value = null }
        }
    }

    private data class SubmittedFrame(
        val serial: Long,
        val outputGeneration: Long,
        val hostGeneration: Long,
        val source: VideoColorInfo,
        val outputDynamicRange: VideoDynamicRange,
    ) {
        fun presentationKey(): PresentationKey =
            PresentationKey(
                hostGeneration = hostGeneration,
                outputDynamicRange = outputDynamicRange,
            )
    }

    private data class PresentationKey(
        val hostGeneration: Long,
        val outputDynamicRange: VideoDynamicRange,
    )

    private data class PresentationEvidenceSnapshot(
        val lastSubmittedFrame: SubmittedFrame?,
        val confirmedPresentationKey: PresentationKey?,
        val pipelineStatus: VideoColorPipelineStatus,
    )

    private data class PendingOpen(
        val uri: String,
        val requestHeaders: Map<String, String>,
    )

    private data class PendingTransport(
        var playWhenReady: Boolean,
        var playbackCommandIssued: Boolean = false,
        var volume: Float? = null,
        var playbackRate: Float? = null,
        var seekMicroseconds: Long? = null,
    )

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val OUTPUT_RESIZE_DEBOUNCE_MS = 200L
        const val DEFAULT_SDR_WHITE_NITS = 203f
        const val DEFAULT_HDR_PEAK_NITS = 1_000f
        const val GPU_RENDERER_LABEL = "libVLC 4 GPU TextureView"
        const val CPU_RENDERER_LABEL = "libVLC 4 CPU pull (SDR) -> Compose Canvas (Skia)"
        const val LIBVLC_DECODER_LABEL = "libVLC 4 decoder (component not exposed)"
        const val GPU_PROJECTION_ERROR =
            "The active libVLC 4 player was created with GPU_PUSH before projection was requested. " +
                "Recreate it with AUTO or CPU_PULL to render projected, stereo, rotated, or cropped video."
        val PLAYABLE_VLC_STATES =
            setOf(VlcPlaybackState.PLAYING, VlcPlaybackState.PAUSED, VlcPlaybackState.BUFFERING)
    }
}

internal data class LibVlcOutputSize(
    val width: Int,
    val height: Int,
)

internal fun sourceSizedLibVlcOutputSize(
    viewportWidth: Int,
    viewportHeight: Int,
    sourceWidth: Int,
    sourceHeight: Int,
): LibVlcOutputSize {
    val width = viewportWidth.coerceAtLeast(0)
    val height = viewportHeight.coerceAtLeast(0)
    if (width == 0 || height == 0 || sourceWidth <= 0 || sourceHeight <= 0) {
        return LibVlcOutputSize(width, height)
    }
    return LibVlcOutputSize(
        width = sourceWidth,
        height = sourceHeight,
    )
}

internal fun skippedLibVlcFrameCount(
    previousSerial: Long,
    currentSerial: Long,
): Long =
    when {
        currentSerial <= 0L -> 0L
        previousSerial == 0L -> currentSerial - 1L
        currentSerial > previousSerial -> currentSerial - previousSerial - 1L
        else -> 0L
    }

internal class LibVlcFrameRateEstimator {
    private var anchorSerial = 0L
    private var anchorPtsMicroseconds = -1L

    @Synchronized
    fun observe(
        serial: Long,
        ptsMicroseconds: Long,
    ): Float? {
        if (serial <= 0L || ptsMicroseconds < 0L) return null
        if (anchorSerial <= 0L || serial <= anchorSerial || ptsMicroseconds < anchorPtsMicroseconds) {
            anchorSerial = serial
            anchorPtsMicroseconds = ptsMicroseconds
            return null
        }
        val frameCount = serial - anchorSerial
        val durationMicroseconds = ptsMicroseconds - anchorPtsMicroseconds
        if (frameCount < MINIMUM_SAMPLE_FRAMES || durationMicroseconds < MINIMUM_SAMPLE_DURATION_US) {
            return null
        }
        val framesPerSecond = frameCount.toDouble() * MICROSECONDS_PER_SECOND / durationMicroseconds.toDouble()
        return framesPerSecond
            .takeIf { it in MINIMUM_FPS..MAXIMUM_FPS }
            ?.toFloat()
    }

    @Synchronized
    fun reset() {
        anchorSerial = 0L
        anchorPtsMicroseconds = -1L
    }

    private companion object {
        const val MINIMUM_SAMPLE_FRAMES = 5L
        const val MINIMUM_SAMPLE_DURATION_US = 500_000L
        const val MICROSECONDS_PER_SECOND = 1_000_000.0
        const val MINIMUM_FPS = 1.0
        const val MAXIMUM_FPS = 240.0
    }
}

private fun VlcOutputTarget.isAvailable(): Boolean = this !is VlcUnavailableOutputTarget

internal fun TextureViewHostCapabilities.hasSameOutputConfigurationAs(other: TextureViewHostCapabilities): Boolean =
    generation == other.generation &&
        actualDynamicRange == other.actualDynamicRange &&
        (presentationState == TextureViewHostPresentationState.UNAVAILABLE) ==
        (other.presentationState == TextureViewHostPresentationState.UNAVAILABLE) &&
        outputPixelFormat == other.outputPixelFormat &&
        producerInfo == other.producerInfo

internal fun VlcSourceDynamicRange.toVideoColorInfo(): VideoColorInfo =
    when (this) {
        VlcSourceDynamicRange.UNKNOWN -> VideoColorInfo()
        VlcSourceDynamicRange.SDR ->
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.SDR,
                transfer = VideoColorTransfer.SDR,
            )
        VlcSourceDynamicRange.HDR10 ->
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HDR10,
                transfer = VideoColorTransfer.PQ,
            )
        VlcSourceDynamicRange.HLG ->
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.HLG,
                transfer = VideoColorTransfer.HLG,
            )
    }

private fun VlcDesktopFrame.outputDynamicRange(source: VideoColorInfo): VideoDynamicRange =
    if (!pixelFormat().extendedLinear()) {
        VideoDynamicRange.SDR
    } else {
        source.dynamicRange.takeUnless { it == VideoDynamicRange.UNKNOWN } ?: VideoDynamicRange.HDR10
    }

private fun VideoColorInfo.libVlcMetadataHandling(): DynamicMetadataHandling =
    if (isHdr) DynamicMetadataHandling.PASSTHROUGH else DynamicMetadataHandling.NONE

internal fun VlcPlaybackState.playingSnapshot(): Boolean? =
    when (this) {
        VlcPlaybackState.PLAYING -> true
        VlcPlaybackState.IDLE,
        VlcPlaybackState.PAUSED,
        VlcPlaybackState.STOPPED,
        VlcPlaybackState.ENDED,
        VlcPlaybackState.ERROR,
        -> false
        VlcPlaybackState.OPENING,
        VlcPlaybackState.BUFFERING,
        -> null
    }

internal enum class VlcPendingTransportAction {
    APPLIED,
    PLAY,
    PAUSE,
    WAIT,
}

internal fun VlcPlaybackState.pendingTransportAction(
    playWhenReady: Boolean,
    playbackCommandIssued: Boolean = false,
): VlcPendingTransportAction =
    when {
        playWhenReady && this == VlcPlaybackState.PLAYING -> VlcPendingTransportAction.APPLIED
        playWhenReady && playbackCommandIssued -> VlcPendingTransportAction.WAIT
        playWhenReady && this == VlcPlaybackState.PAUSED -> VlcPendingTransportAction.PLAY
        !playWhenReady && this == VlcPlaybackState.PAUSED -> VlcPendingTransportAction.APPLIED
        !playWhenReady && playbackCommandIssued -> VlcPendingTransportAction.WAIT
        !playWhenReady && this == VlcPlaybackState.PLAYING -> VlcPendingTransportAction.PAUSE
        else -> VlcPendingTransportAction.WAIT
    }

internal fun cpuPipelineFallbackReason(
    policy: DynamicRangePolicy,
    source: VideoColorInfo,
    projectionRequired: Boolean,
): ColorPipelineFallbackReason =
    when {
        policy == DynamicRangePolicy.FORCE_SDR -> ColorPipelineFallbackReason.EXPLICIT_SDR_REQUEST
        source.isHdr && projectionRequired -> ColorPipelineFallbackReason.HDR_PROJECTION_UNAVAILABLE
        source.isHdr -> ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
        else -> ColorPipelineFallbackReason.NONE
    }

private fun TextureViewHostCapabilities.toDisplayCapabilities(): DisplayColorCapabilities =
    DisplayColorCapabilities(
        isKnown = presentationState != TextureViewHostPresentationState.UNAVAILABLE,
        supportedDynamicRanges =
            if (actualDynamicRange == TextureViewHostDynamicRange.HDR) {
                setOf(VideoDynamicRange.SDR, VideoDynamicRange.HDR10, VideoDynamicRange.HLG)
            } else {
                setOf(VideoDynamicRange.SDR)
            },
        maxLuminanceNits = maximumLuminanceNits,
        referenceWhiteNits = sdrWhiteLevelNits,
    )

private fun VlcOutputTarget.hasSameConfigurationAs(other: VlcOutputTarget?): Boolean {
    if (other == null || javaClass != other.javaClass) return false
    if (generation() != other.generation() ||
        width() != other.width() ||
        height() != other.height() ||
        hdr() != other.hdr() ||
        sdrWhiteNits() != other.sdrWhiteNits() ||
        peakNits() != other.peakNits()
    ) {
        return false
    }
    return when (this) {
        is VlcUnavailableOutputTarget -> true
        is VlcWindowsOutputTarget -> adapterLuid() == (other as VlcWindowsOutputTarget).adapterLuid()
        is VlcMacOutputTarget ->
            metalDevice() == (other as VlcMacOutputTarget).metalDevice() &&
                metalCommandQueue() == other.metalCommandQueue()
        is VlcLinuxOutputTarget ->
            renderNode() == (other as VlcLinuxOutputTarget).renderNode() &&
                drmFormats().contentEquals(other.drmFormats()) &&
                drmModifiers().contentEquals(other.drmModifiers()) &&
                acquireFences() == other.acquireFences() &&
                releaseFences() == other.releaseFences()
    }
}
