package io.github.kdroidfilter.composemediaplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MagicNumber")
class EventingVideoPlayerStateTest {
    @Test
    fun sourceLifecycleEventsUseStableSessionIds() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/video.mp4")
            state.releaseSource()
            runCurrent()

            assertEquals(2L, state.mediaSessionId)

            val preparing = assertIs<PlaybackEvent.SourcePreparing>(events[0])
            assertEquals(1L, preparing.mediaSessionId)
            assertEquals("https://example.test/video.mp4", preparing.uri)

            val released = assertIs<PlaybackEvent.SourceReleased>(events[1])
            assertEquals(1L, released.mediaSessionId)

            state.dispose()
        }

    @Test
    fun releaseSourceEmitsReleasedForSourceStillPreparing() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    markMediaOnOpen = false
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/slow.mp4")
            state.releaseSource()
            runCurrent()

            assertEquals(2L, state.mediaSessionId)

            val preparing = assertIs<PlaybackEvent.SourcePreparing>(events[0])
            assertEquals(1L, preparing.mediaSessionId)
            assertEquals("https://example.test/slow.mp4", preparing.uri)

            val released = assertIs<PlaybackEvent.SourceReleased>(events[1])
            assertEquals(1L, released.mediaSessionId)
            assertEquals(2, events.size)

            state.dispose()
        }

    @Test
    fun stopKeepsTheSourceSessionUntilReleaseSource() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/video.mp4")
            state.stop()
            runCurrent()

            assertEquals(1L, state.mediaSessionId)
            assertEquals(1, events.size)
            assertIs<PlaybackEvent.SourcePreparing>(events.single())

            state.releaseSource()
            runCurrent()
            assertEquals(2L, state.mediaSessionId)
            assertIs<PlaybackEvent.SourceReleased>(events.last())
            state.dispose()
        }

    @Test
    fun disposeEmitsReleasedForActiveSourceSession() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/video.mp4")
            state.dispose()
            runCurrent()

            val preparing = assertIs<PlaybackEvent.SourcePreparing>(events[0])
            assertEquals(1L, preparing.mediaSessionId)

            val released = assertIs<PlaybackEvent.SourceReleased>(events[1])
            assertEquals(1L, released.mediaSessionId)
            assertEquals(2, events.size)
        }

    @Test
    fun disposeIsIdempotent() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/video.mp4")
            state.dispose()
            state.dispose()
            runCurrent()

            assertEquals(1, delegate.disposeCalls)
            assertEquals(
                listOf(
                    PlaybackEvent.SourcePreparing::class,
                    PlaybackEvent.SourceReleased::class,
                ),
                events.map { it::class },
            )
            assertEquals(2, events.size)
        }

    @Test
    fun seekEventsUseClampedTargetAndDelegatePosition() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    duration = 10.seconds
                    preciseCurrentTime = 2.seconds
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/video.mp4")
            runCurrent()
            events.clear()

            state.seekTo(30.seconds)
            runCurrent()

            val started = assertIs<PlaybackEvent.SeekStarted>(events[0])
            assertEquals(10.seconds, started.target)

            val completed = assertIs<PlaybackEvent.SeekCompleted>(events[1])
            assertEquals(10.seconds, completed.position)
            assertEquals(10.seconds, delegate.lastSeekTarget)

            state.dispose()
        }

    @Test
    fun seekWithoutActiveSourceDoesNotEmitEventsOrMutateDelegate() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    duration = 10.seconds
                    preciseCurrentTime = 2.seconds
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.seekTo(5.seconds)
            runCurrent()

            assertEquals(emptyList(), events)
            assertEquals(null, delegate.lastSeekTarget)
            assertEquals(2.seconds, delegate.preciseCurrentTime)

            state.dispose()
        }

    @Test
    fun restartEmitsPlaybackRestartedForActiveSource() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    duration = 10.seconds
                    preciseCurrentTime = 4.seconds
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/video.mp4")
            runCurrent()
            events.clear()

            state.restart()
            runCurrent()

            assertEquals(Duration.ZERO, delegate.lastSeekTarget)
            assertEquals(true, delegate.isPlaying)
            val restarted = assertIs<PlaybackEvent.PlaybackRestarted>(events.single())
            assertEquals(1L, restarted.mediaSessionId)

            state.dispose()
        }

    @Test
    fun trackSelectionEmitsTrackChangedEvent() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)
            val audioTrack = AudioTrack(id = "audio-en", label = "English", language = "en")

            val result = state.selectAudioTrack(audioTrack)
            runCurrent()

            assertEquals(TrackSelectionResult.Selected("audio-en"), result)
            assertEquals(audioTrack, delegate.currentAudioTrack)
            val changed = assertIs<PlaybackEvent.TrackChanged>(events.single())
            assertEquals(TrackKind.AUDIO, changed.kind)
            assertEquals("audio-en", changed.trackId)

            state.dispose()
        }

    @Test
    fun audioTrackIdSelectionEmitsTrackChangedEvent() =
        runTest {
            val audioTrack = AudioTrack(id = "audio-en", label = "English", language = "en")
            val delegate =
                FakeVideoPlayerState().apply {
                    availableAudioTracks = listOf(audioTrack)
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            val result = state.selectAudioTrack("audio-en")
            runCurrent()

            assertEquals(TrackSelectionResult.Selected("audio-en"), result)
            assertEquals(audioTrack, delegate.currentAudioTrack)
            val changed = assertIs<PlaybackEvent.TrackChanged>(events.single())
            assertEquals(TrackKind.AUDIO, changed.kind)
            assertEquals("audio-en", changed.trackId)

            state.dispose()
        }

    @Test
    fun rejectedTrackSelectionDoesNotEmitTrackChangedEvent() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    audioTrackSelectionResult = TrackSelectionResult.NotFound("missing")
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            val result = state.selectAudioTrack(AudioTrack(id = "missing", label = "Missing"))
            runCurrent()

            assertEquals(TrackSelectionResult.NotFound("missing"), result)
            assertEquals(emptyList(), events)

            state.dispose()
        }

    @Test
    fun acceptedAsyncTrackSelectionIsEmittedOnlyAfterConfirmation() =
        runTest {
            val audioTrack = AudioTrack(id = "audio-en", label = "English")
            val delegate =
                FakeVideoPlayerState().apply {
                    audioTrackSelectionResult = TrackSelectionResult.Selected(audioTrack.id)
                }
            val state = EventingVideoPlayerState(delegate, StandardTestDispatcher(testScheduler))
            val events = collectEvents(state)

            val result = state.selectAudioTrack(audioTrack)
            runCurrent()

            assertEquals(TrackSelectionResult.Selected("audio-en"), result)
            assertEquals(emptyList(), events)

            delegate.currentAudioTrack = audioTrack
            testScheduler.advanceTimeBy(20)
            runCurrent()

            val changed = assertIs<PlaybackEvent.TrackChanged>(events.single())
            assertEquals(TrackKind.AUDIO, changed.kind)
            assertEquals(audioTrack.id, changed.trackId)

            state.dispose()
        }

    @Test
    fun externalAudioTrackOperationsAreForwardedToDelegate() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val first = externalAudioTrack("narration-pl")
            val second = externalAudioTrack("narration-en")

            state.addExternalAudioTrack(first)
            assertEquals(listOf(first), state.externalAudioTracks)

            state.replaceExternalAudioTracks(listOf(first, second))
            assertEquals(listOf(first, second), delegate.externalAudioTracks)

            state.removeExternalAudioTrack(first.id)
            assertEquals(listOf(second), delegate.externalAudioTracks)

            state.clearExternalAudioTracks()
            assertEquals(emptyList(), delegate.externalAudioTracks)

            state.dispose()
        }

    @Test
    fun unsupportedHlsQualitySelectionDoesNotEmitTrackChangedEvent() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            val result = state.selectHlsQuality("720p")
            runCurrent()

            assertEquals(HlsQualitySelectionResult.NotSupported, result)
            assertEquals(emptyList(), events)

            state.dispose()
        }

    @Test
    fun appliedHlsQualitySelectionEmitsTrackChangedEvent() =
        runTest {
            val quality = HlsQualityVariant(id = "720p", label = "720p", height = 720)
            val delegate =
                FakeVideoPlayerState().apply {
                    hlsQualitySelectionResult = HlsQualitySelectionResult.Selected(quality)
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            val result = state.selectHlsQuality("720p")
            runCurrent()

            assertEquals(HlsQualitySelectionResult.Selected(quality), result)
            val changed = assertIs<PlaybackEvent.TrackChanged>(events.single())
            assertEquals(TrackKind.HLS_QUALITY, changed.kind)
            assertEquals("720p", changed.trackId)

            state.dispose()
        }

    @Test
    fun primaryAdaptiveQualityApiWaitsForTheConfirmedDelegateState() =
        runTest {
            val quality = HlsQualityVariant(id = "1080p", label = "1080p", height = 1080)
            val delegate =
                FakeVideoPlayerState().apply {
                    hlsQualitySelectionResult = HlsQualitySelectionResult.Selected(quality)
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            val result = state.selectAdaptiveQuality("1080p")
            runCurrent()

            assertEquals(
                AdaptiveQualitySelectionResult.Selected(
                    AdaptiveQualityVariant(id = "1080p", label = "1080p", height = 1080),
                ),
                result,
            )
            assertEquals(AdaptiveQualityMode.MANUAL, state.adaptiveQualityMode)
            assertEquals("1080p", state.currentAdaptiveQuality?.id)
            val changed = assertIs<PlaybackEvent.TrackChanged>(events.single())
            assertEquals(TrackKind.HLS_QUALITY, changed.kind)
            assertEquals("1080p", changed.trackId)

            state.dispose()
        }

    @Test
    fun autoHlsQualitySelectionEmitsTrackChangedEvent() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    hlsQualitySelectionResult = HlsQualitySelectionResult.Auto
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            val result = state.selectAutoHlsQuality()
            runCurrent()

            assertEquals(HlsQualitySelectionResult.Auto, result)
            val changed = assertIs<PlaybackEvent.TrackChanged>(events.single())
            assertEquals(TrackKind.HLS_QUALITY, changed.kind)
            assertEquals(null, changed.trackId)

            state.dispose()
        }

    @Test
    fun repeatedErrorIsEmittedAgainForNewSourceSession() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    error = VideoPlayerError.SourceError("Cannot open source")
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/one.mp4")
            state.openUri("https://example.test/two.mp4")
            runCurrent()

            val errors = events.filterIsInstance<PlaybackEvent.Error>()
            assertEquals(2, errors.size)
            assertEquals(listOf(1L, 2L), errors.map { it.mediaSessionId })

            state.dispose()
        }

    @Test
    fun repeatedErrorIsEmittedAgainAfterBackendRecovery() =
        runTest {
            val repeatedError = VideoPlayerError.SourceError("Transient source failure")
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/video.mp4")
            delegate.error = repeatedError
            state.stop()
            delegate.error = null
            state.stop()
            delegate.error = repeatedError
            state.stop()
            runCurrent()

            val errors = events.filterIsInstance<PlaybackEvent.Error>()
            assertEquals(2, errors.size)
            assertEquals(listOf(repeatedError, repeatedError), errors.map { it.error })
            assertEquals(listOf(1L, 1L), errors.map { it.mediaSessionId })

            state.dispose()
        }

    @Test
    fun openAssetSourcePreparingUsesNormalizedAssetUri() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openAsset(" /videos/intro.mp4 ")
            runCurrent()

            val preparing = assertIs<PlaybackEvent.SourcePreparing>(events.first())
            assertEquals("asset:///videos/intro.mp4", preparing.uri)
            assertEquals("videos/intro.mp4", delegate.lastAssetName)

            state.dispose()
        }

    @Test
    fun synchronousSourceOpenFailureEmitsErrorForPreparingSession() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    openUriException = UnsupportedOperationException("openAsset is not supported on this platform")
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            assertFailsWith<UnsupportedOperationException> {
                state.openUri("asset:///intro.mp4")
            }
            runCurrent()

            assertEquals(1L, state.mediaSessionId)
            val preparing = assertIs<PlaybackEvent.SourcePreparing>(events[0])
            assertEquals(1L, preparing.mediaSessionId)
            assertEquals("asset:///intro.mp4", preparing.uri)

            val error = assertIs<PlaybackEvent.Error>(events[1])
            assertEquals(1L, error.mediaSessionId)
            assertEquals(
                VideoPlayerError.SourceError("openAsset is not supported on this platform"),
                error.error,
            )
            assertEquals(2, events.size)

            state.dispose()
        }

    @Test
    fun unexpectedSynchronousSourceOpenFailureEmitsErrorForPreparingSession() =
        runTest {
            val delegate =
                FakeVideoPlayerState().apply {
                    openUriException = NullPointerException("backend failed before async loading")
                }
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            assertFailsWith<NullPointerException> {
                state.openUri("https://example.test/video.mp4")
            }
            runCurrent()

            assertEquals(1L, state.mediaSessionId)
            val preparing = assertIs<PlaybackEvent.SourcePreparing>(events[0])
            assertEquals(1L, preparing.mediaSessionId)
            assertEquals("https://example.test/video.mp4", preparing.uri)

            val error = assertIs<PlaybackEvent.Error>(events[1])
            assertEquals(1L, error.mediaSessionId)
            assertEquals(
                VideoPlayerError.UnknownError("backend failed before async loading"),
                error.error,
            )
            assertEquals(2, events.size)

            state.dispose()
        }

    @Test
    fun endedEventDoesNotRequireUserCallback() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.openUri("https://example.test/video.mp4")
            runCurrent()
            events.clear()

            delegate.triggerPlaybackEnded()
            runCurrent()

            val ended = assertIs<PlaybackEvent.PlaybackEnded>(events.single())
            assertEquals(1L, ended.mediaSessionId)

            state.dispose()
        }

    @Test
    fun endedCallbackIsStillInvokedWhenRegistered() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            collectEvents(state)
            var callbackCalls = 0

            state.openUri("https://example.test/video.mp4")
            state.onPlaybackEnded = { callbackCalls += 1 }
            delegate.triggerPlaybackEnded()
            runCurrent()

            assertEquals(1, callbackCalls)

            state.dispose()
        }

    @Test
    fun disposeDetachesDelegateCallbacksAndIgnoresLateDelegateSignals() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)
            var endedCallbackCalls = 0
            var restartCallbackCalls = 0

            state.openUri("https://example.test/video.mp4")
            state.onPlaybackEnded = { endedCallbackCalls += 1 }
            state.onRestart = { restartCallbackCalls += 1 }
            state.dispose()
            runCurrent()
            events.clear()

            assertNull(delegate.onPlaybackEnded)
            assertNull(delegate.onRestart)

            delegate.triggerPlaybackEnded()
            delegate.triggerRestart()
            runCurrent()

            assertEquals(0, endedCallbackCalls)
            assertEquals(0, restartCallbackCalls)
            assertEquals(emptyList(), events)
        }

    @Test
    fun operationsAfterDisposeFailFastWithoutMutatingDelegateOrEmittingEvents() =
        runTest {
            val delegate = FakeVideoPlayerState()
            val state = EventingVideoPlayerState(delegate)
            val events = collectEvents(state)

            state.dispose()
            runCurrent()
            events.clear()

            assertFailsWith<IllegalStateException> {
                state.openUri("https://example.test/late.mp4")
            }
            assertFailsWith<IllegalStateException> {
                state.play()
            }
            assertFailsWith<IllegalStateException> {
                state.selectAutoHlsQuality()
            }
            assertFailsWith<IllegalStateException> {
                state.volume = 0.25f
            }
            assertFailsWith<IllegalStateException> {
                state.onPlaybackEnded = {}
            }
            runCurrent()

            assertEquals(0, delegate.openUriCalls)
            assertEquals(0, delegate.playCalls)
            assertEquals(0, delegate.hlsQualitySelectionCalls)
            assertEquals(1f, delegate.volume)
            assertNull(state.onPlaybackEnded)
            assertEquals(emptyList(), events)
        }

    private fun TestScope.collectEvents(state: VideoPlayerState): MutableList<PlaybackEvent> {
        val events = mutableListOf<PlaybackEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.playbackEvents.collect { event ->
                events += event
            }
        }
        return events
    }
}

