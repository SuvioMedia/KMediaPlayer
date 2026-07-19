package io.github.kdroidfilter.composemediaplayer.mpv

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.kdroidfilter.composemediaplayer.AudioTrack
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MediaChapter
import io.github.kdroidfilter.composemediaplayer.PlaybackDiagnostics
import io.github.kdroidfilter.composemediaplayer.PlaybackEvent
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.TrackKind
import io.github.kdroidfilter.composemediaplayer.TrackSelectionResult
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoProjectionSettings
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewControlMode
import io.github.kdroidfilter.composemediaplayer.VideoProjectionViewSettings
import io.github.kdroidfilter.composemediaplayer.VideoTextureCrop
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Shared observable state and command semantics for MPV platform adapters.
 *
 * Android, iOS, and desktop keep only native source resolution, runtime polling and rendering.
 * This class deliberately contains no KMediaMpv or libmpv types, which keeps the common
 * adapter contract testable without loading a native runtime.
 */
@Stable
internal abstract class AbstractMpvVideoPlayerState protected constructor() : VideoPlayerState {
    protected val events = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    protected val audioTracks = mutableStateListOf<AudioTrack>()
    protected val subtitleTracks = mutableStateListOf<SubtitleTrack>()
    protected val registeredExternalSubtitles = linkedMapOf<String, SubtitleTrack>()
    protected val externalSubtitles: MutableMap<String, SubtitleTrack>
        get() = registeredExternalSubtitles

    protected var _mediaSessionId by mutableStateOf(0L)
    protected var _hasMedia by mutableStateOf(false)
    protected var _isPlaying by mutableStateOf(false)
    protected var _isLoading by mutableStateOf(false)
    protected var _isSeeking by mutableStateOf(false)
    protected var _volume by mutableStateOf(1f)
    protected var _sliderPos by mutableStateOf(0f)
    protected var _userDragging by mutableStateOf(false)
    protected var _loop by mutableStateOf(false)
    protected var _playbackSpeed by mutableStateOf(1f)
    protected var _isFullscreen by mutableStateOf(false)
    protected var _currentTime by mutableStateOf(Duration.ZERO)
    protected var _duration by mutableStateOf(Duration.ZERO)
    protected var _chapters by mutableStateOf(emptyList<MediaChapter>())
    protected var _aspectRatio by mutableStateOf(DEFAULT_ASPECT_RATIO)
    protected var _error by mutableStateOf<VideoPlayerError?>(null)
    protected var _currentAudioTrack by mutableStateOf<AudioTrack?>(null)
    protected var _subtitlesEnabled by mutableStateOf(false)
    protected var _currentSubtitleTrack by mutableStateOf<SubtitleTrack?>(null)
    protected var _subtitleOffset by mutableStateOf(Duration.ZERO)

    final override val metadata = VideoMetadata()
    final override val diagnostics: PlaybackDiagnostics
        get() =
            PlaybackDiagnostics(
                videoWidth = metadata.width,
                videoHeight = metadata.height,
                notes = renderingInfo.notes,
            )

    final override var projection: VideoProjectionSettings by mutableStateOf(VideoProjectionSettings())
    final override var projectionView: VideoProjectionViewSettings by mutableStateOf(VideoProjectionViewSettings())
    final override var projectionViewControlMode: VideoProjectionViewControlMode by
        mutableStateOf(VideoProjectionViewControlMode.AUTO)
    final override var projectionTextureCrop: VideoTextureCrop by mutableStateOf(VideoTextureCrop())

    final override val mediaSessionId: Long get() = _mediaSessionId
    final override val hasMedia: Boolean get() = _hasMedia
    final override val isPlaying: Boolean get() = _isPlaying
    final override val isLoading: Boolean get() = _isLoading
    final override val isSeeking: Boolean get() = _isSeeking
    final override val playbackEvents: SharedFlow<PlaybackEvent> get() = events
    final override val error: VideoPlayerError? get() = _error
    final override val currentTime: Duration get() = _currentTime
    final override val duration: Duration get() = _duration
    final override val chapters: List<MediaChapter> get() = _chapters
    final override val positionText: String get() = _currentTime.asClockText()
    final override val durationText: String get() = _duration.asClockText()
    final override val aspectRatio: Float get() = _aspectRatio
    final override val availableAudioTracks: List<AudioTrack> get() = audioTracks
    final override val availableSubtitleTracks: List<SubtitleTrack> get() = subtitleTracks

    protected abstract val backendDisposed: Boolean

    protected abstract fun setBackendVolume(value: Float)

