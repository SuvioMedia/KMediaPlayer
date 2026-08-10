@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
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
import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

internal class LibVlcVideoPlayerState(
    runtime: VlcDesktopRuntimeResolution,
    private val options: LibVlcPlaybackOptions,
) : AbstractBackendVideoPlayerState(),
    VideoPlayerSurfaceProvider {
    private val disposed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outputMutex = Mutex()
    private val frameSignals = Channel<Unit>(Channel.CONFLATED)
    private val renderedFrames = AtomicLong()
    private var outputConfigurationJob: Job? = null
    private val textureStreamController = TextureViewStreamController()
    private val cpuFrameState = mutableStateOf<ImageBitmap?>(null)
    private val retiredCpuImages = ArrayDeque<Bitmap>()
    private val pendingOpenLock = Any()
    private var currentCpuImage: Bitmap? = null
    private val pipelineState = MutableStateFlow(initialPipelineStatus())

    @Volatile
    private var hostCapabilities = TextureViewHostCapabilities.UNAVAILABLE

    @Volatile
    private var surfaceWidth = 0

    @Volatile
    private var surfaceHeight = 0

    @Volatile
    private var lastSubmittedFrame: SubmittedFrame? = null
    private var configuredOutputTarget: VlcOutputTarget? = null
    private var pendingOpen: PendingOpen? = null
    private var pendingTransport: PendingTransport? = null
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
        configureLatestOutput(OUTPUT_RESIZE_DEBOUNCE_MS)
    }

    internal fun updateHostCapabilities(capabilities: TextureViewHostCapabilities) {
        val outputConfigurationChanged = !capabilities.hasSameOutputConfigurationAs(hostCapabilities)
        hostCapabilities = capabilities
        confirmSystemPresent(capabilities)
        if (outputConfigurationChanged) configureLatestOutput()
    }

    internal fun detachSurface() {
        hostCapabilities = TextureViewHostCapabilities.UNAVAILABLE
        configureLatestOutput()
    }

    override fun setBackendVolume(value: Float) {
        check(player.setVolume(value)) { "libVLC rejected volume." }
    }

    override fun setBackendLoop(value: Boolean) {
        check(player.setLoop(value)) { "libVLC rejected loop mode." }
    }

    override fun setBackendPlaybackSpeed(value: Float) {
        check(player.setRate(value)) { "libVLC rejected playback rate." }
    }

    override fun setBackendSubtitleOffset(value: Duration) = Unit

    override fun playBackend() {
        if (updatePendingTransport { it.playWhenReady = true }) return
        check(player.play()) { "libVLC rejected play." }
    }

    override fun pauseBackend() {
        if (updatePendingTransport { it.playWhenReady = false }) return
        check(player.pause()) { "libVLC rejected pause." }
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
        sourceLoadedPublished = false
        synchronized(pendingOpenLock) {
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
            synchronized(pendingOpenLock) { pendingTransport = null }
            publishError(VideoPlayerError.SourceError("libVLC 4 rejected the media source."))
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
            pendingOpen = null
            pendingTransport = null
        }
        runCatching { player.stop() }
        textureStreamController.clear()
        clearCpuFrame()
        sourceLoadedPublished = false
        resetSourceState()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        synchronized(pendingOpenLock) {
            pendingOpen = null
            pendingTransport = null
        }
        frameSignals.close()
        textureStreamController.close()
        clearCpuFrame()
        player.close()
        scope.cancel()
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
                    val configured =
                        runCatching {
                            player.updateOutput(target) &&
                                (target is VlcUnavailableOutputTarget || player.resize(target.width(), target.height()))
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
        return when (val producer = host.producerInfo) {
            is WindowsTextureViewProducerInfo ->
                VlcWindowsOutputTarget(
                    host.generation,
                    surfaceWidth,
                    surfaceHeight,
                    hdr,
                    white,
                    peak,
                    producer.adapterLuid,
                )
            is MacTextureViewProducerInfo ->
                VlcMacOutputTarget(
                    host.generation,
                    surfaceWidth,
                    surfaceHeight,
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
                        surfaceWidth,
                        surfaceHeight,
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
        if (frame.handleType() == VlcNativeHandleType.CPU_ADDRESS) {
            publishCpuFrame(frame)
        } else {
            publishGpuFrame(frame)
        }
    }

    private fun publishGpuFrame(frame: VlcDesktopFrame) {
        val host = hostCapabilities
        if (!usesGpuTexture || projectionRequiresCpuCanvas || frame.generation() != host.generation) {
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
        try {
            val source =
                when (frame.handleType()) {
                    VlcNativeHandleType.D3D11_SHARED_HANDLE ->
                        nucleusD3D11SharedTextureSource(
                            frame.platformHandle(),
                            frame.width(),
                            frame.height(),
                            colorInfo,
                        )
                    VlcNativeHandleType.IOSURFACE ->
                        nucleusIOSurfaceTextureSource(
                            frame.platformHandle(),
                            frame.width(),
                            frame.height(),
                            colorInfo,
                        )
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
            textureStreamController.submitFrame(
                TextureViewFrame(
                    source = source,
                    acquireFenceFd = frame.acquireFenceFd(),
                    onReleased = frame::release,
                ),
            )
        } catch (_: RuntimeException) {
            frame.close()
            return
        }
        val source = frame.sourceDynamicRange().toVideoColorInfo()
        val outputDynamicRange = frame.outputDynamicRange(source)
        lastSubmittedFrame =
            SubmittedFrame(
                serial = frame.serial(),
                outputGeneration = frame.generation(),
                hostGeneration = host.generation,
                presentCountAtSubmission = host.presentedFrameCount,
                source = source,
                outputDynamicRange = outputDynamicRange,
            )
        renderedFrames.incrementAndGet()
        updatePendingPipeline(source, outputDynamicRange)
    }

    private fun publishCpuFrame(frame: VlcDesktopFrame) {
        try {
            val buffer = frame.cpuPixels().orElseThrow()
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val image = Bitmap()
            check(
                image.installPixels(
                    ImageInfo(frame.width(), frame.height(), ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                    bytes,
                    frame.stride(),
                ),
            ) { "Skia rejected the libVLC CPU frame buffer." }
            mutateSnapshotState {
                currentCpuImage?.let(retiredCpuImages::addLast)
                currentCpuImage = image
                cpuFrameState.value = image.asComposeImageBitmap()
                while (retiredCpuImages.size > CPU_IMAGE_GRACE_COUNT) retiredCpuImages.removeFirst().close()
            }
            val source = frame.sourceDynamicRange().toVideoColorInfo()
            lastSubmittedFrame = null
            renderedFrames.incrementAndGet()
            updateCpuPipeline(source)
        } catch (_: RuntimeException) {
            publishError(VideoPlayerError.UnknownError("libVLC 4 CPU frame conversion failed."))
        } finally {
            frame.close()
        }
    }

    private suspend fun pollSnapshotOnce() {
        if (disposed.get()) return
        val snapshot = runCatching { player.snapshot() }.getOrNull() ?: return
        val state = snapshot.state()
        updatePlaybackPosition(
            position = snapshot.positionMicroseconds().microseconds,
            mediaDuration = snapshot.durationMicroseconds().microseconds,
            playing = state.playingSnapshot(),
            loading = state == VlcPlaybackState.OPENING || state == VlcPlaybackState.BUFFERING,
            seeking = if (_isSeeking && state != VlcPlaybackState.BUFFERING) false else null,
        )
        mutateSnapshotState {
            metadata.width = snapshot.videoWidth().takeIf { it > 0 }
            metadata.height = snapshot.videoHeight().takeIf { it > 0 }
            updateAspectRatio(metadata.width, metadata.height)
        }
        applyPendingTransport(snapshot, state)
        if (!sourceLoadedPublished &&
            _hasMedia &&
            state in setOf(VlcPlaybackState.PLAYING, VlcPlaybackState.PAUSED, VlcPlaybackState.BUFFERING)
        ) {
            sourceLoadedPublished = true
            sourceLoaded()
        }
        if (_isSeeking && state != VlcPlaybackState.BUFFERING) emitSeekCompleted()
        if (state == VlcPlaybackState.ENDED && lastPlaybackState != VlcPlaybackState.ENDED) emitPlaybackEnded()
        if (state == VlcPlaybackState.ERROR && lastPlaybackState != VlcPlaybackState.ERROR) {
            publishError(VideoPlayerError.UnknownError("libVLC 4 reported a playback failure."))
        }
        lastPlaybackState = state
    }

    private fun applyPendingTransport(
        snapshot: VlcPlayerSnapshot,
        state: VlcPlaybackState,
    ) {
        if (state !in PLAYABLE_VLC_STATES) return
        val requested = synchronized(pendingOpenLock) { pendingTransport?.copy() } ?: return
        val requestedSeekMicroseconds = requested.seekMicroseconds
        val seekApplied =
            when {
                requestedSeekMicroseconds == null -> true
                !snapshot.seekable() -> false
                else -> player.seek(requestedSeekMicroseconds, false)
            }
        val playbackApplied =
            if (requested.playWhenReady) {
                state == VlcPlaybackState.PLAYING ||
                    state == VlcPlaybackState.BUFFERING ||
                    player.play()
            } else {
                state == VlcPlaybackState.PAUSED || player.pause()
            }
        synchronized(pendingOpenLock) {
            val current = pendingTransport ?: return@synchronized
            if (seekApplied && current.seekMicroseconds == requestedSeekMicroseconds) {
                current.seekMicroseconds = null
            }
            if (playbackApplied &&
                current.playWhenReady == requested.playWhenReady &&
                current.seekMicroseconds == null
            ) {
                pendingTransport = null
            }
        }
    }

    private fun confirmSystemPresent(host: TextureViewHostCapabilities) {
        if (!usesGpuTexture) return
        val submitted =
            lastSubmittedFrame ?: run {
                updatePendingPipeline(VideoColorInfo(), VideoDynamicRange.SDR)
                return
            }
        if (host.generation != submitted.hostGeneration ||
            host.presentationState != TextureViewHostPresentationState.PRESENTED ||
            host.presentedFrameCount <= submitted.presentCountAtSubmission
        ) {
            updatePendingPipeline(submitted.source, submitted.outputDynamicRange)
            return
        }
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
        pipelineState.value =
            initialPipelineStatus().copy(
                display = host.toDisplayCapabilities(),
                source = submitted.source,
                surface =
                    if (usesGpuTexture) VideoSurfaceKind.TEXTURE_VIEW else VideoSurfaceKind.COMPOSE_CANVAS,
                renderer =
                    if (hdrOutput) ColorPipelineRenderer.CONTROLLED_HDR else ColorPipelineRenderer.CONTROLLED_SDR,
                plannedOutputDynamicRange = outputRange,
                outputDynamicRange = outputRange,
                verification = ColorPipelineVerification.RENDERER_CONFIGURED,
                requestHonored = honored,
                fallbackReason =
                    if (honored) {
                        ColorPipelineFallbackReason.NONE
                    } else {
                        ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE
                    },
                detail =
                    "libVLC4 frame=${submitted.serial}, producerGeneration=${submitted.outputGeneration}, " +
                        "hostGeneration=${host.generation}, present=${host.presentedFrameCount}, " +
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
        pipelineState.value =
            initialPipelineStatus().copy(
                display = host.toDisplayCapabilities(),
                source = source,
                surface =
                    if (usesGpuTexture) VideoSurfaceKind.TEXTURE_VIEW else VideoSurfaceKind.COMPOSE_CANVAS,
                renderer =
                    if (hdrOutput) ColorPipelineRenderer.CONTROLLED_HDR else ColorPipelineRenderer.CONTROLLED_SDR,
                plannedOutputDynamicRange = outputDynamicRange,
                outputDynamicRange = VideoDynamicRange.UNKNOWN,
                verification = ColorPipelineVerification.NONE,
                requestHonored = false,
                detail = "A libVLC 4 frame is pending a system Present from the same host generation.",
            )
    }

    private fun updateCpuPipeline(source: VideoColorInfo) {
        pipelineState.value =
            initialPipelineStatus().copy(
                display = hostCapabilities.toDisplayCapabilities(),
                source = source,
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
        cpuFrameState.value = null
        currentCpuImage?.close()
        currentCpuImage = null
        while (retiredCpuImages.isNotEmpty()) retiredCpuImages.removeFirst().close()
    }

    private data class SubmittedFrame(
        val serial: Long,
        val outputGeneration: Long,
        val hostGeneration: Long,
        val presentCountAtSubmission: Long,
        val source: VideoColorInfo,
        val outputDynamicRange: VideoDynamicRange,
    )

    private data class PendingOpen(
        val uri: String,
        val requestHeaders: Map<String, String>,
    )

    private data class PendingTransport(
        var playWhenReady: Boolean,
        var seekMicroseconds: Long? = null,
    )

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val OUTPUT_RESIZE_DEBOUNCE_MS = 200L
        const val CPU_IMAGE_GRACE_COUNT = 3
        const val DEFAULT_SDR_WHITE_NITS = 203f
        const val DEFAULT_HDR_PEAK_NITS = 1_000f
        const val GPU_RENDERER_LABEL = "libVLC 4 GPU TextureView"
        const val CPU_RENDERER_LABEL = "libVLC 4 CPU pull (SDR) -> Compose Canvas (Skia)"
        const val GPU_PROJECTION_ERROR =
            "The active libVLC 4 player was created with GPU_PUSH before projection was requested. " +
                "Recreate it with AUTO or CPU_PULL to render projected, stereo, rotated, or cropped video."
        val PLAYABLE_VLC_STATES =
            setOf(VlcPlaybackState.PLAYING, VlcPlaybackState.PAUSED, VlcPlaybackState.BUFFERING)
    }
}

private fun VlcOutputTarget.isAvailable(): Boolean = this !is VlcUnavailableOutputTarget

private fun TextureViewHostCapabilities.hasSameOutputConfigurationAs(other: TextureViewHostCapabilities): Boolean =
    generation == other.generation &&
        actualDynamicRange == other.actualDynamicRange &&
        (presentationState == TextureViewHostPresentationState.UNAVAILABLE) ==
        (other.presentationState == TextureViewHostPresentationState.UNAVAILABLE) &&
        sdrWhiteLevelNits == other.sdrWhiteLevelNits &&
        maximumLuminanceNits == other.maximumLuminanceNits &&
        headroom == other.headroom &&
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
