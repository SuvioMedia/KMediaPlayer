package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Adds synchronized external audio to a backend by running an audio-only transport beside the primary player.
 *
 * The primary player remains the timeline authority. Its embedded audio is muted only after the external transport is
 * ready, and is restored immediately when the external track is removed or fails.
 */
internal class SynchronizedExternalAudioVideoPlayerState(
    internal val primaryState: VideoPlayerState,
    private val engineFactory: () -> ExternalAudioPlaybackEngine,
    private val synchronizationDispatcher: CoroutineDispatcher = Dispatchers.Main,
) : DelegatingVideoPlayerState,
    VideoPlayerState by primaryState {
    private val registeredTracks = mutableStateListOf<ExternalAudioTrack>()
    private var selectedExternalTrack by mutableStateOf<ExternalAudioTrack?>(null)
    private var externalError by mutableStateOf<VideoPlayerError?>(null)
    private var engine: ExternalAudioPlaybackEngine? = null
    private var externalAudioActive = false
    private var disposed = false
    private var requestedVolume = primaryState.volume
    private var synchronizationScope: CoroutineScope? = null
    private var syncJob: Job? = null

    override val delegateState: VideoPlayerState
        get() = primaryState

    override val capabilities: PlayerCapabilities
        get() = primaryState.capabilities.copy(supportsExternalAudioTracks = true)

    override val externalAudioTracks: List<ExternalAudioTrack>
        get() = registeredTracks

    override val availableAudioTracks: List<AudioTrack>
        get() {
            val externalIds = registeredTracks.mapTo(mutableSetOf(), ExternalAudioTrack::id)
            return primaryState.availableAudioTracks.filterNot { track -> track.id in externalIds } +
                registeredTracks.map(ExternalAudioTrack::asAudioTrack)
        }

    override var currentAudioTrack: AudioTrack?
        get() = selectedExternalTrack?.asAudioTrack() ?: primaryState.currentAudioTrack
        set(value) {
            selectAudioTrack(value)
        }

    override var volume: Float
        get() = requestedVolume
        set(value) {
            val normalized = value.coerceIn(0f, 1f)
            requestedVolume = normalized
            if (externalAudioActive) {
                primaryState.volume = 0f
                engine?.setVolume(normalized)
            } else {
                primaryState.volume = normalized
            }
        }

    override var playbackSpeed: Float
        get() = primaryState.playbackSpeed
        set(value) {
            primaryState.playbackSpeed = value
            engine?.setPlaybackSpeed(primaryState.playbackSpeed)
        }

    override var loop: Boolean
        get() = primaryState.loop
        set(value) {
            primaryState.loop = value
            engine?.setLoop(value)
        }

    override val error: VideoPlayerError?
        get() = externalError ?: primaryState.error

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        ensureOpen()
        val external = track?.let { requested -> registeredTracks.firstOrNull { it.id == requested.id } }
        if (track != null && external == null && primaryState.availableAudioTracks.none { it.id == track.id }) {
            return TrackSelectionResult.NotFound(track.id)
        }
        return if (external != null) {
            selectExternalTrack(external)
        } else {
            deactivateExternalAudio()
            externalError = null
            primaryState.selectAudioTrack(track)
        }
    }

    override fun selectAudioTrack(trackId: String?): TrackSelectionResult =
        trackId
            ?.let { id ->
                availableAudioTracks.firstOrNull { it.id == id }?.let(::selectAudioTrack)
                    ?: TrackSelectionResult.NotFound(id)
            }
            ?: selectAudioTrack(null as AudioTrack?)

    override fun addExternalAudioTrack(track: ExternalAudioTrack) {
        ensureOpen()
        val selected = selectedExternalTrack?.id == track.id
        val existingIndex = registeredTracks.indexOfFirst { it.id == track.id }
        if (existingIndex >= 0) {
            if (registeredTracks[existingIndex] == track) return
            registeredTracks[existingIndex] = track
        } else {
            registeredTracks += track
        }
        if (selected) selectExternalTrack(track)
    }

    override fun removeExternalAudioTrack(trackId: String) {
        ensureOpen()
        if (registeredTracks.none { it.id == trackId }) return
        if (selectedExternalTrack?.id == trackId) deactivateExternalAudio()
        registeredTracks.removeAll { it.id == trackId }
        externalError = null
    }

    override fun removeExternalAudioTrack(track: ExternalAudioTrack) {
        removeExternalAudioTrack(track.id)
    }

    override fun clearExternalAudioTracks() {
        ensureOpen()
        deactivateExternalAudio()
        registeredTracks.clear()
        externalError = null
    }

    override fun replaceExternalAudioTracks(tracks: List<ExternalAudioTrack>) {
        ensureOpen()
        require(tracks.distinctBy(ExternalAudioTrack::id).size == tracks.size) {
            "External audio track ids must be unique."
        }
        externalError = null
        val selectedId = selectedExternalTrack?.id
        registeredTracks.clear()
        registeredTracks.addAll(tracks)
        val replacement = selectedId?.let { id -> tracks.firstOrNull { it.id == id } }
        if (replacement == null) deactivateExternalAudio() else selectExternalTrack(replacement)
    }

    override fun play() {
        primaryState.play()
        if (selectedExternalTrack != null) engine?.play()
    }

    override fun pause() {
        primaryState.pause()
        engine?.pause()
    }

    override fun stop() {
        primaryState.stop()
        if (primaryState.hasMedia) {
            engine?.pause()
            engine?.seekTo(Duration.ZERO)
        } else {
            clearExternalAudioForSourceChange()
        }
    }

    override fun restart() {
        primaryState.restart()
        engine?.seekTo(Duration.ZERO)
        if (primaryState.isPlaying) engine?.play()
    }

    override fun seekTo(time: Duration) {
        primaryState.seekTo(time)
        engine?.seekTo(time)
    }

    override fun seekToMs(timeMs: Long) {
        seekTo(timeMs.coerceAtLeast(0L).milliseconds)
    }

    override fun seekBy(delta: Duration) {
        seekTo(primaryState.preciseCurrentTime + delta)
    }

    override fun seekByMs(deltaMs: Long) {
        seekBy(deltaMs.milliseconds)
    }

    override fun seekToProgress(progress: Float) {
        val target = primaryState.duration * progress.coerceIn(0f, 1f).toDouble()
        seekTo(target)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        seekToProgress(value / VideoPlayerState.SLIDER_SCALE)
    }

    override fun seekFinished() {
        seekToProgress(sliderPos / VideoPlayerState.SLIDER_SCALE)
        userDragging = false
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) = replacePrimarySource { primaryState.openUri(uri, initializePlayerState, requestHeaders) }

    override fun openSource(
        source: MediaSourceSpec,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) = replacePrimarySource { primaryState.openSource(source, initializePlayerState, requestHeaders) }

    override fun prepare(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) = replacePrimarySource { primaryState.prepare(uri, initializePlayerState, requestHeaders) }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) = replacePrimarySource { primaryState.openFile(file, initializePlayerState) }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) = replacePrimarySource { primaryState.openAsset(fileName, initializePlayerState) }

    override fun releaseSource() {
        clearExternalAudioForSourceChange()
        primaryState.releaseSource()
    }

    override fun clearError() {
        externalError = null
        primaryState.clearError()
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        syncJob?.cancel()
        engine?.dispose()
        engine = null
        synchronizationScope?.cancel()
        synchronizationScope = null
        primaryState.dispose()
    }

    internal fun synchronizeExternalAudio() {
        val selected = selectedExternalTrack ?: return
        val currentEngine = engine ?: return
        if (!primaryState.hasMedia || primaryState.error != null) {
            deactivateExternalAudio()
            return
        }
        if (currentEngine.hasError) {
            failExternalAudio()
            return
        }
        if (!currentEngine.isReady) return

        val primaryPosition = primaryState.preciseCurrentTime
        val driftMs = abs(currentEngine.currentTime.inWholeMilliseconds - primaryPosition.inWholeMilliseconds)
        if (!externalAudioActive || driftMs > MAXIMUM_DRIFT_MS) currentEngine.seekTo(primaryPosition)
        currentEngine.setPlaybackSpeed(primaryState.playbackSpeed)
        currentEngine.setLoop(primaryState.loop)
        if (primaryState.isPlaying && !currentEngine.isPlaying) {
            currentEngine.play()
            return
        }
        if (!primaryState.isPlaying && currentEngine.isPlaying) currentEngine.pause()
        if (selectedExternalTrack?.id != selected.id) return
        if (!externalAudioActive) {
            externalAudioActive = true
            primaryState.volume = 0f
        }
        currentEngine.setVolume(requestedVolume)
    }

    private fun selectExternalTrack(track: ExternalAudioTrack): TrackSelectionResult =
        try {
            deactivateExternalAudio(clearSelection = false)
            externalError = null
            selectedExternalTrack = track
            val currentEngine = engine ?: engineFactory().also { engine = it }
            currentEngine.prepare(
                track = track,
                position = primaryState.preciseCurrentTime,
                volume = 0f,
                playbackSpeed = primaryState.playbackSpeed,
                loop = primaryState.loop,
            )
            if (primaryState.isPlaying) currentEngine.play()
            startSynchronization()
            synchronizeExternalAudio()
            TrackSelectionResult.Selected(track.id)
        } catch (_: Throwable) {
            failExternalAudio()
            TrackSelectionResult.Failed(EXTERNAL_AUDIO_FAILURE_MESSAGE)
        }

    private fun startSynchronization() {
        if (syncJob?.isActive == true) return
        val activeScope =
            synchronizationScope
                ?: CoroutineScope(SupervisorJob() + synchronizationDispatcher).also {
                    synchronizationScope = it
                }
        syncJob =
            activeScope.launch {
                while (isActive) {
                    delay(SYNC_INTERVAL_MS)
                    runCatching(::synchronizeExternalAudio).onFailure { failExternalAudio() }
                }
            }
    }

    private fun deactivateExternalAudio(clearSelection: Boolean = true) {
        externalAudioActive = false
        primaryState.volume = requestedVolume
        runCatching { engine?.release() }
        if (clearSelection) selectedExternalTrack = null
    }

    private fun failExternalAudio() {
        deactivateExternalAudio()
        externalError = VideoPlayerError.SourceError(EXTERNAL_AUDIO_FAILURE_MESSAGE)
    }

    private inline fun replacePrimarySource(open: () -> Unit) {
        clearExternalAudioForSourceChange()
        open()
    }

    private fun clearExternalAudioForSourceChange() {
        deactivateExternalAudio()
        registeredTracks.clear()
        externalError = null
    }

    private fun ensureOpen() {
        check(!disposed) { "VideoPlayerState has been disposed" }
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 250L
        const val MAXIMUM_DRIFT_MS = 250L
        const val EXTERNAL_AUDIO_FAILURE_MESSAGE = "External audio playback failed."
    }
}

/** Decorates an alternative backend with the platform's synchronized external-audio transport. */
@ExperimentalComposeMediaPlayerBackendApi
fun VideoPlayerState.withSynchronizedExternalAudioPlayback(): VideoPlayerState =
    withSynchronizedExternalAudioEngine(::createPlatformExternalAudioPlaybackEngine)

/** Decorates an alternative backend with a caller-supplied auxiliary platform player. */
@ExperimentalComposeMediaPlayerBackendApi
fun VideoPlayerState.withSynchronizedExternalAudioPlayback(
    auxiliaryPlayerFactory: () -> VideoPlayerState,
): VideoPlayerState =
    withSynchronizedExternalAudioEngine {
        VideoPlayerStateExternalAudioPlaybackEngine(auxiliaryPlayerFactory())
    }

private fun VideoPlayerState.withSynchronizedExternalAudioEngine(
    engineFactory: () -> ExternalAudioPlaybackEngine,
): VideoPlayerState =
    EventingVideoPlayerState(
        SynchronizedExternalAudioVideoPlayerState(
            primaryState = this,
            engineFactory = engineFactory,
        ),
    )
