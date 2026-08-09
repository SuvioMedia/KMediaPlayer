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
import io.github.kdroidfilter.composemediaplayer.DesktopVideoSurfaceMode
import io.github.kdroidfilter.composemediaplayer.DisplayColorCapabilities
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.LibVlcPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.VideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.effectiveDeliveryMode
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
import io.github.shusek.kmediavlc.runtime.desktop.VlcUnavailableOutputTarget
import io.github.shusek.kmediavlc.runtime.desktop.VlcWindowsOutputTarget
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

internal class LibVlcVideoPlayerState(
    runtime: VlcDesktopRuntimeResolution,
    private val options: LibVlcPlaybackOptions,
) : AbstractBackendVideoPlayerState(), VideoPlayerSurfaceProvider {
    private val disposed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outputMutex = Mutex()
    private val frameSignals = Channel<Unit>(Channel.CONFLATED)
    private val textureStreamController = TextureViewStreamController()
    private val cpuFrameState = mutableStateOf<ImageBitmap?>(null)
    private val retiredCpuImages = ArrayDeque<Bitmap>()
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
    private var sourceLoadedPublished = false
    private var lastPlaybackState = VlcPlaybackState.IDLE

    private val nativeListener =
        object : VlcPlayerListener {
            override fun onFrameAvailable(serial: Long, outputGeneration: Long) {
                frameSignals.trySend(Unit)
            }

            override fun onPlaybackStateChanged(state: VlcPlaybackState, mediaGeneration: Long) {
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
                    "libVLC 4 GPU TextureView"
                } else {
                    "libVLC 4 CPU pull (SDR)"
                },
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

    internal fun updateSurfaceSize(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(0)
        surfaceHeight = height.coerceAtLeast(0)
        configureLatestOutput()
    }

    internal fun updateHostCapabilities(capabilities: TextureViewHostCapabilities) {
        hostCapabilities = capabilities
        confirmSystemPresent(capabilities)
        configureLatestOutput()
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
        check(player.play()) { "libVLC rejected play." }
    }

    override fun pauseBackend() {
        check(player.pause()) { "libVLC rejected pause." }
    }

    override fun seekBackend(time: Duration) {
        check(player.seek(time.inWholeMicroseconds, false)) { "libVLC rejected seek." }
    }

    override fun selectBackendAudioTrack(track: AudioTrack?): TrackSelectionResult = TrackSelectionResult.NotSupported

    override fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult =
        TrackSelectionResult.NotSupported

    override fun disableBackendSubtitles(): TrackSelectionResult = TrackSelectionResult.NotSupported

    override fun validateExternalSubtitle(track: SubtitleTrack) {
        throw UnsupportedOperationException("External subtitles are not exposed by the libVLC 4 bridge yet.")
    }

    override fun removeBackendExternalSubtitle(track: SubtitleTrack) = Unit

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        ensureOpen()
        beginSourcePreparation(uri, initializePlayerState)
        sourceLoadedPublished = false
        val opened =
            runCatching {
                player.open(
                    uri,
                    requestHeaders.sanitizedRequestHeaders(),
                    initializePlayerState == InitialPlayerState.PLAY,
                )
            }.getOrElse {
                publishError(VideoPlayerError.SourceError("libVLC 4 rejected the media source."))
                false
            }
        if (!opened) publishError(VideoPlayerError.SourceError("libVLC 4 rejected the media source."))
    }

    override fun openFile(file: PlatformFile, initializePlayerState: InitialPlayerState) {
        openUri(file.path, initializePlayerState, emptyMap())
    }

    override fun releaseSource() {
        ensureOpen()
        runCatching { player.stop() }
        textureStreamController.clear()
        clearCpuFrame()
        sourceLoadedPublished = false
        resetSourceState()
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        frameSignals.close()
        textureStreamController.close()
        clearCpuFrame()
        player.close()
        scope.cancel()
        resetSourceState()
    }

    private fun configureLatestOutput() {
        if (disposed.get()) return
        scope.launch {
            outputMutex.withLock {
                val target = buildOutputTarget()
                if (target.hasSameConfigurationAs(configuredOutputTarget)) return@withLock
                val configured = runCatching { player.updateOutput(target) }.getOrDefault(false)
                if (configured) {
                    configuredOutputTarget = target
                } else if (target !is VlcUnavailableOutputTarget) {
                    publishColorFailure("libVLC 4 could not configure the active TextureView producer target.")
                }
            }
        }
    }

    private fun buildOutputTarget(): VlcOutputTarget {
        val host = hostCapabilities
        if (!usesGpuTexture || surfaceWidth <= 0 || surfaceHeight <= 0 ||
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
        if (!usesGpuTexture || frame.generation() != host.generation) {
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
        lastSubmittedFrame =
            SubmittedFrame(
                serial = frame.serial(),
                outputGeneration = frame.generation(),
                hostGeneration = host.generation,
                presentCountAtSubmission = host.presentedFrameCount,
                hdr = frame.pixelFormat().extendedLinear(),
            )
        updatePendingPipeline(frame.pixelFormat().extendedLinear())
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
            lastSubmittedFrame =
                SubmittedFrame(frame.serial(), frame.generation(), hostCapabilities.generation, hostCapabilities.presentedFrameCount, false)
            updatePendingPipeline(false)
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
            playing = state == VlcPlaybackState.PLAYING,
            loading = state == VlcPlaybackState.OPENING || state == VlcPlaybackState.BUFFERING,
            seeking = if (_isSeeking && state != VlcPlaybackState.BUFFERING) false else null,
        )
        mutateSnapshotState {
            metadata.width = snapshot.videoWidth().takeIf { it > 0 }
            metadata.height = snapshot.videoHeight().takeIf { it > 0 }
            updateAspectRatio(metadata.width, metadata.height)
        }
        if (!sourceLoadedPublished && _hasMedia &&
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

    private fun confirmSystemPresent(host: TextureViewHostCapabilities) {
        val submitted = lastSubmittedFrame ?: run {
            updatePendingPipeline(false)
            return
        }
        if (host.generation != submitted.hostGeneration ||
            host.presentationState != TextureViewHostPresentationState.PRESENTED ||
            host.presentedFrameCount <= submitted.presentCountAtSubmission
        ) {
            updatePendingPipeline(submitted.hdr)
            return
        }
        val outputRange = if (submitted.hdr) VideoDynamicRange.HDR10 else VideoDynamicRange.SDR
        val honored =
            when (options.dynamicRangePolicy) {
                DynamicRangePolicy.REQUIRE_HDR -> outputRange != VideoDynamicRange.SDR
                DynamicRangePolicy.FORCE_SDR -> outputRange == VideoDynamicRange.SDR
                else -> true
            }
        pipelineState.value =
            initialPipelineStatus().copy(
                display = host.toDisplayCapabilities(),
                surface = if (usesGpuTexture) VideoSurfaceKind.TEXTURE_VIEW else VideoSurfaceKind.COMPOSE_CANVAS,
                renderer = if (submitted.hdr) ColorPipelineRenderer.CONTROLLED_HDR else ColorPipelineRenderer.CONTROLLED_SDR,
                plannedOutputDynamicRange = outputRange,
                outputDynamicRange = outputRange,
                verification = ColorPipelineVerification.SYSTEM_REPORTED,
                requestHonored = honored,
                fallbackReason =
                    if (honored) ColorPipelineFallbackReason.NONE else ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
                detail =
                    "libVLC4 frame=${submitted.serial}, producerGeneration=${submitted.outputGeneration}, " +
                        "hostGeneration=${host.generation}, present=${host.presentedFrameCount}, " +
                        "format=${host.outputPixelFormat}",
            )
    }

    private fun updatePendingPipeline(hdrFrame: Boolean) {
        val host = hostCapabilities
        pipelineState.value =
            initialPipelineStatus().copy(
                display = host.toDisplayCapabilities(),
                surface = if (usesGpuTexture) VideoSurfaceKind.TEXTURE_VIEW else VideoSurfaceKind.COMPOSE_CANVAS,
                renderer = if (hdrFrame) ColorPipelineRenderer.CONTROLLED_HDR else ColorPipelineRenderer.CONTROLLED_SDR,
                plannedOutputDynamicRange = if (hdrFrame) VideoDynamicRange.HDR10 else VideoDynamicRange.SDR,
                outputDynamicRange = VideoDynamicRange.UNKNOWN,
                verification = ColorPipelineVerification.NONE,
                requestHonored = false,
                detail = "A libVLC 4 frame is pending a system Present from the same host generation.",
            )
    }

    private fun initialPipelineStatus(): VideoColorPipelineStatus =
        VideoColorPipelineStatus(
            requestedDynamicRangePolicy = options.dynamicRangePolicy,
            requestedDolbyVisionPolicy = options.dolbyVisionPolicy,
            surface =
                if (options.desktopVideoSurfaceMode == DesktopVideoSurfaceMode.COMPOSE) {
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
            publishError(VideoPlayerError.ColorPipelineError(ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE, message))
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
        val hdr: Boolean,
    )

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val CPU_IMAGE_GRACE_COUNT = 3
        const val DEFAULT_SDR_WHITE_NITS = 203f
        const val DEFAULT_HDR_PEAK_NITS = 1_000f
    }
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
