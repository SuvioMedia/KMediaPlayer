@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MediaChapter
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurfaceProvider
import io.github.kdroidfilter.composemediaplayer.VideoRenderingInfo
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
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIImage
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Stable
@OptIn(ExperimentalComposeMediaPlayerBackendApi::class)
internal class IosMpvVideoPlayerState(
    internal val options: MpvPlaybackOptions,
    private val engine: IosLibMpvEngine,
) : AbstractMpvVideoPlayerState(),
    VideoPlayerSurfaceProvider {
    private val lifecycleLock = NSLock()
    private val renderLock = NSLock()
    private var disposed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventJob: Job
    private val frameState = mutableStateOf<UIImage?>(null)

    private var lastTrackRefreshMs = 0L

    init {
        eventJob = scope.launch { eventLoop() }
    }

    @Composable
    override fun RenderVideoPlayerSurface(
        modifier: Modifier,
        contentScale: ContentScale,
        overlay: @Composable () -> Unit,
    ) {
        IosMpvVideoPlayerSurface(
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
            audioRenderer = "mpv iOS audio output",
            subtitleRenderer = "mpv/libass",
            notes = "Application-embedded, code-signed iOS libmpv runtime.",
        )

    override val capabilities =
        PlayerCapabilities(
            supportsMkv = true,
            supportedUriSchemes = setOf("file", "http", "https", "rtmp", "rtsp"),
        )

    override val preciseCurrentTime: Duration
        get() =
            if (isDisposed()) {
                _currentTime
            } else {
                engine.getProperty("time-pos").secondsOrNull() ?: _currentTime
            }

    override val backendDisposed: Boolean
        get() = isDisposed()

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
            (value.inWholeMilliseconds.toDouble() / 1_000.0).toString(),
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
            (time.inWholeMilliseconds.toDouble() / 1_000.0).toString(),
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
        beginSourcePreparation(uri, initializePlayerState)
        try {
            engine.command("loadfile", uri, "replace")
            engine.setProperty(
                "pause",
                if (initializePlayerState == InitialPlayerState.PAUSE) "yes" else "no",
            )
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

    override fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult =
        try {
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

    override fun validateExternalSubtitle(track: SubtitleTrack) = Unit

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

    internal val currentFrame: State<UIImage?>
        get() = frameState

    internal fun renderFrame(
        requestedWidth: Int,
        requestedHeight: Int,
    ) {
        if (isDisposed() || !_hasMedia || requestedWidth <= 0 || requestedHeight <= 0) return
        renderLock.lock()
        try {
            if (isDisposed() || !_hasMedia) return
            val (width, height) = constrainSize(requestedWidth, requestedHeight)
            val rowBytes = width * BYTES_PER_PIXEL
            val colorSpace = checkNotNull(CGColorSpaceCreateDeviceRGB())
            val bitmapInfo =
                kCGBitmapByteOrder32Little or
                    CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value
            val context =
                CGBitmapContextCreate(
                    data = null,
                    width = width.toULong(),
                    height = height.toULong(),
                    bitsPerComponent = BITS_PER_COMPONENT.toULong(),
                    bytesPerRow = rowBytes.toULong(),
                    space = colorSpace,
                    bitmapInfo = bitmapInfo,
                )
            if (context == null) {
                CGColorSpaceRelease(colorSpace)
                error("CoreGraphics could not allocate an MPV frame.")
            }
            try {
                val pixels = checkNotNull(CGBitmapContextGetData(context))
                engine.render(
                    width = width,
                    height = height,
                    rowBytes = rowBytes.toULong(),
                    pixels = pixels,
                )
                val image = checkNotNull(CGBitmapContextCreateImage(context))
                try {
                    val uiImage = UIImage.imageWithCGImage(image)
                    mutateSnapshotState {
                        frameState.value = uiImage
                    }
                } finally {
                    CGImageRelease(image)
                }
            } finally {
                CGContextRelease(context)
                CGColorSpaceRelease(colorSpace)
            }
        } catch (_: Throwable) {
            if (!isDisposed()) {
                mutateSnapshotState { _hasMedia = false }
                publishError(VideoPlayerError.UnknownError("libmpv software rendering failed."))
            }
        } finally {
            renderLock.unlock()
        }
    }

    internal fun setCropMode(crop: Boolean) {
        if (!isDisposed()) {
            runCatching { engine.setProperty("panscan", if (crop) "1.0" else "0.0") }
        }
    }

    private suspend fun eventLoop() {
        try {
            while (scope.isActive && !isDisposed()) {
                when (val event = engine.waitEvent(EVENT_WAIT_SECONDS)) {
                    IosNativeMpvEvent.None -> Unit
                    IosNativeMpvEvent.Shutdown -> return
                    IosNativeMpvEvent.FileLoaded -> onFileLoaded()
                    is IosNativeMpvEvent.EndFile -> onEndFile(event)
                    IosNativeMpvEvent.SeekStarted ->
                        mutateSnapshotState { _isSeeking = true }
                    IosNativeMpvEvent.PlaybackRestarted -> onPlaybackRestarted()
                }
                refreshSnapshot()
                val now = currentTimeMillis()
                if (_hasMedia && now - lastTrackRefreshMs >= TRACK_REFRESH_INTERVAL_MS) {
                    refreshTracks()
                    refreshChapters()
                    lastTrackRefreshMs = now
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            if (!isDisposed()) {
                publishError(VideoPlayerError.UnknownError("libmpv event processing failed."))
            }
        }
    }

    private fun onFileLoaded() {
        refreshSnapshot()
        refreshTracks()
        refreshChapters()
        sourceLoaded()
    }

    private fun onEndFile(event: IosNativeMpvEvent.EndFile) {
        if (!_hasMedia ||
            event.reason == MPV_END_FILE_REASON_STOP ||
            event.reason == MPV_END_FILE_REASON_QUIT
        ) {
            return
        }
        if (event.reason == MPV_END_FILE_REASON_ERROR || event.errorCode < 0) {
            mutateSnapshotState { _hasMedia = false }
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
        if (!_hasMedia || isDisposed()) return
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
        mutateSnapshotState {
            metadata.title = title
            metadata.width = width
            metadata.height = height
            metadata.frameRate = frameRate
            renderingInfo.container = container
            renderingInfo.videoDecoder = videoDecoder
            renderingInfo.audioRenderer = audioDecoder ?: "mpv iOS audio output"
        }
        updateAspectRatio(width, height)
    }

    private fun refreshTracks() {
        if (isDisposed()) return
        val count =
            engine
                .getProperty("track-list/count")
                .positiveIntOrNull(allowZero = true)
                ?: return
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
                            channels =
                                engine
                                    .getProperty("$base/demux-channel-count")
                                    .positiveIntOrNull(),
                            sampleRate =
                                engine
                                    .getProperty("$base/demux-samplerate")
                                    .positiveIntOrNull(),
                            bitrate =
                                engine
                                    .getProperty("$base/demux-bitrate")
                                    .positiveIntOrNull(),
                            isDefault = engine.getProperty("$base/default").yesNoOrNull() == true,
                        )
                    discoveredAudio += track
                    if (selected) selectedAudio = track
                }
                "sub" -> {
                    val external = engine.getProperty("$base/external").yesNoOrNull() == true
                    val filename = engine.getProperty("$base/external-filename")
                    val registered =
                        filename?.let { path ->
                            registeredExternalSubtitles.values.firstOrNull { it.src == path }
                        }
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
        if (isDisposed()) return
        val count =
            engine
                .getProperty("chapter-list/count")
                .positiveIntOrNull(allowZero = true)
                ?: return
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
                MediaChapter(start = start, end = end, title = title)
            },
        )
    }

    private fun findMpvTrackId(
        type: String,
        externalFilename: String,
    ): String? {
        val count =
            engine
                .getProperty("track-list/count")
                .positiveIntOrNull(allowZero = true)
                ?: return null
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

    private fun constrainSize(
        requestedWidth: Int,
        requestedHeight: Int,
    ): Pair<Int, Int> {
        val pixels = requestedWidth.toLong() * requestedHeight.toLong()
        if (pixels <= options.maxDesktopRenderPixels.toLong()) {
            return requestedWidth to requestedHeight
        }
        val scale = sqrt(options.maxDesktopRenderPixels.toDouble() / pixels.toDouble())
        return (requestedWidth * scale).toInt().coerceAtLeast(1) to
            (requestedHeight * scale).toInt().coerceAtLeast(1)
    }

    private fun isDisposed(): Boolean {
        lifecycleLock.lock()
        return try {
            disposed
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun markDisposed(): Boolean {
        lifecycleLock.lock()
        return try {
            if (disposed) {
                false
            } else {
                disposed = true
                true
            }
        } finally {
            lifecycleLock.unlock()
        }
    }

    override fun dispose() {
        if (!markDisposed()) return
        engine.wakeup()
        runBlocking {
            withTimeoutOrNull(DISPOSE_TIMEOUT_MS) {
                eventJob.cancelAndJoin()
            }
        }
        renderLock.lock()
        try {
            frameState.value = null
            engine.close()
        } finally {
            renderLock.unlock()
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
        private const val BYTES_PER_PIXEL = 4
        private const val BITS_PER_COMPONENT = 8
    }
}

private fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

private fun String?.secondsOrNull(): Duration? =
    this
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.seconds

private fun String?.yesNoOrNull(): Boolean? =
    when (this?.lowercase()) {
        "yes", "true", "1" -> true
        "no", "false", "0" -> false
        else -> null
    }

private fun String?.positiveIntOrNull(allowZero: Boolean = false): Int? =
    this?.toIntOrNull()?.takeIf { if (allowZero) it >= 0 else it > 0 }

private fun String?.positiveFloatOrNull(): Float? = this?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
