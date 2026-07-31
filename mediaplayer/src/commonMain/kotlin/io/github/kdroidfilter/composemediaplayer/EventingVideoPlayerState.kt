package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Suppress("LargeClass", "TooManyFunctions")
internal class EventingVideoPlayerState(
    private val delegate: VideoPlayerState,
    eventCoroutineContext: CoroutineContext,
) : VideoPlayerState by delegate {
    constructor(delegate: VideoPlayerState) : this(delegate, Dispatchers.Default)

    /** Underlying platform state used by platform surface hosts. */
    internal val wrappedState: VideoPlayerState
        get() = delegate

    private val eventDispatcher = PlaybackEventDispatcher()
    private val eventScope = CoroutineScope(eventCoroutineContext + SupervisorJob())
    private val eventStateLock = PlatformLock()

    // Every field below is read and written under eventStateLock. Public player calls may arrive
    // from UI and media callback threads concurrently on JVM, Android, and iOS.
    private var activeSourceSessionId = 0L
    private var sourceLoadedSessionId = 0L
    private var sourceLoadedJob: Job? = null
    private var seekCompletionJob: Job? = null
    private var audioTrackSelectionJob: Job? = null
    private var subtitleTrackSelectionJob: Job? = null
    private var adaptiveQualitySelectionJob: Job? = null
    private var lastError: VideoPlayerError? = null
    private var playbackEndedCallback: (() -> Unit)? = null
    private var restartCallback: (() -> Unit)? = null
    private var isDisposed = false
    private val delegatePlaybackEndedCallback: () -> Unit = {
        val callback =
            eventStateLock.withLock {
                if (isDisposed) return@withLock null
                emitPlaybackEvent { sessionId, sampledAtMs ->
                    PlaybackEvent.PlaybackEnded(
                        mediaSessionId = sessionId,
                        sampledAtMs = sampledAtMs,
                    )
                }
                playbackEndedCallback
            }
        callback?.invoke()
    }
    private val delegateRestartCallback: () -> Unit = {
        val callback =
            eventStateLock.withLock {
                if (isDisposed) return@withLock null
                emitPlaybackEvent { sessionId, sampledAtMs ->
                    PlaybackEvent.PlaybackRestarted(
                        mediaSessionId = sessionId,
                        sampledAtMs = sampledAtMs,
                    )
                }
                restartCallback
            }
        callback?.invoke()
    }

    override val mediaSessionId: Long get() = eventDispatcher.mediaSessionId
    override val playbackEvents = eventDispatcher.events

    init {
        delegate.onPlaybackEnded = delegatePlaybackEndedCallback
        delegate.onRestart = delegateRestartCallback
    }

    override var volume: Float
        get() = delegate.volume
        set(value) {
            ensureNotDisposed()
            delegate.volume = value
        }

    override var sliderPos: Float
        get() = delegate.sliderPos
        set(value) {
            ensureNotDisposed()
            delegate.sliderPos = value
        }

    override var userDragging: Boolean
        get() = delegate.userDragging
        set(value) {
            ensureNotDisposed()
            delegate.userDragging = value
        }

    override var loop: Boolean
        get() = delegate.loop
        set(value) {
            ensureNotDisposed()
            delegate.loop = value
        }

    override var playbackSpeed: Float
        get() = delegate.playbackSpeed
        set(value) {
            ensureNotDisposed()
            delegate.playbackSpeed = value
        }

    override var isFullscreen: Boolean
        get() = delegate.isFullscreen
        set(value) {
            ensureNotDisposed()
            delegate.isFullscreen = value
        }

    override var isPipActive: Boolean
        get() = delegate.isPipActive
        set(value) {
            ensureNotDisposed()
            delegate.isPipActive = value
        }

    override var isPipEnabled: Boolean
        get() = delegate.isPipEnabled
        set(value) {
            ensureNotDisposed()
            delegate.isPipEnabled = value
        }

    override var projection: VideoProjectionSettings
        get() = delegate.projection
        set(value) {
            ensureNotDisposed()
            delegate.projection = value
        }

    override var projectionView: VideoProjectionViewSettings
        get() = delegate.projectionView
        set(value) {
            ensureNotDisposed()
            delegate.projectionView = value
        }

    override var projectionViewControlMode: VideoProjectionViewControlMode
        get() = delegate.projectionViewControlMode
        set(value) {
            ensureNotDisposed()
            delegate.projectionViewControlMode = value
        }

    override var projectionTextureCrop: VideoTextureCrop
        get() = delegate.projectionTextureCrop
        set(value) {
            ensureNotDisposed()
            delegate.projectionTextureCrop = value
        }

    override var currentAudioTrack: AudioTrack?
        get() = delegate.currentAudioTrack
        set(value) {
            ensureNotDisposed()
            delegate.currentAudioTrack = value
        }

    override var subtitlesEnabled: Boolean
        get() = delegate.subtitlesEnabled
        set(value) {
            ensureNotDisposed()
            delegate.subtitlesEnabled = value
        }

    override var currentSubtitleTrack: SubtitleTrack?
        get() = delegate.currentSubtitleTrack
        set(value) {
            ensureNotDisposed()
            delegate.currentSubtitleTrack = value
        }

    override var subtitleTextStyle: TextStyle
        get() = delegate.subtitleTextStyle
        set(value) {
            ensureNotDisposed()
            delegate.subtitleTextStyle = value
        }

    override var subtitleBackgroundColor: Color
        get() = delegate.subtitleBackgroundColor
        set(value) {
            ensureNotDisposed()
            delegate.subtitleBackgroundColor = value
        }

    override var subtitleOffset: Duration
        get() = delegate.subtitleOffset
        set(value) {
            ensureNotDisposed()
            delegate.subtitleOffset = value
        }

    override suspend fun enterPip(): PipResult {
        ensureNotDisposed()
        return delegate.enterPip()
    }

    override var onPlaybackEnded: (() -> Unit)?
        get() = eventStateLock.withLock { playbackEndedCallback }
        set(value) {
            ensureNotDisposed()
            eventStateLock.withLock {
                check(!isDisposed) { DISPOSED_MESSAGE }
                playbackEndedCallback = value
            }
        }

    override var onRestart: (() -> Unit)?
        get() = eventStateLock.withLock { restartCallback }
        set(value) {
            ensureNotDisposed()
            eventStateLock.withLock {
                check(!isDisposed) { DISPOSED_MESSAGE }
                restartCallback = value
            }
        }

    override fun play() {
        ensureNotDisposed()
        delegate.play()
    }

    override fun pause() {
        ensureNotDisposed()
        delegate.pause()
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        openSource(uri) {
            delegate.openUri(uri, initializePlayerState, requestHeaders)
        }
    }

    override fun openSource(
        source: MediaSourceSpec,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        openSource(source.uri) {
            delegate.openSource(source, initializePlayerState, requestHeaders)
        }
    }

    override fun prepare(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        openUri(uri, initializePlayerState, requestHeaders)
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        openSource(file.getUri()) {
            delegate.openFile(file, initializePlayerState)
        }
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        val assetPath = fileName.normalizedAssetPath()
        openSource(assetPath.normalizedAssetUri()) {
            delegate.openAsset(assetPath, initializePlayerState)
        }
    }

    override fun stop() {
        ensureNotDisposed()
        cancelSeekCompletionJob()
        delegate.stop()
        emitCurrentErrorIfChanged()
    }

    override fun releaseSource() {
        ensureNotDisposed()
        val releasedSessionId = sourceSessionToRelease()
        delegate.releaseSource()
        if (releasedSessionId != 0L) {
            eventStateLock.withLock {
                val stillCurrent =
                    activeSourceSessionId == releasedSessionId ||
                        (activeSourceSessionId == 0L && mediaSessionId == releasedSessionId)
                if (stillCurrent && !isDisposed) {
                    clearActiveSourceUnsafe().forEach { it.cancel() }
                    emitSourceReleased(releasedSessionId)
                    eventDispatcher.nextMediaSessionId()
                }
            }
        }
        emitCurrentErrorIfChanged()
    }

    override fun seekTo(time: Duration) {
        ensureNotDisposed()
        val seekSessionId = sourceSessionToRelease()
        if (seekSessionId == 0L || !canSeekCurrentSource()) {
            emitCurrentErrorIfChanged()
            return
        }
        val target = clampedSeekTarget(time)
        eventStateLock.withLock {
            if (isDisposed || activeSourceSessionId != seekSessionId) return@withLock
            emitPlaybackEventForSession(seekSessionId) { sessionId, sampledAtMs ->
                PlaybackEvent.SeekStarted(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    target = target,
                )
            }
        }
        delegate.seekTo(target)
        cancelSeekCompletionJob()
        if (!isActiveSourceSession(seekSessionId)) return
        val immediatePosition = delegate.preciseCurrentTime
        val completedSynchronously =
            !delegate.isSeeking &&
                isWithinSeekTolerance(position = immediatePosition, target = target)
        if (completedSynchronously) {
            emitSeekCompleted(seekSessionId, immediatePosition)
            emitCurrentErrorIfChanged()
            return
        }
        lateinit var newJob: Job
        val shouldStart =
            eventStateLock.withLock {
                if (isDisposed || activeSourceSessionId != seekSessionId) {
                    false
                } else {
                    newJob =
                        eventScope.launch(start = CoroutineStart.LAZY) {
                            repeat(SEEK_COMPLETION_POLL_ATTEMPTS) {
                                if (!isActiveSourceSession(seekSessionId)) return@launch
                                val position = delegate.preciseCurrentTime
                                if (!delegate.isSeeking && isWithinSeekTolerance(position, target)) {
                                    emitSeekCompleted(seekSessionId, position)
                                    return@launch
                                }
                                delay(SEEK_COMPLETION_POLL_INTERVAL)
                            }
                        }
                    seekCompletionJob = newJob
                    true
                }
            }
        if (shouldStart) newJob.start()
        emitCurrentErrorIfChanged()
    }

    private fun isWithinSeekTolerance(
        position: Duration,
        target: Duration,
    ): Boolean =
        abs(position.inWholeMilliseconds - target.inWholeMilliseconds) <=
            SEEK_COMPLETION_TOLERANCE.inWholeMilliseconds

    private fun emitSeekCompleted(
        sessionId: Long,
        position: Duration,
    ) {
        eventStateLock.withLock {
            if (isDisposed || activeSourceSessionId != sessionId) return@withLock
            emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
                PlaybackEvent.SeekCompleted(
                    mediaSessionId = eventSessionId,
                    sampledAtMs = sampledAtMs,
                    position = position,
                )
            }
        }
    }

    override fun seekBy(delta: Duration) {
        ensureNotDisposed()
        seekTo(delegate.preciseCurrentTime + delta)
    }

    override fun seekToMs(timeMs: Long) {
        ensureNotDisposed()
        seekTo(timeMs.coerceAtLeast(0L).milliseconds)
    }

    override fun seekByMs(deltaMs: Long) {
        ensureNotDisposed()
        seekBy(deltaMs.milliseconds)
    }

    override fun seekToProgress(progress: Float) {
        ensureNotDisposed()
        val duration = delegate.duration
        if (duration > Duration.ZERO) {
            seekTo(duration * progress.coerceIn(0f, 1f).toDouble())
        } else {
            delegate.seekToProgress(progress)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        ensureNotDisposed()
        seekToProgress(value / VideoPlayerState.SLIDER_SCALE)
    }

    override fun seekStart(value: Float) {
        ensureNotDisposed()
        delegate.seekStart(value)
    }

    override fun seekFinished() {
        ensureNotDisposed()
        seekToProgress(delegate.sliderPos / VideoPlayerState.SLIDER_SCALE)
        delegate.userDragging = false
    }

    override fun restart() {
        ensureNotDisposed()
        val restartSessionId = sourceSessionToRelease()
        delegate.restart()
        if (restartSessionId != 0L) {
            emitPlaybackRestarted(restartSessionId)
        }
        emitCurrentErrorIfChanged()
    }

    override fun toggleFullscreen() {
        ensureNotDisposed()
        delegate.toggleFullscreen()
    }

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        ensureNotDisposed()
        val selectionSessionId = mediaSessionId
        val previousTrackId = delegate.currentAudioTrack?.id
        val result = delegate.selectAudioTrack(track)
        handleTrackSelectionResult(
            kind = TrackKind.AUDIO,
            result = result,
            selectionSessionId = selectionSessionId,
            previousTrackId = previousTrackId,
            currentTrackId = { delegate.currentAudioTrack?.id },
        )
        emitCurrentErrorIfChanged()
        return result
    }

    override fun selectAudioTrack(trackId: String?): TrackSelectionResult {
        ensureNotDisposed()
        return trackId
            ?.let { id ->
                delegate.availableAudioTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectAudioTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectAudioTrack(null as AudioTrack?)
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        ensureNotDisposed()
        val selectionSessionId = mediaSessionId
        val previousTrackId = delegate.currentSubtitleTrack?.id
        val result = delegate.selectSubtitleTrack(track)
        handleTrackSelectionResult(
            kind = TrackKind.SUBTITLE,
            result = result,
            selectionSessionId = selectionSessionId,
            previousTrackId = previousTrackId,
            currentTrackId = { delegate.currentSubtitleTrack?.id },
        )
        emitCurrentErrorIfChanged()
        return result
    }

    override fun selectSubtitleTrack(trackId: String?): TrackSelectionResult {
        ensureNotDisposed()
        return trackId
            ?.let { id ->
                delegate.availableSubtitleTracks
                    .firstOrNull { it.id == id }
                    ?.let(::selectSubtitleTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectSubtitleTrack(null as SubtitleTrack?)
    }

    override fun addSubtitleTrack(track: SubtitleTrack) {
        ensureNotDisposed()
        delegate.addSubtitleTrack(track)
    }

    override fun removeSubtitleTrack(trackId: String) {
        ensureNotDisposed()
        val selectionSessionId = mediaSessionId
        val selectedTrack = delegate.currentSubtitleTrack
        delegate.removeSubtitleTrack(trackId)
        if (selectedTrack?.id == trackId && selectedTrack.isExternal) {
            emitTrackChanged(TrackKind.SUBTITLE, null, selectionSessionId)
        }
    }

    override fun removeSubtitleTrack(track: SubtitleTrack) {
        removeSubtitleTrack(track.id)
    }

    override fun clearExternalSubtitleTracks() {
        ensureNotDisposed()
        val selectionSessionId = mediaSessionId
        val selectedTrack = delegate.currentSubtitleTrack
        delegate.clearExternalSubtitleTracks()
        if (selectedTrack?.isExternal == true) {
            emitTrackChanged(TrackKind.SUBTITLE, null, selectionSessionId)
        }
    }

    override fun replaceExternalSubtitleTracks(tracks: List<SubtitleTrack>) {
        ensureNotDisposed()
        val previouslySelectedExternalTrackId = delegate.currentSubtitleTrack?.takeIf { it.isExternal }?.id
        clearExternalSubtitleTracks()
        tracks.forEach(::addSubtitleTrack)
        if (previouslySelectedExternalTrackId != null) {
            val replacementTrack =
                delegate.availableSubtitleTracks.firstOrNull { track ->
                    track.id == previouslySelectedExternalTrackId && track.isExternal
                }
            if (replacementTrack != null) {
                selectSubtitleTrack(replacementTrack)
            }
        }
    }

    override fun disableSubtitles(): TrackSelectionResult {
        ensureNotDisposed()
        val selectionSessionId = mediaSessionId
        val previousTrackId = delegate.currentSubtitleTrack?.id
        val result = delegate.disableSubtitles()
        handleTrackSelectionResult(
            kind = TrackKind.SUBTITLE,
            result = result,
            selectionSessionId = selectionSessionId,
            previousTrackId = previousTrackId,
            currentTrackId = { delegate.currentSubtitleTrack?.id },
        )
        emitCurrentErrorIfChanged()
        return result
    }

    override val availableAdaptiveQualities: List<AdaptiveQualityVariant>
        get() = delegate.availableAdaptiveQualities

    override val currentAdaptiveQuality: AdaptiveQualityVariant?
        get() = delegate.currentAdaptiveQuality

    override val adaptiveQualityMode: AdaptiveQualityMode
        get() = delegate.adaptiveQualityMode

    override fun selectAdaptiveQuality(variantId: String?): AdaptiveQualitySelectionResult {
        ensureNotDisposed()
        return selectHlsQuality(variantId).toEventingAdaptiveQualitySelectionResult()
    }

    override fun selectAutoAdaptiveQuality(): AdaptiveQualitySelectionResult {
        ensureNotDisposed()
        return selectAdaptiveQuality(null)
    }

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult {
        ensureNotDisposed()
        val selectionSessionId = mediaSessionId
        val result = delegate.selectHlsQuality(variantId)
        handleAdaptiveQualitySelectionResult(
            result = result,
            selectionSessionId = selectionSessionId,
            requestedVariantId = variantId,
        )
        emitCurrentErrorIfChanged()
        return result
    }

    override fun selectAutoHlsQuality(): HlsQualitySelectionResult {
        ensureNotDisposed()
        return selectHlsQuality(null)
    }

    override fun playbackSnapshot(): PlaybackSnapshot =
        delegate
            .playbackSnapshot()
            .copy(
                mediaSessionId = mediaSessionId,
                sampledAtMs = Clock.System.now().toEpochMilliseconds(),
            )

    override fun clearError() {
        ensureNotDisposed()
        delegate.clearError()
        eventStateLock.withLock { lastError = null }
    }

    override fun clearCache(): CacheClearResult {
        ensureNotDisposed()
        return delegate.clearCache()
    }

    override fun dispose() {
        val delegateHasMedia = delegate.hasMedia
        val disposalState =
            eventStateLock.withLock {
                if (isDisposed) return@withLock null
                isDisposed = true
                val releasedSessionId = sourceSessionToReleaseUnsafe(delegateHasMedia)
                val jobs = clearActiveSourceUnsafe()
                playbackEndedCallback = null
                restartCallback = null
                releasedSessionId to jobs
            } ?: return

        disposalState.second.forEach { it.cancel() }
        detachDelegateCallbacks()
        delegate.dispose()
        if (disposalState.first != 0L) {
            emitSourceReleased(disposalState.first)
        }
        eventScope.cancel()
    }

    private fun ensureNotDisposed() {
        eventStateLock.withLock {
            check(!isDisposed) { DISPOSED_MESSAGE }
        }
    }

    private fun detachDelegateCallbacks() {
        if (delegate.onPlaybackEnded === delegatePlaybackEndedCallback) {
            delegate.onPlaybackEnded = null
        }
        if (delegate.onRestart === delegateRestartCallback) {
            delegate.onRestart = null
        }
    }

    private fun beginSource(uri: String): Long {
        val delegateHasMedia = delegate.hasMedia
        return eventStateLock.withLock {
            check(!isDisposed) { DISPOSED_MESSAGE }
            val previousSessionId = sourceSessionToReleaseUnsafe(delegateHasMedia)
            if (previousSessionId != 0L) {
                emitSourceReleased(previousSessionId)
            }
            clearActiveSourceUnsafe().forEach { it.cancel() }
            lastError = null
            val sessionId = eventDispatcher.nextMediaSessionId()
            activeSourceSessionId = sessionId
            emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
                PlaybackEvent.SourcePreparing(
                    mediaSessionId = eventSessionId,
                    sampledAtMs = sampledAtMs,
                    uri = uri,
                )
            }
            sessionId
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun openSource(
        uri: String,
        open: () -> Unit,
    ) {
        ensureNotDisposed()
        val sessionId = beginSource(uri)
        try {
            open()
        } catch (e: UnsupportedOperationException) {
            failSourceOpen(sessionId, e)
        } catch (e: IllegalArgumentException) {
            failSourceOpen(sessionId, e)
        } catch (e: IllegalStateException) {
            failSourceOpen(sessionId, e)
        } catch (e: RuntimeException) {
            failSourceOpen(sessionId, e)
        }
        scheduleSourceLoaded(sessionId)
        emitCurrentErrorIfChanged()
    }

    private fun scheduleSourceLoaded(sessionId: Long) {
        lateinit var newJob: Job
        val shouldStart =
            eventStateLock.withLock {
                if (isDisposed || activeSourceSessionId != sessionId || mediaSessionId != sessionId) {
                    false
                } else {
                    sourceLoadedJob?.cancel()
                    newJob =
                        eventScope.launch(start = CoroutineStart.LAZY) {
                            while (isActiveSourceSession(sessionId)) {
                                val pollInterval =
                                    eventStateLock.withLock {
                                        if (sourceLoadedSessionId == sessionId) {
                                            SOURCE_MONITOR_POLL_INTERVAL
                                        } else {
                                            SOURCE_LOAD_POLL_INTERVAL
                                        }
                                    }
                                delay(pollInterval)
                                if (!isActiveSourceSession(sessionId)) return@launch
                                emitCurrentErrorIfChanged()
                                val sourceIsReady =
                                    delegate.hasMedia &&
                                        (!delegate.isLoading || delegate.duration > Duration.ZERO)
                                if (sourceIsReady) {
                                    val loadedDuration = delegate.duration
                                    eventStateLock.withLock {
                                        if (isDisposed ||
                                            activeSourceSessionId != sessionId ||
                                            sourceLoadedSessionId == sessionId
                                        ) {
                                            return@withLock
                                        }
                                        sourceLoadedSessionId = sessionId
                                        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
                                            PlaybackEvent.SourceLoaded(
                                                mediaSessionId = eventSessionId,
                                                sampledAtMs = sampledAtMs,
                                                duration = loadedDuration,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    sourceLoadedJob = newJob
                    true
                }
            }
        if (shouldStart) newJob.start()
    }

    private fun failSourceOpen(
        sessionId: Long,
        exception: RuntimeException,
    ): Nothing {
        eventStateLock.withLock {
            if (!isDisposed && activeSourceSessionId == sessionId) {
                clearActiveSourceUnsafe().forEach { it.cancel() }
                emitOpenFailed(sessionId, exception)
            }
        }
        throw exception
    }

    private fun emitOpenFailed(
        sessionId: Long,
        exception: RuntimeException,
    ) {
        val error = exception.toVideoPlayerError()
        lastError = error
        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
            PlaybackEvent.Error(
                mediaSessionId = eventSessionId,
                sampledAtMs = sampledAtMs,
                error = error,
            )
        }
    }

    private fun RuntimeException.toVideoPlayerError(): VideoPlayerError =
        when (this) {
            is UnsupportedOperationException ->
                VideoPlayerError.SourceError(message ?: "Source operation is not supported")

            else ->
                VideoPlayerError.UnknownError(message ?: "Source operation failed")
        }

    private fun clampedSeekTarget(time: Duration): Duration =
        when {
            time < Duration.ZERO -> Duration.ZERO
            delegate.duration > Duration.ZERO && time > delegate.duration -> delegate.duration
            else -> time
        }

    private fun emitCurrentErrorIfChanged() {
        val errorSessionId = mediaSessionId
        val currentError = delegate.error
        eventStateLock.withLock {
            if (isDisposed || mediaSessionId != errorSessionId) return@withLock
            if (currentError == null) {
                lastError = null
                return@withLock
            }
            if (currentError == lastError) return@withLock
            lastError = currentError
            emitPlaybackEventForSession(errorSessionId) { sessionId, sampledAtMs ->
                PlaybackEvent.Error(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    error = currentError,
                )
            }
        }
    }

    private fun emitTrackChanged(
        kind: TrackKind,
        trackId: String?,
        expectedSessionId: Long? = null,
    ) {
        eventStateLock.withLock {
            if (isDisposed || (expectedSessionId != null && mediaSessionId != expectedSessionId)) return@withLock
            val eventSessionId = expectedSessionId ?: mediaSessionId
            emitPlaybackEventForSession(eventSessionId) { sessionId, sampledAtMs ->
                PlaybackEvent.TrackChanged(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                    kind = kind,
                    trackId = trackId,
                )
            }
        }
    }

    private fun handleTrackSelectionResult(
        kind: TrackKind,
        result: TrackSelectionResult,
        selectionSessionId: Long,
        previousTrackId: String?,
        currentTrackId: () -> String?,
    ) {
        val previousSelectionJob =
            eventStateLock.withLock {
                when (kind) {
                    TrackKind.AUDIO -> audioTrackSelectionJob.also { audioTrackSelectionJob = null }
                    TrackKind.SUBTITLE -> subtitleTrackSelectionJob.also { subtitleTrackSelectionJob = null }
                    TrackKind.HLS_QUALITY -> null
                }
            }
        previousSelectionJob?.cancel()

        if (!result.isApplied) return
        val requestedTrackId = result.trackChangedEventId()
        if (requestedTrackId == previousTrackId) return
        if (currentTrackId() == requestedTrackId) {
            emitTrackChanged(kind, requestedTrackId, selectionSessionId)
            return
        }

        lateinit var newJob: Job
        val shouldStart =
            eventStateLock.withLock {
                if (isDisposed || mediaSessionId != selectionSessionId) {
                    false
                } else {
                    newJob =
                        eventScope.launch(start = CoroutineStart.LAZY) {
                            repeat(TRACK_SELECTION_CONFIRMATION_POLL_ATTEMPTS) {
                                delay(TRACK_SELECTION_CONFIRMATION_POLL_INTERVAL)
                                if (!isCurrentEventSession(selectionSessionId)) return@launch
                                emitCurrentErrorIfChanged()
                                if (currentTrackId() == requestedTrackId) {
                                    eventStateLock.withLock {
                                        if (isDisposed || mediaSessionId != selectionSessionId) return@withLock
                                        emitPlaybackEventForSession(selectionSessionId) { sessionId, sampledAtMs ->
                                            PlaybackEvent.TrackChanged(
                                                mediaSessionId = sessionId,
                                                sampledAtMs = sampledAtMs,
                                                kind = kind,
                                                trackId = requestedTrackId,
                                            )
                                        }
                                    }
                                    return@launch
                                }
                            }
                        }
                    when (kind) {
                        TrackKind.AUDIO -> audioTrackSelectionJob = newJob
                        TrackKind.SUBTITLE -> subtitleTrackSelectionJob = newJob
                        TrackKind.HLS_QUALITY -> Unit
                    }
                    true
                }
            }
        if (shouldStart) newJob.start()
    }

    private fun handleAdaptiveQualitySelectionResult(
        result: HlsQualitySelectionResult,
        selectionSessionId: Long,
        requestedVariantId: String?,
    ) {
        val previousJob =
            eventStateLock.withLock {
                adaptiveQualitySelectionJob.also { adaptiveQualitySelectionJob = null }
            }
        previousJob?.cancel()
        if (!result.isApplied) return

        val isConfirmed: () -> Boolean = {
            if (requestedVariantId == null) {
                delegate.adaptiveQualityMode == AdaptiveQualityMode.AUTO
            } else {
                delegate.adaptiveQualityMode == AdaptiveQualityMode.MANUAL &&
                    delegate.currentAdaptiveQuality?.id == requestedVariantId
            }
        }
        if (isConfirmed()) {
            emitTrackChanged(TrackKind.HLS_QUALITY, requestedVariantId, selectionSessionId)
            return
        }

        lateinit var newJob: Job
        val shouldStart =
            eventStateLock.withLock {
                if (isDisposed || mediaSessionId != selectionSessionId) {
                    false
                } else {
                    newJob =
                        eventScope.launch(start = CoroutineStart.LAZY) {
                            repeat(TRACK_SELECTION_CONFIRMATION_POLL_ATTEMPTS) {
                                delay(TRACK_SELECTION_CONFIRMATION_POLL_INTERVAL)
                                if (!isCurrentEventSession(selectionSessionId)) return@launch
                                emitCurrentErrorIfChanged()
                                if (isConfirmed()) {
                                    emitTrackChanged(
                                        TrackKind.HLS_QUALITY,
                                        requestedVariantId,
                                        selectionSessionId,
                                    )
                                    return@launch
                                }
                            }
                        }
                    adaptiveQualitySelectionJob = newJob
                    true
                }
            }
        if (shouldStart) newJob.start()
    }

    private fun emitPlaybackRestarted(sessionId: Long) {
        eventStateLock.withLock {
            if (isDisposed || activeSourceSessionId != sessionId) return@withLock
            emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
                PlaybackEvent.PlaybackRestarted(
                    mediaSessionId = eventSessionId,
                    sampledAtMs = sampledAtMs,
                )
            }
        }
    }

    private fun TrackSelectionResult.trackChangedEventId(): String? =
        when (this) {
            is TrackSelectionResult.Selected -> trackId
            TrackSelectionResult.Auto,
            TrackSelectionResult.Disabled,
            is TrackSelectionResult.Failed,
            is TrackSelectionResult.NotFound,
            TrackSelectionResult.NotSupported,
            -> null
        }

    private fun emitSourceReleased(sessionId: Long) {
        if (sessionId == 0L) return
        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
            PlaybackEvent.SourceReleased(
                mediaSessionId = eventSessionId,
                sampledAtMs = sampledAtMs,
            )
        }
    }

    private fun sourceSessionToRelease(): Long {
        val delegateHasMedia = delegate.hasMedia
        return eventStateLock.withLock { sourceSessionToReleaseUnsafe(delegateHasMedia) }
    }

    private fun sourceSessionToReleaseUnsafe(delegateHasMedia: Boolean): Long =
        activeSourceSessionId.takeIf { it != 0L }
            ?: mediaSessionId.takeIf { delegateHasMedia }
            ?: 0L

    private fun canSeekCurrentSource(): Boolean =
        sourceSessionToRelease() != 0L && (delegate.hasMedia || delegate.duration > Duration.ZERO)

    private fun isActiveSourceSession(sessionId: Long): Boolean =
        eventStateLock.withLock {
            !isDisposed && activeSourceSessionId == sessionId && mediaSessionId == sessionId
        }

    private fun isCurrentEventSession(sessionId: Long): Boolean =
        eventStateLock.withLock { !isDisposed && mediaSessionId == sessionId }

    private fun cancelSeekCompletionJob() {
        val previousSeekCompletionJob =
            eventStateLock.withLock {
                seekCompletionJob.also { seekCompletionJob = null }
            }
        previousSeekCompletionJob?.cancel()
    }

    /** Must be called with eventStateLock held. Returns jobs that were detached from the active source. */
    private fun clearActiveSourceUnsafe(): List<Job> {
        val jobs =
            listOfNotNull(
                sourceLoadedJob,
                seekCompletionJob,
                audioTrackSelectionJob,
                subtitleTrackSelectionJob,
                adaptiveQualitySelectionJob,
            )
        sourceLoadedJob = null
        seekCompletionJob = null
        audioTrackSelectionJob = null
        subtitleTrackSelectionJob = null
        adaptiveQualitySelectionJob = null
        activeSourceSessionId = 0L
        sourceLoadedSessionId = 0L
        return jobs
    }

    private fun emitPlaybackEvent(factory: (Long, Long) -> PlaybackEvent) {
        eventDispatcher.emit(factory)
    }

    private fun emitPlaybackEventForSession(
        sessionId: Long,
        factory: (Long, Long) -> PlaybackEvent,
    ) {
        eventDispatcher.emitForSession(sessionId, factory)
    }

    companion object {
        private const val DISPOSED_MESSAGE = "VideoPlayerState has been disposed"
        private val SOURCE_LOAD_POLL_INTERVAL = 50.milliseconds
        private val SOURCE_MONITOR_POLL_INTERVAL = 250.milliseconds
        private const val SEEK_COMPLETION_POLL_ATTEMPTS = 250
        private val SEEK_COMPLETION_POLL_INTERVAL = 20.milliseconds
        private val SEEK_COMPLETION_TOLERANCE = 250.milliseconds
        private const val TRACK_SELECTION_CONFIRMATION_POLL_ATTEMPTS = 250
        private val TRACK_SELECTION_CONFIRMATION_POLL_INTERVAL = 20.milliseconds
    }
}

private fun HlsQualitySelectionResult.toEventingAdaptiveQualitySelectionResult(): AdaptiveQualitySelectionResult =
    when (this) {
        HlsQualitySelectionResult.Auto -> AdaptiveQualitySelectionResult.Auto
        is HlsQualitySelectionResult.Selected ->
            AdaptiveQualitySelectionResult.Selected(
                AdaptiveQualityVariant(
                    id = quality.id,
                    label = quality.label,
                    width = quality.width,
                    height = quality.height,
                    bitrate = quality.bitrate,
                    codecs = quality.codecs,
                ),
            )
        is HlsQualitySelectionResult.NotFound -> AdaptiveQualitySelectionResult.NotFound(variantId)
        HlsQualitySelectionResult.NotSupported -> AdaptiveQualitySelectionResult.NotSupported
    }
