package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.util.PipResult
import io.github.kdroidfilter.composemediaplayer.util.getUri
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Suppress("TooManyFunctions")
internal class EventingVideoPlayerState(
    private val delegate: VideoPlayerState,
) : VideoPlayerState by delegate {
    private val eventDispatcher = PlaybackEventDispatcher()
    private val eventScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeSourceSessionId = 0L
    private var sourceLoadedSessionId = 0L
    private var sourceLoadedJob: Job? = null
    private var lastError: VideoPlayerError? = null
    private var playbackEndedCallback: (() -> Unit)? = null
    private var restartCallback: (() -> Unit)? = null
    private var isDisposed = false
    private val delegatePlaybackEndedCallback: () -> Unit = {
        if (!isDisposed) {
            emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.PlaybackEnded(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                )
            }
            playbackEndedCallback?.invoke()
        }
    }
    private val delegateRestartCallback: () -> Unit = {
        if (!isDisposed) {
            emitPlaybackEvent { sessionId, sampledAtMs ->
                PlaybackEvent.PlaybackRestarted(
                    mediaSessionId = sessionId,
                    sampledAtMs = sampledAtMs,
                )
            }
            restartCallback?.invoke()
        }
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
        get() = playbackEndedCallback
        set(value) {
            ensureNotDisposed()
            playbackEndedCallback = value
        }

    override var onRestart: (() -> Unit)?
        get() = restartCallback
        set(value) {
            ensureNotDisposed()
            restartCallback = value
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
        val releasedSessionId = sourceSessionToRelease()
        delegate.stop()
        clearActiveSource()
        if (releasedSessionId != 0L) {
            emitSourceReleased(releasedSessionId)
            eventDispatcher.nextMediaSessionId()
        }
        emitCurrentErrorIfChanged()
    }

    override fun releaseSource() {
        ensureNotDisposed()
        val releasedSessionId = sourceSessionToRelease()
        delegate.releaseSource()
        clearActiveSource()
        if (releasedSessionId != 0L) {
            emitSourceReleased(releasedSessionId)
            eventDispatcher.nextMediaSessionId()
        }
        emitCurrentErrorIfChanged()
    }

    override fun seekTo(time: Duration) {
        ensureNotDisposed()
        if (!canSeekCurrentSource()) {
            emitCurrentErrorIfChanged()
            return
        }
        val target = clampedSeekTarget(time)
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.SeekStarted(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                target = target,
            )
        }
        delegate.seekTo(target)
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.SeekCompleted(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                position = delegate.preciseCurrentTime,
            )
        }
        emitCurrentErrorIfChanged()
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
        val hasActiveSource = sourceSessionToRelease() != 0L
        delegate.restart()
        if (hasActiveSource) {
            emitPlaybackRestarted()
        }
        emitCurrentErrorIfChanged()
    }

    override fun toggleFullscreen() {
        ensureNotDisposed()
        delegate.toggleFullscreen()
    }

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        ensureNotDisposed()
        val result = delegate.selectAudioTrack(track)
        if (result.isApplied) {
            emitTrackChanged(TrackKind.AUDIO, result.trackChangedEventId())
        }
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
        val result = delegate.selectSubtitleTrack(track)
        if (result.isApplied) {
            emitTrackChanged(TrackKind.SUBTITLE, result.trackChangedEventId())
        }
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
        val selectedTrack = delegate.currentSubtitleTrack
        delegate.removeSubtitleTrack(trackId)
        if (selectedTrack?.id == trackId && selectedTrack.isExternal) {
            emitTrackChanged(TrackKind.SUBTITLE, null)
        }
    }

    override fun removeSubtitleTrack(track: SubtitleTrack) {
        removeSubtitleTrack(track.id)
    }

    override fun clearExternalSubtitleTracks() {
        ensureNotDisposed()
        val selectedTrack = delegate.currentSubtitleTrack
        delegate.clearExternalSubtitleTracks()
        if (selectedTrack?.isExternal == true) {
            emitTrackChanged(TrackKind.SUBTITLE, null)
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
        val result = delegate.disableSubtitles()
        if (result.isApplied) {
            emitTrackChanged(TrackKind.SUBTITLE, null)
        }
        emitCurrentErrorIfChanged()
        return result
    }

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult {
        ensureNotDisposed()
        val result = delegate.selectHlsQuality(variantId)
        if (result.isApplied) {
            emitTrackChanged(
                kind = TrackKind.HLS_QUALITY,
                trackId =
                    when (result) {
                        HlsQualitySelectionResult.Auto -> null
                        is HlsQualitySelectionResult.Selected -> result.quality.id
                        else -> variantId
                    },
            )
        }
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
        lastError = null
    }

    override fun clearCache(): CacheClearResult {
        ensureNotDisposed()
        return delegate.clearCache()
    }

    override fun dispose() {
        if (isDisposed) return

        val releasedSessionId = sourceSessionToRelease()
        isDisposed = true
        detachDelegateCallbacks()
        delegate.dispose()
        clearActiveSource()
        if (releasedSessionId != 0L) {
            emitSourceReleased(releasedSessionId)
        }
        playbackEndedCallback = null
        restartCallback = null
        eventScope.cancel()
    }

    private fun ensureNotDisposed() {
        check(!isDisposed) { "VideoPlayerState has been disposed and cannot be reused." }
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
        val previousSessionId = sourceSessionToRelease()
        if (previousSessionId != 0L) {
            emitSourceReleased(previousSessionId)
        }
        clearActiveSource()
        lastError = null
        val sessionId = eventDispatcher.nextMediaSessionId()
        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
            PlaybackEvent.SourcePreparing(
                mediaSessionId = eventSessionId,
                sampledAtMs = sampledAtMs,
                uri = uri,
            )
        }
        return sessionId
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
            failSourceOpen(e)
        } catch (e: IllegalArgumentException) {
            failSourceOpen(e)
        } catch (e: IllegalStateException) {
            failSourceOpen(e)
        } catch (e: RuntimeException) {
            failSourceOpen(e)
        }
        activeSourceSessionId = sessionId
        scheduleSourceLoaded()
        emitCurrentErrorIfChanged()
    }

    private fun scheduleSourceLoaded() {
        val sessionId = mediaSessionId
        sourceLoadedJob?.cancel()
        sourceLoadedJob =
            eventScope.launch {
                repeat(SOURCE_LOAD_POLL_ATTEMPTS) {
                    delay(SOURCE_LOAD_POLL_INTERVAL)
                    if (sessionId != mediaSessionId || sourceLoadedSessionId == sessionId) return@launch
                    emitCurrentErrorIfChanged()
                    if (delegate.hasMedia && (!delegate.isLoading || delegate.duration > Duration.ZERO)) {
                        sourceLoadedSessionId = sessionId
                        emitPlaybackEventForSession(sessionId) { eventSessionId, sampledAtMs ->
                            PlaybackEvent.SourceLoaded(
                                mediaSessionId = eventSessionId,
                                sampledAtMs = sampledAtMs,
                                duration = delegate.duration,
                            )
                        }
                        return@launch
                    }
                }
            }
    }

    private fun failSourceOpen(exception: RuntimeException): Nothing {
        clearActiveSource()
        emitOpenFailed(exception)
        throw exception
    }

    private fun emitOpenFailed(exception: RuntimeException) {
        val error = exception.toVideoPlayerError()
        lastError = error
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.Error(
                mediaSessionId = sessionId,
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
        val currentError = delegate.error
        if (currentError == null || currentError == lastError) return
        lastError = currentError
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.Error(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                error = currentError,
            )
        }
    }

    private fun emitTrackChanged(
        kind: TrackKind,
        trackId: String?,
    ) {
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.TrackChanged(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
                kind = kind,
                trackId = trackId,
            )
        }
    }

    private fun emitPlaybackRestarted() {
        emitPlaybackEvent { sessionId, sampledAtMs ->
            PlaybackEvent.PlaybackRestarted(
                mediaSessionId = sessionId,
                sampledAtMs = sampledAtMs,
            )
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

    private fun sourceSessionToRelease(): Long =
        activeSourceSessionId.takeIf { it != 0L }
            ?: mediaSessionId.takeIf { delegate.hasMedia }
            ?: 0L

    private fun canSeekCurrentSource(): Boolean =
        sourceSessionToRelease() != 0L && (delegate.hasMedia || delegate.duration > Duration.ZERO)

    private fun clearActiveSource() {
        sourceLoadedJob?.cancel()
        activeSourceSessionId = 0L
        sourceLoadedSessionId = 0L
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
        private const val SOURCE_LOAD_POLL_ATTEMPTS = 100
        private val SOURCE_LOAD_POLL_INTERVAL = 50.milliseconds
    }
}
