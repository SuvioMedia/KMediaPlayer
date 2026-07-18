package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableException
import io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableReason
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
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
import java.nio.file.Files
import java.nio.file.LinkOption
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
    VideoPlayerSurfaceProvider {
    private val disposed = AtomicBoolean(false)
    private val renderLock = ReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventJob: Job
    private val frameState = mutableStateOf<ImageBitmap?>(null)
    private val framePool = MpvFramePool(runtimeConfig.maxRenderPixels)

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

    override val renderingInfo =
        VideoRenderingInfo(
            backend = "libmpv",
            videoRenderer = "libmpv software render API",
            audioRenderer = "mpv audio output",
            subtitleRenderer = "mpv/libass",
            notes =
                if (usesVerifiedBundledRuntime) {
                    "Verified KMediaMpv local-file runtime."
                } else {
                    "User-provided libmpv; native runtime license is unverified."
                },
        )

    init {
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
        engine.setProperty("pause", "no")
    }

    override fun pauseBackend() {
        engine.setProperty("pause", "yes")
    }

    override fun seekBackend(time: Duration) {
        engine.command(
            "seek",
            time.inWholeMilliseconds
                .toDouble()
                .div(1_000.0)
                .toString(),
            "absolute+exact",
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
        require(requestHeaders.isEmpty()) {
            "The mpv artifact does not accept request headers. Use a credential-safe transport outside this backend."
        }
        require(!usesVerifiedBundledRuntime || uri.isLocalMpvSource()) {
            "The verified KMediaMpv runtime has networking disabled and accepts local paths or file: URIs only."
        }

        beginSourcePreparation(uri, initializePlayerState)

        try {
            engine.command("loadfile", uri, "replace")
            engine.setProperty("pause", if (initializePlayerState == InitialPlayerState.PAUSE) "yes" else "no")
        } catch (_: Throwable) {
            _hasMedia = false
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
                val target = framePool.next(width, height)
                engine.render(
                    width = target.width,
                    height = target.height,
                    rowBytes = target.rowBytes,
                    pixelsAddress = target.pixelsAddress,
                )
                Snapshot.withMutableSnapshot {
                    frameState.value = target.bitmap.asComposeImageBitmap()
                }
            }
        } catch (_: Exception) {
            if (!disposed.get()) {
                Snapshot.withMutableSnapshot { _hasMedia = false }
                publishError(VideoPlayerError.UnknownError("libmpv software rendering failed."))
            }
        }
    }

    internal fun setCropMode(crop: Boolean) {
        if (!disposed.get()) runCatching { engine.setProperty("panscan", if (crop) "1.0" else "0.0") }
    }

    private suspend fun eventLoop() {
        try {
            while (scope.isActive && !disposed.get()) {
                when (val event = engine.waitEvent(EVENT_WAIT_SECONDS)) {
                    NativeMpvEvent.None -> Unit
                    NativeMpvEvent.Shutdown -> return
                    NativeMpvEvent.FileLoaded -> onFileLoaded()
                    is NativeMpvEvent.EndFile -> onEndFile(event)
                    NativeMpvEvent.SeekStarted -> Snapshot.withMutableSnapshot { _isSeeking = true }
                    NativeMpvEvent.PlaybackRestarted -> onPlaybackRestarted()
                }
                refreshSnapshot()
                val now = System.currentTimeMillis()
                if (_hasMedia && now - lastTrackRefreshMs >= TRACK_REFRESH_INTERVAL_MS) {
                    refreshTracks()
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
        refreshTracks()
        sourceLoaded()
    }

    private fun onEndFile(event: NativeMpvEvent.EndFile) {
        if (!_hasMedia || event.reason == MPV_END_FILE_REASON_STOP || event.reason == MPV_END_FILE_REASON_QUIT) return
        if (event.reason == MPV_END_FILE_REASON_ERROR || event.errorCode < 0) {
            Snapshot.withMutableSnapshot { _hasMedia = false }
            publishError(VideoPlayerError.SourceError("libmpv could not finish the media source."))
            return
        }
        emitPlaybackEnded()
    }

    private fun onPlaybackRestarted() {
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
        val buffering = engine.getProperty("paused-for-cache").yesNoOrNull() ?: false
        val seeking = engine.getProperty("seeking").yesNoOrNull() ?: _isSeeking
        val width = engine.getProperty("video-params/w").positiveIntOrNull()
        val height = engine.getProperty("video-params/h").positiveIntOrNull()
        val title = engine.getProperty("media-title")
        val frameRate = engine.getProperty("container-fps").positiveFloatOrNull()
        val container = engine.getProperty("file-format")
        val videoDecoder = engine.getProperty("video-codec")
        val audioDecoder = engine.getProperty("audio-codec-name")

        updatePlaybackPosition(
            position = position,
            mediaDuration = mediaDuration,
            playing = !paused,
            loading = buffering,
            seeking = seeking,
        )
        Snapshot.withMutableSnapshot {
            metadata.title = title
            metadata.width = width
            metadata.height = height
            metadata.frameRate = frameRate
            renderingInfo.container = container
            renderingInfo.videoDecoder = videoDecoder
            renderingInfo.audioRenderer = audioDecoder ?: "mpv audio output"
        }
        updateAspectRatio(width, height)
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
        engine.wakeup()
        runBlocking {
            withTimeoutOrNull(DISPOSE_TIMEOUT_MS) {
                eventJob.cancelAndJoin()
            }
        }
        renderLock.withLock {
            frameState.value = null
            framePool.close()
            engine.close()
        }
        scope.cancel()
    }

    companion object {
        private const val MPV_VOLUME_SCALE = 100f
        private const val EVENT_WAIT_SECONDS = 0.05
        private const val TRACK_REFRESH_INTERVAL_MS = 1_000L
        private const val DISPOSE_TIMEOUT_MS = 2_000L
        private const val MPV_END_FILE_REASON_STOP = 2
        private const val MPV_END_FILE_REASON_QUIT = 3
        private const val MPV_END_FILE_REASON_ERROR = 4
        private const val AUDIO_TRACK_PREFIX = "mpv:audio:"
        private const val SUBTITLE_TRACK_PREFIX = "mpv:subtitle:"
    }
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
        put("osc", "no")
        put("hwdec", "no")
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
        } catch (_: RuntimeException) {
            library.close()
            throw MpvBackendUnavailableException(
                MpvBackendAvailability.Unavailable(
                    reason = MpvBackendUnavailableReason.INITIALIZATION_FAILED,
                    guidance =
                        "The verified libmpv runtime could not initialize the software backend.",
                ),
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