    protected abstract fun setBackendLoop(value: Boolean)

    protected abstract fun setBackendPlaybackSpeed(value: Float)

    protected abstract fun setBackendSubtitleOffset(value: Duration)

    protected abstract fun playBackend()

    protected abstract fun pauseBackend()

    protected abstract fun seekBackend(time: Duration)

    protected abstract fun selectBackendAudioTrack(track: AudioTrack?): TrackSelectionResult

    protected abstract fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult

    protected abstract fun disableBackendSubtitles(): TrackSelectionResult

    protected abstract fun validateExternalSubtitle(track: SubtitleTrack)

    protected abstract fun removeBackendExternalSubtitle(track: SubtitleTrack)

    protected open val seekFailureMessage: String
        get() = "MPV rejected the seek request."

    final override var volume: Float
        get() = _volume
        set(value) {
            ensureOpen()
            val normalized = value.coerceIn(0f, 1f)
            setBackendVolume(normalized)
            _volume = normalized
        }

    final override var sliderPos: Float
        get() = _sliderPos
        set(value) {
            _sliderPos = value.coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
        }

    final override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            _userDragging = value
        }

    final override var loop: Boolean
        get() = _loop
        set(value) {
            ensureOpen()
            setBackendLoop(value)
            _loop = value
        }

    final override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            ensureOpen()
            val normalized =
                value.coerceIn(
                    VideoPlayerState.MIN_PLAYBACK_SPEED,
                    VideoPlayerState.MAX_PLAYBACK_SPEED,
                )
            setBackendPlaybackSpeed(normalized)
            _playbackSpeed = normalized
        }

    final override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            _isFullscreen = value
        }

    final override var currentAudioTrack: AudioTrack?
        get() = _currentAudioTrack
        set(value) {
            selectAudioTrack(value)
        }

    final override var subtitlesEnabled: Boolean
        get() = _subtitlesEnabled
        set(value) {
            if (value) {
                _currentSubtitleTrack?.let(::selectSubtitleTrack)
            } else {
                disableSubtitles()
            }
        }

    final override var currentSubtitleTrack: SubtitleTrack?
        get() = _currentSubtitleTrack
        set(value) {
            selectSubtitleTrack(value)
        }

    final override var subtitleTextStyle: TextStyle by mutableStateOf(TextStyle.Default)
    final override var subtitleBackgroundColor: Color by mutableStateOf(Color.Transparent)
    final override var subtitleOffset: Duration
        get() = _subtitleOffset
        set(value) {
            ensureOpen()
            setBackendSubtitleOffset(value)
            _subtitleOffset = value
        }

    final override var onPlaybackEnded: (() -> Unit)? = null
    final override var onRestart: (() -> Unit)? = null

    final override fun play() {
        ensureOpen()
        if (!_hasMedia) return
        playBackend()
        _isPlaying = true
    }

    final override fun pause() {
        ensureOpen()
        if (!_hasMedia) return
        pauseBackend()
        _isPlaying = false
    }

    final override fun stop() {
        releaseSource()
    }

    final override fun seekTo(time: Duration) {
        ensureOpen()
        if (!_hasMedia) return
        val target = time.coerceIn(Duration.ZERO, _duration.takeIf { it > Duration.ZERO } ?: time)
        _isSeeking = true
        emitEvent(
            PlaybackEvent.SeekStarted(
                mediaSessionId = _mediaSessionId,
                sampledAtMs = nowMillis(),
                target = target,
            ),
        )
        try {
            seekBackend(target)
        } catch (_: RuntimeException) {
            _isSeeking = false
            publishError(VideoPlayerError.UnknownError(seekFailureMessage))
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    final override fun seekTo(value: Float) {
        val target =
            if (_duration > Duration.ZERO) {
                _duration * (value / VideoPlayerState.SLIDER_SCALE).coerceIn(0f, 1f).toDouble()
            } else {
                Duration.ZERO
            }
        seekTo(target)
    }

    final override fun toggleFullscreen() {
        _isFullscreen = !_isFullscreen
    }

    final override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        ensureOpen()
        if (track != null && audioTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }
        val result = selectBackendAudioTrack(track)
        when (result) {
            TrackSelectionResult.Auto -> {
                _currentAudioTrack = null
                emitTrackChanged(TrackKind.AUDIO, null)
            }
            is TrackSelectionResult.Selected -> {
                _currentAudioTrack = track
                emitTrackChanged(TrackKind.AUDIO, result.trackId)
            }
            else -> Unit
        }
        return result
    }

    final override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        ensureOpen()
        if (track == null) return disableSubtitles()
        if (subtitleTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }
        if (track.isExternal) {
            validateExternalSubtitle(track)
            registeredExternalSubtitles[track.id] = track
        }
        val result = selectBackendSubtitleTrack(track)
        if (result is TrackSelectionResult.Selected) {
            _currentSubtitleTrack = track
            _subtitlesEnabled = true
            renderingInfo.subtitleSource = if (track.isEmbedded) "embedded" else "external"
            emitTrackChanged(TrackKind.SUBTITLE, result.trackId)
        }
        return result
    }

    final override fun addSubtitleTrack(track: SubtitleTrack) {
        ensureOpen()
        val external = track.copy(isEmbedded = false)
        validateExternalSubtitle(external)
        registeredExternalSubtitles[external.id] = external
        val index = subtitleTracks.indexOfFirst { it.id == external.id }
        if (index >= 0) {
            subtitleTracks[index] = external
        } else {
            subtitleTracks += external
        }
    }

    final override fun removeSubtitleTrack(trackId: String) {
        ensureOpen()
        val removed = registeredExternalSubtitles.remove(trackId) ?: return
        removeBackendExternalSubtitle(removed)
        subtitleTracks.removeAll { it.id == trackId && it.isExternal }
        if (_currentSubtitleTrack?.id == trackId) {
            disableSubtitles()
        }
    }

    final override fun clearExternalSubtitleTracks() {
        ensureOpen()
        registeredExternalSubtitles.keys.toList().forEach(::removeSubtitleTrack)
        subtitleTracks.removeAll { it.isExternal }
    }

    final override fun disableSubtitles(): TrackSelectionResult {
        ensureOpen()
        if (!_subtitlesEnabled && _currentSubtitleTrack == null) {
            return TrackSelectionResult.Disabled
        }
        val result = disableBackendSubtitles()
        if (result.isApplied) {
            _currentSubtitleTrack = null
            _subtitlesEnabled = false
            renderingInfo.subtitleSource = null
            emitTrackChanged(TrackKind.SUBTITLE, null)
        }
        return result
    }

    final override fun clearError() {
        _error = null
    }

    protected fun ensureOpen() {
        check(!backendDisposed) { "${this::class.simpleName ?: "MPV player"} has been disposed." }
    }

    protected fun beginSourcePreparation(
        uri: String,
        initializePlayerState: InitialPlayerState,
    ) {
        _mediaSessionId += 1L
        _error = null
        _hasMedia = true
        _isLoading = true
        _isSeeking = false
        _isPlaying = initializePlayerState == InitialPlayerState.PLAY
        _currentTime = Duration.ZERO
        _duration = Duration.ZERO
        _chapters = emptyList()
        _sliderPos = 0f
        _aspectRatio = DEFAULT_ASPECT_RATIO
        clearMetadata()
        audioTracks.clear()
        subtitleTracks.clear()
        subtitleTracks.addAll(registeredExternalSubtitles.values)
        _currentAudioTrack = null
        _currentSubtitleTrack = null
        _subtitlesEnabled = false
        emitEvent(
            PlaybackEvent.SourcePreparing(
                mediaSessionId = _mediaSessionId,
                sampledAtMs = nowMillis(),
                uri = uri,
            ),
        )
    }

    protected fun sourceLoaded() {
        _isLoading = false
        _hasMedia = true
        emitEvent(
            PlaybackEvent.SourceLoaded(
                mediaSessionId = _mediaSessionId,
                sampledAtMs = nowMillis(),
                duration = _duration,
            ),
        )
    }

    protected fun resetSourceState() {
        val releasedSession = _mediaSessionId
        Snapshot.withMutableSnapshot {
            _hasMedia = false
            _isPlaying = false
            _isLoading = false
            _isSeeking = false
            _currentTime = Duration.ZERO
            _duration = Duration.ZERO
            _chapters = emptyList()
            _sliderPos = 0f
            _aspectRatio = DEFAULT_ASPECT_RATIO
            clearMetadata()
            audioTracks.clear()
            subtitleTracks.clear()
            subtitleTracks.addAll(registeredExternalSubtitles.values)
            _currentAudioTrack = null
            _currentSubtitleTrack = null
            _subtitlesEnabled = false
        }
        emitEvent(
            PlaybackEvent.SourceReleased(
                mediaSessionId = releasedSession,
                sampledAtMs = nowMillis(),
            ),
        )
    }

    protected fun updatePlaybackPosition(
        position: Duration,
        mediaDuration: Duration,
        playing: Boolean? = null,
        loading: Boolean? = null,
        seeking: Boolean? = null,
    ) {
        Snapshot.withMutableSnapshot {
            _currentTime = position
            _duration = mediaDuration
            playing?.let { _isPlaying = it }
            loading?.let { _isLoading = it }
            seeking?.let { _isSeeking = it }
            if (!_userDragging && mediaDuration > Duration.ZERO) {
                _sliderPos =
                    (
                        position.inWholeMilliseconds.toDouble() /
                            mediaDuration.inWholeMilliseconds.toDouble() *
                            VideoPlayerState.SLIDER_SCALE
                    ).toFloat()
                        .coerceIn(0f, VideoPlayerState.SLIDER_SCALE)
            }
            metadata.duration = mediaDuration.takeIf { it > Duration.ZERO }
        }
    }

    protected fun replaceDiscoveredTracks(
        discoveredAudio: List<AudioTrack>,
        discoveredSubtitles: List<SubtitleTrack>,
        selectedAudio: AudioTrack?,
        selectedSubtitle: SubtitleTrack?,
    ) {
        Snapshot.withMutableSnapshot {
            audioTracks.clear()
            audioTracks.addAll(discoveredAudio)
            subtitleTracks.clear()
            subtitleTracks.addAll(registeredExternalSubtitles.values)
            discoveredSubtitles.forEach { discovered ->
                if (subtitleTracks.none { it.id == discovered.id }) subtitleTracks += discovered
            }
            _currentAudioTrack = selectedAudio
            _currentSubtitleTrack = selectedSubtitle
            _subtitlesEnabled = selectedSubtitle != null
            renderingInfo.subtitleSource =
                selectedSubtitle?.let { track -> if (track.isEmbedded) "embedded" else "external" }
        }
    }

    protected fun replaceDiscoveredChapters(chapters: List<MediaChapter>) {
        Snapshot.withMutableSnapshot {
            _chapters = chapters
        }
    }

    protected fun emitTrackChanged(
        kind: TrackKind,
        trackId: String?,
    ) {
        emitEvent(
            PlaybackEvent.TrackChanged(
                mediaSessionId = _mediaSessionId,
                sampledAtMs = nowMillis(),
                kind = kind,
                trackId = trackId,
            ),
        )
    }

    protected fun emitSeekCompleted(position: Duration = _currentTime) {
        _isSeeking = false
        emitEvent(
            PlaybackEvent.SeekCompleted(
                mediaSessionId = _mediaSessionId,
                sampledAtMs = nowMillis(),
                position = position,
            ),
        )
    }

    protected fun emitPlaybackEnded(looping: Boolean = _loop) {
        if (looping) {
            onRestart?.invoke()
            emitEvent(
                PlaybackEvent.PlaybackRestarted(
                    mediaSessionId = _mediaSessionId,
                    sampledAtMs = nowMillis(),
                ),
            )
        } else {
            _isPlaying = false
            onPlaybackEnded?.invoke()
            emitEvent(
                PlaybackEvent.PlaybackEnded(
                    mediaSessionId = _mediaSessionId,
                    sampledAtMs = nowMillis(),
                ),
            )
        }
    }

    protected fun publishError(playerError: VideoPlayerError) {
        Snapshot.withMutableSnapshot {
            _error = playerError
            _isLoading = false
            _isPlaying = false
        }
        emitEvent(
            PlaybackEvent.Error(
                mediaSessionId = _mediaSessionId,
                sampledAtMs = nowMillis(),
                error = playerError,
            ),
        )
    }

    protected fun clearMetadata() {
        metadata.title = null
        metadata.duration = null
        metadata.width = null
        metadata.height = null
        metadata.bitrate = null
        metadata.frameRate = null
        metadata.mimeType = null
        metadata.audioChannels = null
        metadata.audioSampleRate = null
    }

    protected fun updateAspectRatio(
        width: Int?,
        height: Int?,
    ) {
        if (width != null && height != null && width > 0 && height > 0) {
            _aspectRatio = width.toFloat() / height.toFloat()
        }
    }

    protected fun emitEvent(event: PlaybackEvent) {
        events.tryEmit(event)
    }

    private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    private fun Duration.asClockText(): String {
        val totalSeconds = inWholeSeconds.coerceAtLeast(0L)
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
        } else {
            "${minutes.twoDigits()}:${seconds.twoDigits()}"
        }
    }

    private fun Long.twoDigits(): String = toString().padStart(2, '0')

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64
        const val DEFAULT_ASPECT_RATIO = 16f / 9f
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3_600L
    }
}
