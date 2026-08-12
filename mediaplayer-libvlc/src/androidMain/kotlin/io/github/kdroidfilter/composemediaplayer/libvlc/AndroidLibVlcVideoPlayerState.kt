@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.libvlc

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.AbstractBackendVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.LibVlcAndroidDecodeMode
import io.github.kdroidfilter.composemediaplayer.LibVlcPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
import io.github.kdroidfilter.composemediaplayer.isDefaultTextureCrop
import io.github.kdroidfilter.composemediaplayer.renderingInfoLabel
import io.github.kdroidfilter.composemediaplayer.requiresProjectionRenderer
import io.github.kdroidfilter.composemediaplayer.sanitizedRequestHeaders
import io.github.shusek.kmediavlc.runtime.android.VlcAndroidDecodeMode
import io.github.shusek.kmediavlc.runtime.android.VlcAndroidPlaybackState
import io.github.shusek.kmediavlc.runtime.android.VlcAndroidPlayer
import io.github.shusek.kmediavlc.runtime.android.VlcAndroidPlayerSnapshot
import io.github.shusek.kmediavlc.runtime.android.VlcAndroidRuntime
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

@Stable
internal class AndroidLibVlcVideoPlayerState(
    private val context: Context,
    private val options: LibVlcPlaybackOptions,
) : AbstractBackendVideoPlayerState(),
    VideoPlayerSurfaceProvider {
    private val disposed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleLock = Any()
    private val activeMediaDescriptors = linkedSetOf<ParcelFileDescriptor>()
    private val ownedTemporaryFiles = linkedSetOf<File>()
    private val player = VlcAndroidPlayer.create(context, options.androidDecodeMode.toRuntimeDecodeMode())

    private var surfaceAttached = false
    private var attachedVideoSurface: Surface? = null
    private var attachedSubtitleSurface: Surface? = null
    private var attachedSurfaceWidth = 0
    private var attachedSurfaceHeight = 0
    private var pendingOpen: PendingOpen? = null
    private var activeMediaRequest: PendingOpen? = null
    private var pendingTransport: PendingTransport? = null
    private var activeMediaGeneration: Long? = null
    private var sourceLoadedPublished = false
    private var lastPlaybackState = VlcAndroidPlaybackState.IDLE
    private var rejectedSurfaceRoute: String? = null

    override val renderingInfo =
        VideoRenderingInfo(
            backend = "KMediaVlc ${VlcAndroidRuntime.VLC_VERSION}",
            videoRenderer = ANDROID_SURFACE_RENDERER,
            audioRenderer = "libVLC Android audio output",
            notes =
                "Bundled Android libVLC 4 runtime; direct MediaCodec video Surface plus a " +
                    "transparent subtitle Surface on API 28+ ARM devices. The automatic route " +
                    "requests an HDR-capable window; strict color and projection policies fail closed.",
        )

    override val capabilities =
        PlayerCapabilities(
            supportsMkv = true,
            supportsHls = true,
            supportedUriSchemes = setOf("file", "content", "http", "https"),
        )

    override val preciseCurrentTime: Duration
        get() =
            if (disposed.get() || !_hasMedia) {
                _currentTime
            } else {
                runCatching {
                    val snapshot = player.snapshot()
                    synchronized(lifecycleLock) {
                        if (activeMediaGeneration == snapshot.mediaGeneration) {
                            snapshot.positionMicroseconds.microseconds
                        } else {
                            _currentTime
                        }
                    }
                }.getOrDefault(_currentTime)
            }

    override val backendDisposed: Boolean
        get() = disposed.get()

    internal val projectionUnsupported: Boolean
        get() = projection.requiresProjectionRenderer || !projectionTextureCrop.isDefaultTextureCrop

    internal val requestsHdrWindow: Boolean
        get() = options.androidDecodeMode.requestsAndroidLibVlcHdrWindow()

    init {
        projection = options.projection.normalized()
        projectionView = options.projectionView.normalized()
        projectionViewControlMode = options.projectionViewControlMode
        projectionTextureCrop = options.projectionTextureCrop.normalized()
        scope.launch { pollPlayback() }
    }

    @Composable
    override fun RenderVideoPlayerSurface(
        modifier: Modifier,
        contentScale: ContentScale,
        overlay: @Composable () -> Unit,
    ) {
        AndroidLibVlcVideoPlayerSurface(
            playerState = this,
            modifier = modifier,
            contentScale = contentScale,
            overlay = overlay,
        )
    }

    override fun setBackendVolume(value: Float) {
        check(player.setVolume(value)) { "libVLC rejected volume." }
    }

    override fun setBackendLoop(value: Boolean) {
        // Keep native input-repeat disabled so changing VideoPlayerState.loop while media is open
        // takes effect immediately. The adapter restarts the current media after END instead.
        check(player.setLoop(false)) { "libVLC rejected loop mode." }
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
        synchronized(lifecycleLock) {
            pendingTransport?.let(update) != null
        }

    override fun selectBackendAudioTrack(track: AudioTrack?): TrackSelectionResult = TrackSelectionResult.NotSupported

    override fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult =
        TrackSelectionResult.NotSupported

    override fun disableBackendSubtitles(): TrackSelectionResult = TrackSelectionResult.NotSupported

    override fun validateExternalSubtitle(track: SubtitleTrack): Unit =
        throw UnsupportedOperationException("The Android KMediaVlc bridge does not expose subtitle tracks yet.")

    override fun removeBackendExternalSubtitle(track: SubtitleTrack) = Unit

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        ensureOpen()
        val headers = requestHeaders.toLibVlcHeaders()
        val source = prepareMediaSource(uri)
        var descriptorTransferred = false
        try {
            synchronized(lifecycleLock) {
                ensureOpen()
                if (_hasMedia) runCatching { player.stop() }
                closeActiveMediaDescriptors()
                beginSourcePreparation(uri, initializePlayerState)
                sourceLoadedPublished = false
                lastPlaybackState = VlcAndroidPlaybackState.IDLE
                activeMediaGeneration = null
                activeMediaRequest = null
                pendingOpen = PendingOpen(source.location, headers)
                pendingTransport =
                    PendingTransport(
                        playWhenReady = initializePlayerState == InitialPlayerState.PLAY,
                    )
                source.descriptor?.let(activeMediaDescriptors::add)
                descriptorTransferred = true
            }
        } finally {
            if (!descriptorTransferred) source.close()
        }
        openPendingSourceIfReady()
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        val location =
            when (val androidFile = file.androidFile) {
                is AndroidFile.FileWrapper -> androidFile.file.absolutePath
                is AndroidFile.UriWrapper -> androidFile.uri.toString()
            }
        openUri(location, initializePlayerState, emptyMap())
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        ensureOpen()
        require(fileName.isNotBlank() && !fileName.startsWith('/') && ".." !in fileName.split('/')) {
            "The asset path must be relative and must not contain parent traversal."
        }
        val suffix = fileName.substringAfterLast('.', missingDelimiterValue = "bin").take(MAX_ASSET_SUFFIX_LENGTH)
        val target = File.createTempFile("composemediaplayer-libvlc-", ".$suffix", context.cacheDir)
        try {
            context.assets.open(fileName).use { input ->
                target.outputStream().use(input::copyTo)
            }
            synchronized(lifecycleLock) { ownedTemporaryFiles += target }
            openUri(target.absolutePath, initializePlayerState, emptyMap())
        } catch (failure: Exception) {
            synchronized(lifecycleLock) { ownedTemporaryFiles -= target }
            runCatching { target.delete() }
            throw failure
        }
    }

    override fun releaseSource() {
        ensureOpen()
        synchronized(lifecycleLock) {
            pendingOpen = null
            activeMediaRequest = null
            pendingTransport = null
            activeMediaGeneration = null
            runCatching { player.stop() }
            closeActiveMediaDescriptors()
            sourceLoadedPublished = false
            resetSourceState()
        }
    }

    internal fun attachSurfaces(
        videoSurface: Surface,
        subtitleSurface: Surface,
        width: Int,
        height: Int,
    ) {
        if (
            disposed.get() ||
            projectionUnsupported ||
            !videoSurface.isValid ||
            !subtitleSurface.isValid ||
            width <= 0 ||
            height <= 0
        ) {
            return
        }
        try {
            synchronized(lifecycleLock) {
                if (disposed.get()) return
                if (
                    attachedVideoSurface !== videoSurface ||
                    attachedSubtitleSurface !== subtitleSurface ||
                    attachedSurfaceWidth != width ||
                    attachedSurfaceHeight != height
                ) {
                    player.attachSurfaces(videoSurface, subtitleSurface, width, height)
                }
                attachedVideoSurface = videoSurface
                attachedSubtitleSurface = subtitleSurface
                attachedSurfaceWidth = width
                attachedSurfaceHeight = height
                surfaceAttached = true
            }
            openPendingSourceIfReady()
        } catch (_: RuntimeException) {
            publishError(VideoPlayerError.UnknownError("KMediaVlc rejected the Android video/subtitle Surfaces."))
        }
    }

    internal fun detachSurface(surface: Surface? = null) {
        synchronized(lifecycleLock) {
            if (
                surface != null &&
                attachedVideoSurface !== surface &&
                attachedSubtitleSurface !== surface
            ) {
                return
            }
            attachedVideoSurface = null
            attachedSubtitleSurface = null
            attachedSurfaceWidth = 0
            attachedSurfaceHeight = 0
            surfaceAttached = false
            if (!disposed.get()) runCatching { player.detachSurfaces() }
        }
    }

    internal fun updateSurfaceRenderingRoute(contentScale: ContentScale) {
        renderingInfo.videoProjection = projection.renderingInfoLabel()
        val rejection =
            when {
                projectionUnsupported -> ANDROID_PROJECTION_ERROR
                !contentScale.isSupportedAndroidLibVlcContentScale() -> ANDROID_CONTENT_SCALE_ERROR
                else -> null
            }
        if (rejection == null) {
            renderingInfo.videoRenderer = ANDROID_SURFACE_RENDERER
            rejectedSurfaceRoute = null
            return
        }
        renderingInfo.videoRenderer = "$ANDROID_SURFACE_RENDERER (requested layout unavailable)"
        if (rejectedSurfaceRoute == rejection) return
        rejectedSurfaceRoute = rejection
        detachSurface()
        runCatching { player.pause() }
        publishError(VideoPlayerError.UnknownError(rejection))
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        synchronized(lifecycleLock) {
            attachedVideoSurface = null
            attachedSubtitleSurface = null
            attachedSurfaceWidth = 0
            attachedSurfaceHeight = 0
            surfaceAttached = false
            pendingOpen = null
            activeMediaRequest = null
            pendingTransport = null
            activeMediaGeneration = null
        }
        scope.cancel()
        runCatching { player.close() }
        closeActiveMediaDescriptors()
        ownedTemporaryFiles.forEach { file -> runCatching { file.delete() } }
        ownedTemporaryFiles.clear()
        resetSourceState()
    }

    private fun openPendingSourceIfReady() {
        if (disposed.get()) return
        synchronized(lifecycleLock) {
            if (disposed.get() || !surfaceAttached) return
            val request = pendingOpen.also { pendingOpen = null } ?: return
            val generation =
                runCatching {
                    check(player.setVolume(_volume))
                    check(player.setRate(_playbackSpeed))
                    check(player.setLoop(false))
                    if (player.open(request.location, request.requestHeaders, true)) {
                        player.snapshot().mediaGeneration
                    } else {
                        null
                    }
                }.getOrNull()
            if (generation != null) {
                activeMediaGeneration = generation
                activeMediaRequest = request
            } else {
                runCatching { player.stop() }
                activeMediaGeneration = null
                activeMediaRequest = null
                pendingTransport = null
                closeActiveMediaDescriptors()
                resetSourceState()
                publishError(VideoPlayerError.SourceError("KMediaVlc rejected the Android media source."))
            }
        }
    }

    private suspend fun pollPlayback() {
        try {
            while (scope.isActive && !disposed.get()) {
                if (_hasMedia) refreshSnapshot()
                delay(POLL_INTERVAL_MS)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: RuntimeException) {
            if (!disposed.get()) {
                publishError(VideoPlayerError.UnknownError("KMediaVlc Android playback monitoring failed."))
            }
        }
    }

    private fun refreshSnapshot() {
        val snapshot = player.snapshot()
        synchronized(lifecycleLock) {
            if (activeMediaGeneration != snapshot.mediaGeneration) return
            val state = snapshot.state
            val pendingSeekRequested = pendingTransport?.seekMicroseconds != null
            updatePlaybackPosition(
                position = snapshot.positionMicroseconds.microseconds,
                mediaDuration = snapshot.durationMicroseconds.microseconds,
                playing = state.playingSnapshot(),
                loading = state == VlcAndroidPlaybackState.OPENING || state == VlcAndroidPlaybackState.BUFFERING,
                seeking = null,
            )
            mutateSnapshotState {
                metadata.duration = snapshot.durationMicroseconds.takeIf { it > 0 }?.microseconds
                metadata.width = snapshot.videoWidth.takeIf { it > 0 }
                metadata.height = snapshot.videoHeight.takeIf { it > 0 }
                updateAspectRatio(metadata.width, metadata.height)
            }
            val pendingSeekApplied = applyPendingTransport(snapshot, state)
            if (!sourceLoadedPublished && state in PLAYABLE_STATES) {
                sourceLoadedPublished = true
                sourceLoaded()
            }
            if (shouldCompleteAndroidLibVlcSeek(_isSeeking, state, pendingSeekRequested, pendingSeekApplied)) {
                emitSeekCompleted()
            }
            val previousState = lastPlaybackState
            lastPlaybackState = state
            if (state == VlcAndroidPlaybackState.ENDED && previousState != VlcAndroidPlaybackState.ENDED) {
                if (_loop) {
                    if (!restartLoopedMedia()) {
                        runCatching { player.stop() }
                        activeMediaGeneration = null
                        activeMediaRequest = null
                        closeActiveMediaDescriptors()
                        resetSourceState()
                        publishError(VideoPlayerError.UnknownError("KMediaVlc could not restart looped Android media."))
                    }
                } else {
                    emitPlaybackEnded(looping = false)
                }
            }
            if (state == VlcAndroidPlaybackState.ERROR && previousState != VlcAndroidPlaybackState.ERROR) {
                publishError(VideoPlayerError.UnknownError("KMediaVlc reported an Android playback failure."))
            }
        }
    }

    private fun applyPendingTransport(
        snapshot: VlcAndroidPlayerSnapshot,
        state: VlcAndroidPlaybackState,
    ): Boolean {
        if (state !in PLAYABLE_STATES) return false
        val requested = synchronized(lifecycleLock) { pendingTransport?.copy() } ?: return false
        val requestedSeek = requested.seekMicroseconds
        val seekApplied =
            when {
                requestedSeek == null -> true
                !snapshot.isSeekable -> false
                else -> player.seek(requestedSeek, false)
            }
        val playbackApplied =
            if (requested.playWhenReady) {
                state == VlcAndroidPlaybackState.PLAYING ||
                    state == VlcAndroidPlaybackState.BUFFERING ||
                    player.play()
            } else {
                state == VlcAndroidPlaybackState.PAUSED || player.pause()
            }
        synchronized(lifecycleLock) {
            val current = pendingTransport ?: return@synchronized
            if (seekApplied && current.seekMicroseconds == requestedSeek) current.seekMicroseconds = null
            if (playbackApplied &&
                current.playWhenReady == requested.playWhenReady &&
                current.seekMicroseconds == null
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
                check(player.setVolume(_volume))
                check(player.setRate(_playbackSpeed))
                check(player.setLoop(false))
                if (player.open(request.location, request.requestHeaders, true)) {
                    player.snapshot().mediaGeneration
                } else {
                    null
                }
            }.getOrNull() ?: return false
        activeMediaGeneration = generation
        mutateSnapshotState {
            _currentTime = Duration.ZERO
            _sliderPos = 0f
            _isPlaying = true
            _isLoading = true
        }
        emitPlaybackEnded(looping = true)
        return true
    }

    private fun prepareMediaSource(value: String): PreparedMediaSource {
        require(value.isNotBlank() && '\u0000' !in value) {
            "The KMediaVlc source must be a non-blank URI or path."
        }
        val uri = Uri.parse(value)
        return when {
            uri.scheme == null -> PreparedMediaSource(value)
            uri.scheme.equals("file", ignoreCase = true) ->
                PreparedMediaSource(File(requireNotNull(uri.path)).absolutePath)
            uri.scheme.equals("content", ignoreCase = true) -> {
                val descriptor =
                    requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) {
                        "The Android content provider did not expose a readable media descriptor."
                    }
                PreparedMediaSource("/proc/self/fd/${descriptor.fd}", descriptor)
            }
            uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true) ->
                PreparedMediaSource(value)
            else ->
                throw IllegalArgumentException(
                    "The Android KMediaVlc adapter accepts file:, content:, http:, and https: sources only.",
                )
        }
    }

    private fun Map<String, String>.toLibVlcHeaders(): Map<String, String> {
        val safe = sanitizedRequestHeaders()
        require(safe.keys.all { name -> name.lowercase() in SUPPORTED_HTTP_HEADERS }) {
            "KMediaVlc accepts only User-Agent, Referer, and Cookie request headers."
        }
        return safe
    }

    private fun closeActiveMediaDescriptors() {
        activeMediaDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
        activeMediaDescriptors.clear()
    }

    private data class PendingOpen(
        val location: String,
        val requestHeaders: Map<String, String>,
    )

    private data class PendingTransport(
        var playWhenReady: Boolean,
        var seekMicroseconds: Long? = null,
    )

    private data class PreparedMediaSource(
        val location: String,
        val descriptor: ParcelFileDescriptor? = null,
    ) {
        fun close() {
            runCatching { descriptor?.close() }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 100L
        const val MAX_ASSET_SUFFIX_LENGTH = 16
        const val ANDROID_SURFACE_RENDERER = "libVLC 4 Android Surface"
        const val ANDROID_PROJECTION_ERROR =
            "The Android KMediaVlc player cannot project its direct Surface yet. " +
                "Restore flat mono playback or select a backend with an Android projection input surface."
        const val ANDROID_CONTENT_SCALE_ERROR =
            "The Android KMediaVlc direct Surface currently supports ContentScale.Fit only."
        val PLAYABLE_STATES =
            setOf(
                VlcAndroidPlaybackState.PLAYING,
                VlcAndroidPlaybackState.PAUSED,
                VlcAndroidPlaybackState.BUFFERING,
            )
        val SUPPORTED_HTTP_HEADERS = setOf("user-agent", "referer", "cookie")
    }
}

internal fun VlcAndroidPlaybackState.playingSnapshot(): Boolean? =
    when (this) {
        VlcAndroidPlaybackState.PLAYING -> true
        VlcAndroidPlaybackState.IDLE,
        VlcAndroidPlaybackState.PAUSED,
        VlcAndroidPlaybackState.STOPPED,
        VlcAndroidPlaybackState.ENDED,
        VlcAndroidPlaybackState.ERROR,
        -> false
        VlcAndroidPlaybackState.OPENING,
        VlcAndroidPlaybackState.BUFFERING,
        -> null
    }

internal fun shouldCompleteAndroidLibVlcSeek(
    isSeeking: Boolean,
    state: VlcAndroidPlaybackState,
    pendingSeekRequested: Boolean,
    pendingSeekApplied: Boolean,
): Boolean =
    isSeeking &&
        state != VlcAndroidPlaybackState.BUFFERING &&
        (!pendingSeekRequested || pendingSeekApplied)

private fun LibVlcAndroidDecodeMode.toRuntimeDecodeMode(): VlcAndroidDecodeMode =
    when (this) {
        LibVlcAndroidDecodeMode.AUTOMATIC -> VlcAndroidDecodeMode.AUTOMATIC
        LibVlcAndroidDecodeMode.SOFTWARE_ONLY -> VlcAndroidDecodeMode.SOFTWARE_ONLY
    }

internal fun LibVlcAndroidDecodeMode.requestsAndroidLibVlcHdrWindow(): Boolean =
    this == LibVlcAndroidDecodeMode.AUTOMATIC