private class FakeVideoPlayerState : VideoPlayerState {
    override var hasMedia: Boolean = false
    override var isPlaying: Boolean = false
    override var isLoading: Boolean = true
    override var volume: Float = 1f
    override var sliderPos: Float = 0f
    override var userDragging: Boolean = false
    override var loop: Boolean = false
    override var playbackSpeed: Float = 1f
    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null
    override val positionText: String = "00:00"
    override val durationText: String = "00:00"
    override val currentTime: Duration get() = preciseCurrentTime
    override var preciseCurrentTime: Duration = Duration.ZERO
    override var duration: Duration = Duration.ZERO
    override var isFullscreen: Boolean = false
    override val aspectRatio: Float = 16f / 9f
    override var error: VideoPlayerError? = null
    override val metadata: VideoMetadata = VideoMetadata()
    override var currentAudioTrack: AudioTrack? = null
    override var availableAudioTracks: List<AudioTrack> = emptyList()
    private val mutableExternalAudioTracks = mutableListOf<ExternalAudioTrack>()
    override val externalAudioTracks: List<ExternalAudioTrack>
        get() = mutableExternalAudioTracks

    @Suppress("OVERRIDE_DEPRECATION")
    override var availableHlsQualities: List<HlsQualityVariant> = emptyList()

