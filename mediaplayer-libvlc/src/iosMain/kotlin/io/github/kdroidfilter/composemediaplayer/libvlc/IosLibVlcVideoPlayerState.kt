@file:OptIn(
    io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.github.kdroidfilter.composemediaplayer.libvlc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.AbstractBackendVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ColorPipelineRenderer
import io.github.kdroidfilter.composemediaplayer.ColorPipelineVerification
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.LibVlcPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.RendererColorCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoColorPipelineStatus
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.VideoSurfaceKind
import io.github.kdroidfilter.composemediaplayer.isDefaultTextureCrop
import io.github.kdroidfilter.composemediaplayer.renderingInfoLabel
import io.github.kdroidfilter.composemediaplayer.requiresProjectionRenderer
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import kotlinx.cinterop.convert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIImage
import platform.posix.memcpy
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

private const val SEEK_COMPLETION_TOLERANCE_MICROSECONDS = 750_000L

@Stable
internal class IosLibVlcVideoPlayerState(
    private val options: LibVlcPlaybackOptions,
    private val engine: IosLibVlcEngine,
) : AbstractBackendVideoPlayerState(),
    VideoPlayerSurfaceProvider {
    private val lifecycleLock = NSRecursiveLock()
    private var disposed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pollingJob: Job
    private val frameState = mutableStateOf<UIImage?>(null)
    private val pipelineState =
        MutableStateFlow(
            VideoColorPipelineStatus(
                requestedDynamicRangePolicy = options.dynamicRangePolicy,
                requestedDolbyVisionPolicy = options.dolbyVisionPolicy,
                surface = VideoSurfaceKind.NATIVE_LAYER,
                renderer = ColorPipelineRenderer.CONTROLLED_SDR,
                rendererCapabilities = RendererColorCapabilities(),
                plannedOutputDynamicRange = VideoDynamicRange.SDR,
                outputDynamicRange = VideoDynamicRange.SDR,
                verification = ColorPipelineVerification.RENDERER_CONFIGURED,
                requestHonored = true,
                detail = "KMediaVlc iOS CPU pull is copied into an RGBA8/sRGB CoreGraphics image.",
            ),
        )

    private var pendingTransport: PendingTransport? = null
    private var activeMediaRequest: PendingOpen? = null
    private var activeMediaGeneration: ULong? = null
    private var pendingSeekTargetMicroseconds: Long? = null
    private var sourceLoadedPublished = false
    private var lastPlaybackState = IosLibVlcPlaybackState.IDLE
    private var lastFrameSerial: ULong? = null
    private var rejectedProjectionRoute: String? = null

    init {
        projection = options.projection.normalized()
        projectionView = options.projectionView.normalized()
        projectionViewControlMode = options.projectionViewControlMode
        projectionTextureCrop = options.projectionTextureCrop.normalized()
        pollingJob = scope.launch { pollPlayback() }
    }

    @Composable
    override fun RenderVideoPlayerSurface(
        modifier: Modifier,
        contentScale: ContentScale,
        overlay: @Composable () -> Unit,
    ) {
        IosLibVlcVideoPlayerSurface(
            playerState = this,
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
    }

    override val renderingInfo =
        VideoRenderingInfo(
            backend = "KMediaVlc ABI 2 / bundled libVLC 4",
            videoRenderer = IOS_CPU_RENDERER,
            audioRenderer = "libVLC AudioUnit",
            subtitleRenderer = "libVLC/libass",
            notes =
                "Application-embedded, code-signed KMediaVlc XCFramework graph. " +
                    "The first iOS route is bounded CPU-pull RGBA8/sRGB; HDR and projection fail closed.",
        )

    override val capabilities =
        PlayerCapabilities(
            supportsMkv = true,
            supportsHls = true,
            supportedUriSchemes = setOf("file", "http", "https"),
        )

    override val colorPipelineStatus: StateFlow<VideoColorPipelineStatus> = pipelineState.asStateFlow()

    override val preciseCurrentTime: Duration
        get() =
            if (isDisposed() || !_hasMedia) {
                _currentTime
            } else {
                runCatching {
                    val snapshot = engine.snapshot()
                    lifecycleLock.withLock {
                        if (activeMediaGeneration == snapshot.mediaGeneration) {
                            snapshot.positionMicroseconds.microseconds
                        } else {
                            _currentTime
                        }
                    }
                }.getOrDefault(_currentTime)
            }

    override val backendDisposed: Boolean
        get() = isDisposed()

    internal val currentFrame: State<UIImage?>
        get() = frameState

    internal val projectionUnsupported: Boolean
        get() = projection.requiresProjectionRenderer || !projectionTextureCrop.isDefaultTextureCrop

    override fun setBackendVolume(value: Float) {
        if (updatePendingTransport { it.volume = value }) return
        if (!_hasMedia) return
        check(engine.setVolume(value)) { "KMediaVlc rejected iOS volume." }
    }

    override fun setBackendLoop(value: Boolean) {
        check(engine.setLoop(false)) { "KMediaVlc rejected iOS loop mode." }
    }

    override fun setBackendPlaybackSpeed(value: Float) {
        if (updatePendingTransport { it.playbackRate = value }) return
        if (!_hasMedia) return
        check(engine.setRate(value)) { "KMediaVlc rejected iOS playback rate." }
    }

    override fun setBackendSubtitleOffset(value: Duration) = Unit

    override fun playBackend() {
        if (updatePendingTransport { it.playWhenReady = true }) return
        check(engine.play()) { "KMediaVlc rejected iOS play." }
    }

    override fun pauseBackend() {
        if (updatePendingTransport { it.playWhenReady = false }) return
        check(engine.pause()) { "KMediaVlc rejected iOS pause." }
    }

    override fun seekBackend(time: Duration) {
        val target = time.inWholeMicroseconds
        if (updatePendingTransport { it.seekMicroseconds = target }) return
        check(engine.seek(target, false)) { "KMediaVlc rejected iOS seek." }
        lifecycleLock.withLock { pendingSeekTargetMicroseconds = target }
    }

    override fun selectBackendAudioTrack(track: AudioTrack?): TrackSelectionResult = TrackSelectionResult.NotSupported

    override fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult =
        TrackSelectionResult.NotSupported

    override fun disableBackendSubtitles(): TrackSelectionResult = TrackSelectionResult.NotSupported

    override fun validateExternalSubtitle(track: SubtitleTrack): Unit =
        throw UnsupportedOperationException("The iOS KMediaVlc bridge does not expose subtitle tracks yet.")

    override fun removeBackendExternalSubtitle(track: SubtitleTrack) = Unit

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        ensureOpen()
        val location = validatedMediaLocation(uri)
        val headers = validatedIosLibVlcRequestHeaders(requestHeaders)
        lifecycleLock.withLock {
            check(!disposed) { "The KMediaVlc iOS player has been disposed." }
            if (_hasMedia) runCatching { engine.stop() }
            beginSourcePreparation(uri, initializePlayerState)
            frameState.value = null
            sourceLoadedPublished = false
            lastPlaybackState = IosLibVlcPlaybackState.IDLE
            lastFrameSerial = null
            pendingSeekTargetMicroseconds = null
            activeMediaGeneration = null
            activeMediaRequest = null
            pendingTransport =
                PendingTransport(
                    playWhenReady = initializePlayerState == InitialPlayerState.PLAY,
                    volume = _volume,
                    playbackRate = _playbackSpeed,
                )
            val request = PendingOpen(location, headers)
            val generation =
                runCatching {
                    if (engine.open(request.location, request.requestHeaders, true)) {
                        engine.snapshot().mediaGeneration
                    } else {
                        null
                    }
                }.getOrNull()
            if (generation != null) {
                activeMediaGeneration = generation
                activeMediaRequest = request
            } else {
                failActiveSource("KMediaVlc rejected the iOS media source.")
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        openUri(file.path, initializePlayerState, emptyMap())
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        ensureOpen()
        require(fileName.isNotBlank() && !fileName.startsWith('/') && ".." !in fileName.split('/')) {
            "The asset path must be relative and must not contain parent traversal."
        }
        val resourceRoot =
            requireNotNull(NSBundle.mainBundle.resourcePath) {
                "The iOS application bundle has no resource directory."
            }
        val path = "$resourceRoot/$fileName"
        require(NSFileManager.defaultManager.isReadableFileAtPath(path)) {
            "The requested iOS application asset does not exist."
        }
        openUri(path, initializePlayerState, emptyMap())
    }

    override fun releaseSource() {
        ensureOpen()
        lifecycleLock.withLock {
            pendingTransport = null
            pendingSeekTargetMicroseconds = null
            activeMediaGeneration = null
            activeMediaRequest = null
            sourceLoadedPublished = false
            lastPlaybackState = IosLibVlcPlaybackState.IDLE
            lastFrameSerial = null
            runCatching { engine.stop() }
            frameState.value = null
            resetSourceState()
        }
    }

    internal fun updateRenderingRoute() {
        val rejection =
            lifecycleLock.withLock {
                if (disposed) return
                renderingInfo.videoProjection = projection.renderingInfoLabel()
                val currentRejection =
                    if (projectionUnsupported) {
                        IOS_PROJECTION_ERROR
                    } else {
                        null
                    }
                if (currentRejection == null) {
                    renderingInfo.videoRenderer = IOS_CPU_RENDERER
                    rejectedProjectionRoute = null
                    return
                }
                renderingInfo.videoRenderer = "$IOS_CPU_RENDERER (requested projection unavailable)"
                if (rejectedProjectionRoute == currentRejection) return
                rejectedProjectionRoute = currentRejection
                frameState.value = null
                runCatching { engine.pause() }
                currentRejection
            }
        publishError(VideoPlayerError.UnknownError(rejection))
    }

    private inline fun updatePendingTransport(update: (PendingTransport) -> Unit): Boolean =
        lifecycleLock.withLock {
            pendingTransport?.let(update) != null
        }

    private suspend fun pollPlayback() {
        var lastSnapshotAt = 0L
        try {
            while (scope.isActive && !isDisposed()) {
                val hasActiveMedia = lifecycleLock.withLock { activeMediaGeneration != null }
                if (hasActiveMedia) {
                    val now = currentTimeMillis()
                    if (now - lastSnapshotAt >= SNAPSHOT_INTERVAL_MS) {
                        refreshSnapshot()
                        lastSnapshotAt = now
                    }
                    refreshFrame()
                    delay(if (_isPlaying) PLAYING_FRAME_INTERVAL_MS else PAUSED_FRAME_INTERVAL_MS)
                } else {
                    delay(IDLE_INTERVAL_MS)
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            if (!isDisposed()) {
                publishError(VideoPlayerError.UnknownError("KMediaVlc iOS playback monitoring failed."))
            }
        }
    }

    private fun refreshSnapshot() {
        val snapshot = engine.snapshot()
        lifecycleLock.withLock {
            if (disposed || activeMediaGeneration != snapshot.mediaGeneration) return
            val state = snapshot.state
            updatePlaybackPosition(
                position = snapshot.positionMicroseconds.microseconds,
                mediaDuration = snapshot.durationMicroseconds.microseconds,
                playing = state.playingSnapshot(),
                loading = state == IosLibVlcPlaybackState.OPENING || state == IosLibVlcPlaybackState.BUFFERING,
                seeking = null,
            )
            mutateSnapshotState {
                metadata.duration = snapshot.durationMicroseconds.takeIf { it > 0 }?.microseconds
                metadata.width = snapshot.videoWidth.takeIf { it > 0 }
                metadata.height = snapshot.videoHeight.takeIf { it > 0 }
                updateAspectRatio(metadata.width, metadata.height)
            }
            val seekApplied = applyPendingTransport(snapshot, state)
            if (!sourceLoadedPublished && state in PLAYABLE_STATES) {
                sourceLoadedPublished = true
                sourceLoaded()
            }
            val pendingSeekTarget = pendingSeekTargetMicroseconds
            if (shouldCompleteIosLibVlcSeek(
                    isSeeking = _isSeeking,
                    state = state,
                    positionMicroseconds = snapshot.positionMicroseconds,
                    targetMicroseconds = pendingSeekTarget,
                    seekAppliedThisSnapshot = seekApplied,
                )
            ) {
                pendingSeekTargetMicroseconds = null
                emitSeekCompleted()
            }
            val previousState = lastPlaybackState
            lastPlaybackState = state
            if (state == IosLibVlcPlaybackState.ENDED && previousState != IosLibVlcPlaybackState.ENDED) {
                if (_loop) {
                    if (!restartLoopedMedia()) {
                        failActiveSource("KMediaVlc could not restart looped iOS media.")
                    }
                } else {
                    emitPlaybackEnded(looping = false)
                }
            }
            if (state == IosLibVlcPlaybackState.ERROR && previousState != IosLibVlcPlaybackState.ERROR) {
                publishError(VideoPlayerError.UnknownError("KMediaVlc reported an iOS playback failure."))
            }
        }
    }

    private fun applyPendingTransport(
        snapshot: IosLibVlcSnapshot,
        state: IosLibVlcPlaybackState,
    ): Boolean {
        if (state !in PLAYABLE_STATES) return false
        val requested = pendingTransport?.copy() ?: return false
        val requestedSeek = requested.seekMicroseconds
        val seekApplied =
            when {
                requestedSeek == null -> true
                !snapshot.isSeekable -> false
                else -> engine.seek(requestedSeek, false)
            }
        if (requestedSeek != null && seekApplied) {
            pendingSeekTargetMicroseconds = requestedSeek
        }
        val volumeApplied = requested.volume?.let(engine::setVolume) ?: true
        val playbackRateApplied = requested.playbackRate?.let(engine::setRate) ?: true
        val playbackApplied =
            if (requested.playWhenReady) {
                state == IosLibVlcPlaybackState.PLAYING ||
                    state == IosLibVlcPlaybackState.BUFFERING ||
                    engine.play()
            } else {
                state == IosLibVlcPlaybackState.PAUSED || engine.pause()
            }
        val current = pendingTransport
        if (current != null) {
            if (seekApplied && current.seekMicroseconds == requestedSeek) current.seekMicroseconds = null
            if (volumeApplied && current.volume == requested.volume) current.volume = null
            if (playbackRateApplied && current.playbackRate == requested.playbackRate) {
                current.playbackRate = null
            }
            if (playbackApplied &&
                current.playWhenReady == requested.playWhenReady &&
                current.seekMicroseconds == null &&
                current.volume == null &&
                current.playbackRate == null
            ) {
                pendingTransport = null
            }
        }
        return requestedSeek != null && seekApplied
    }

    private fun restartLoopedMedia(): Boolean {
        val request = activeMediaRequest ?: return false
        val generation =
            runCatching {
                if (engine.open(request.location, request.requestHeaders, true)) {
                    engine.snapshot().mediaGeneration
                } else {
                    null
                }
            }.getOrNull() ?: return false
        activeMediaGeneration = generation
        pendingSeekTargetMicroseconds = null
        pendingTransport =
            PendingTransport(
                playWhenReady = true,
                volume = _volume,
                playbackRate = _playbackSpeed,
            )
        lastFrameSerial = null
        frameState.value = null
        mutateSnapshotState {
            _currentTime = Duration.ZERO
            _sliderPos = 0f
            _isPlaying = true
            _isLoading = true
        }
        emitPlaybackEnded(looping = true)
        return true
    }

    private fun refreshFrame() {
        val frame = engine.acquireLatestFrame() ?: return
        try {
            val expectedGeneration =
                lifecycleLock.withLock {
                    if (disposed || projectionUnsupported || lastFrameSerial == frame.info.serial) return
                    activeMediaGeneration
                }
            if (expectedGeneration == null || frame.info.outputGeneration != expectedGeneration) return
            val image = frame.toUiImage()
            lifecycleLock.withLock {
                if (disposed || activeMediaGeneration != expectedGeneration || projectionUnsupported) return
                lastFrameSerial = frame.info.serial
                mutateSnapshotState { frameState.value = image }
            }
        } catch (_: Throwable) {
            lifecycleLock.withLock {
                if (!disposed && _hasMedia) {
                    failActiveSource("KMediaVlc returned an invalid iOS CPU frame.")
                }
            }
        } finally {
            frame.close()
        }
    }

    private fun IosLibVlcFrame.toUiImage(): UIImage {
        val frameInfo = info
        require(frameInfo.width in 1..MAX_IOS_FRAME_DIMENSION)
        require(frameInfo.height in 1..MAX_IOS_FRAME_DIMENSION)
        require(frameInfo.premultipliedAlpha)
        val minimumStride = frameInfo.width.toLong() * BYTES_PER_PIXEL
        require(frameInfo.stride.toLong() >= minimumStride)
        val requiredBytes = frameInfo.stride.toLong() * frameInfo.height.toLong()
        require(requiredBytes in 1..MAX_IOS_FRAME_BYTES)
        require(frameInfo.byteCount >= requiredBytes.toULong())

        val colorSpace = checkNotNull(CGColorSpaceCreateDeviceRGB())
        val bitmapInfo = kCGBitmapByteOrder32Big or CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        val context =
            CGBitmapContextCreate(
                data = null,
                width = frameInfo.width.convert(),
                height = frameInfo.height.convert(),
                bitsPerComponent = BITS_PER_COMPONENT.convert(),
                bytesPerRow = frameInfo.stride.convert(),
                space = colorSpace,
                bitmapInfo = bitmapInfo,
            )
        if (context == null) {
            CGColorSpaceRelease(colorSpace)
            error("CoreGraphics could not allocate a KMediaVlc frame.")
        }
        try {
            memcpy(checkNotNull(CGBitmapContextGetData(context)), pixels, requiredBytes.convert())
            val image = checkNotNull(CGBitmapContextCreateImage(context))
            return try {
                UIImage.imageWithCGImage(image)
            } finally {
                CGImageRelease(image)
            }
        } finally {
            CGContextRelease(context)
            CGColorSpaceRelease(colorSpace)
        }
    }

    private fun failActiveSource(message: String) {
        runCatching { engine.stop() }
        pendingTransport = null
        pendingSeekTargetMicroseconds = null
        activeMediaGeneration = null
        activeMediaRequest = null
        sourceLoadedPublished = false
        lastPlaybackState = IosLibVlcPlaybackState.IDLE
        lastFrameSerial = null
        frameState.value = null
        resetSourceState()
        publishError(VideoPlayerError.SourceError(message))
    }

    private fun validatedMediaLocation(value: String): String {
        require(value.isNotBlank() && '\u0000' !in value) {
            "The KMediaVlc source must be a non-blank URI or absolute path."
        }
        if (value.startsWith('/')) return value
        val separator = value.indexOf(':')
        require(separator > 0) {
            "The iOS KMediaVlc adapter requires an absolute path or file/http/https URI."
        }
        val scheme = value.substring(0, separator).lowercase()
        require(scheme in SUPPORTED_URI_SCHEMES) {
            "The iOS KMediaVlc adapter accepts file:, http:, and https: sources only."
        }
        return value
    }

    private fun isDisposed(): Boolean = lifecycleLock.withLock { disposed }

    override fun dispose() {
        val shouldDispose =
            lifecycleLock.withLock {
                if (disposed) {
                    false
                } else {
                    disposed = true
                    pendingTransport = null
                    pendingSeekTargetMicroseconds = null
                    activeMediaGeneration = null
                    activeMediaRequest = null
                    true
                }
            }
        if (!shouldDispose) return
        scope.cancel()
        runBlocking {
            withTimeoutOrNull(DISPOSE_TIMEOUT_MS) {
                pollingJob.join()
            }
        }
        engine.close()
        frameState.value = null
    }

    private data class PendingOpen(
        val location: String,
        val requestHeaders: Map<String, String>,
    )

    private data class PendingTransport(
        var playWhenReady: Boolean,
        var seekMicroseconds: Long? = null,
        var volume: Float? = null,
        var playbackRate: Float? = null,
    )

    private companion object {
        const val SNAPSHOT_INTERVAL_MS = 100L
        const val PLAYING_FRAME_INTERVAL_MS = 16L
        const val PAUSED_FRAME_INTERVAL_MS = 100L
        const val IDLE_INTERVAL_MS = 100L
        const val DISPOSE_TIMEOUT_MS = 2_000L
        const val BYTES_PER_PIXEL = 4L
        const val BITS_PER_COMPONENT = 8
        const val MAX_IOS_FRAME_DIMENSION = 8_192
        const val MAX_IOS_FRAME_BYTES = 128L * 1024L * 1024L
        const val IOS_CPU_RENDERER = "KMediaVlc RGBA8 CPU pull / UIKit"
        const val IOS_PROJECTION_ERROR =
            "The iOS KMediaVlc CPU-pull player cannot project or crop video yet. " +
                "Restore flat mono playback or select a backend with a verified iOS projection renderer."
        val PLAYABLE_STATES =
            setOf(
                IosLibVlcPlaybackState.PLAYING,
                IosLibVlcPlaybackState.PAUSED,
                IosLibVlcPlaybackState.BUFFERING,
            )
        val SUPPORTED_URI_SCHEMES = setOf("file", "http", "https")
    }
}

internal fun validatedIosLibVlcRequestHeaders(requestHeaders: Map<String, String>): Map<String, String> {
    val safe = requestHeaders.sanitizedRequestHeaders()
    require(safe.keys.all { name -> name.lowercase() in IOS_SUPPORTED_HTTP_HEADERS }) {
        "KMediaVlc accepts only User-Agent, Referer, and Cookie request headers."
    }
    require(safe.values.none { value -> '\u0000' in value }) {
        "KMediaVlc request header values must not contain NUL."
    }
    return safe
}

internal fun IosLibVlcPlaybackState.playingSnapshot(): Boolean? =
    when (this) {
        IosLibVlcPlaybackState.PLAYING -> true
        IosLibVlcPlaybackState.IDLE,
        IosLibVlcPlaybackState.PAUSED,
        IosLibVlcPlaybackState.STOPPED,
        IosLibVlcPlaybackState.ENDED,
        IosLibVlcPlaybackState.ERROR,
        -> false
        IosLibVlcPlaybackState.OPENING,
        IosLibVlcPlaybackState.BUFFERING,
        -> null
    }

internal fun shouldCompleteIosLibVlcSeek(
    isSeeking: Boolean,
    state: IosLibVlcPlaybackState,
    positionMicroseconds: Long,
    targetMicroseconds: Long?,
    seekAppliedThisSnapshot: Boolean,
): Boolean =
    isSeeking &&
        targetMicroseconds != null &&
        !seekAppliedThisSnapshot &&
        state != IosLibVlcPlaybackState.IDLE &&
        state != IosLibVlcPlaybackState.OPENING &&
        state != IosLibVlcPlaybackState.BUFFERING &&
        abs(positionMicroseconds - targetMicroseconds) <= SEEK_COMPLETION_TOLERANCE_MICROSECONDS

private inline fun <T> NSRecursiveLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}

private fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

private val IOS_SUPPORTED_HTTP_HEADERS = setOf("user-agent", "referer", "cookie")