    @Suppress("OVERRIDE_DEPRECATION")
    override var currentHlsQuality: HlsQualityVariant? = null

    @Suppress("OVERRIDE_DEPRECATION")
    override var hlsQualityMode: HlsQualityMode = HlsQualityMode.AUTO
    override var subtitlesEnabled: Boolean = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks: List<SubtitleTrack> = emptyList()
    override var subtitleTextStyle: TextStyle = TextStyle.Default
    override var subtitleBackgroundColor: Color = Color.Transparent
    var audioTrackSelectionResult: TrackSelectionResult? = null
    var subtitleTrackSelectionResult: TrackSelectionResult? = null
    var hlsQualitySelectionResult: HlsQualitySelectionResult = HlsQualitySelectionResult.NotSupported
    var lastSeekTarget: Duration? = null
    var openUriException: RuntimeException? = null
    var lastAssetName: String? = null
    var markMediaOnOpen: Boolean = true
    var disposeCalls: Int = 0
    var openUriCalls: Int = 0
    var playCalls: Int = 0
    var hlsQualitySelectionCalls: Int = 0

    override fun play() {
        playCalls += 1
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun stop() {
        hasMedia = false
        isPlaying = false
    }

    override fun seekTo(time: Duration) {
        lastSeekTarget = time
        preciseCurrentTime = time
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun seekTo(value: Float) {
        sliderPos = value
    }

    override fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        openUriCalls += 1
        openUriException?.let { throw it }
        if (markMediaOnOpen) {
            hasMedia = true
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) {
        if (markMediaOnOpen) {
            hasMedia = true
        }
    }

    override fun openAsset(
        fileName: String,
        initializePlayerState: InitialPlayerState,
    ) {
        lastAssetName = fileName
        if (markMediaOnOpen) {
            hasMedia = true
        }
    }

    override fun releaseSource() {
        hasMedia = false
        isPlaying = false
    }

    override fun clearError() {
        error = null
    }

    override fun selectAudioTrack(track: AudioTrack?): TrackSelectionResult {
        audioTrackSelectionResult?.let { return it }
        currentAudioTrack = track
        return track.audioTrackSelectionResult()
    }

    override fun addExternalAudioTrack(track: ExternalAudioTrack) {
        mutableExternalAudioTracks.removeAll { existing -> existing.id == track.id }
        mutableExternalAudioTracks += track
    }

    override fun removeExternalAudioTrack(trackId: String) {
        mutableExternalAudioTracks.removeAll { track -> track.id == trackId }
    }

    override fun clearExternalAudioTracks() {
        mutableExternalAudioTracks.clear()
    }

    override fun replaceExternalAudioTracks(tracks: List<ExternalAudioTrack>) {
        mutableExternalAudioTracks.clear()
        mutableExternalAudioTracks.addAll(tracks)
    }

    override fun selectHlsQuality(variantId: String?): HlsQualitySelectionResult {
        hlsQualitySelectionCalls += 1
        return hlsQualitySelectionResult.also { result ->
            when (result) {
                HlsQualitySelectionResult.Auto -> {
                    hlsQualityMode = HlsQualityMode.AUTO
                    currentHlsQuality = null
                }

                is HlsQualitySelectionResult.Selected -> {
                    hlsQualityMode = HlsQualityMode.MANUAL
                    currentHlsQuality = result.quality
                }

                is HlsQualitySelectionResult.NotFound,
                HlsQualitySelectionResult.NotSupported,
                -> Unit
            }
        }
    }

    override fun selectSubtitleTrack(track: SubtitleTrack?): TrackSelectionResult {
        subtitleTrackSelectionResult?.let { return it }
        currentSubtitleTrack = track
        subtitlesEnabled = track != null
        return track.subtitleTrackSelectionResult()
    }

    override fun addSubtitleTrack(track: SubtitleTrack) = Unit

    override fun removeSubtitleTrack(trackId: String) = Unit

    override fun clearExternalSubtitleTracks() = Unit

    override fun disableSubtitles(): TrackSelectionResult {
        currentSubtitleTrack = null
        subtitlesEnabled = false
        return TrackSelectionResult.Disabled
    }

    override fun dispose() {
        disposeCalls += 1
    }

    fun triggerPlaybackEnded() {
        onPlaybackEnded?.invoke()
    }

    fun triggerRestart() {
        onRestart?.invoke()
    }
}

private fun externalAudioTrack(id: String): ExternalAudioTrack =
    ExternalAudioTrack(
        id = id,
        label = id,
        source = MediaSourceSpec("https://media.invalid/$id.m4a", "audio/mp4"),
    )
