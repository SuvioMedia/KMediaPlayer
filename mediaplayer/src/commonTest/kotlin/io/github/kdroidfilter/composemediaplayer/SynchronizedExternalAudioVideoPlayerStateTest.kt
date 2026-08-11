package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("MagicNumber")
class SynchronizedExternalAudioVideoPlayerStateTest {
    @Test
    fun overlayAudioKeepsProgrammeAudioAndDucksOnlyInsideConfiguredIntervals() {
        val primary = FakePrimaryVideoPlayerState(initialVolume = 0.8f)
        val engine = FakeExternalAudioPlaybackEngine(ready = true)
        val state = synchronizedState(primary, engine)
        val narration =
            narrationTrack().copy(
                playbackMode = ExternalAudioPlaybackMode.OVERLAY,
                duckingIntervals = listOf(ExternalAudioDuckingInterval(5.seconds, 7.seconds)),
                duckingVolumeMultiplier = 0.5f,
            )

        try {
            state.addExternalAudioTrack(narration)
            state.selectAudioTrack(narration.id)
            state.synchronizeExternalAudio()

            assertEquals(0.4f, primary.volume)
            assertEquals(0.8f, engine.volume)
            assertTrue(engine.isPlaying)

            state.seekTo(7_150.milliseconds)
            assertEquals(0.6f, primary.volume)

            state.seekTo(8.seconds)
            assertEquals(0.8f, primary.volume)
            assertEquals(0.8f, engine.volume)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun automaticPolicyPreservesRecognizedAtmosInsteadOfDuckingIt() {
        val atmos = AudioTrack(id = "atmos", label = "Atmos", mimeType = "audio/eac3-joc", channels = 6)
        val primary = FakePrimaryVideoPlayerState(embeddedTrack = atmos, initialVolume = 0.8f)
        val engine = FakeExternalAudioPlaybackEngine(ready = true)
        val state = synchronizedState(primary, engine)
        val narration =
            narrationTrack().copy(
                playbackMode = ExternalAudioPlaybackMode.OVERLAY,
                duckingIntervals = listOf(ExternalAudioDuckingInterval(5.seconds, 7.seconds)),
                duckingVolumeMultiplier = 0.5f,
            )

        try {
            state.addExternalAudioTrack(narration)
            state.selectAudioTrack(narration.id)
            state.synchronizeExternalAudio()

            assertEquals(0.8f, primary.volume)
            assertEquals(
                ExternalAudioPrimaryAudioHandling.PRESERVED,
                state.externalAudioPlaybackStatus.primaryAudioHandling,
            )
            assertEquals(AudioSpatialFormat.DOLBY_ATMOS, state.externalAudioPlaybackStatus.spatialAudioFormat)
            assertFalse(state.externalAudioPlaybackStatus.encodedPassthroughSuppressed)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun localMixPolicyDucksAtmosWhenNarrationHasPriority() {
        val atmos = AudioTrack(id = "atmos", label = "Atmos", mimeType = "audio/eac3-joc", channels = 6)
        val primary = FakePrimaryVideoPlayerState(embeddedTrack = atmos, initialVolume = 0.8f)
        val engine = FakeExternalAudioPlaybackEngine(ready = true)
        val state = synchronizedState(primary, engine)
        val narration =
            narrationTrack().copy(
                playbackMode = ExternalAudioPlaybackMode.OVERLAY,
                duckingIntervals = listOf(ExternalAudioDuckingInterval(5.seconds, 7.seconds)),
                duckingVolumeMultiplier = 0.5f,
                mixingPolicy = ExternalAudioMixingPolicy.PREFER_LOCAL_MIX,
            )

        try {
            state.addExternalAudioTrack(narration)
            state.selectAudioTrack(narration.id)
            state.synchronizeExternalAudio()

            assertEquals(0.4f, primary.volume)
            assertEquals(
                ExternalAudioPrimaryAudioHandling.DUCKED,
                state.externalAudioPlaybackStatus.primaryAudioHandling,
            )
            assertEquals(atmos, state.externalAudioPlaybackStatus.primaryAudioTrack)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun externalAudioActivatesOnlyAfterTheAuxiliaryTransportIsReady() {
        val embedded = AudioTrack(id = "embedded", label = "Embedded")
        val primary = FakePrimaryVideoPlayerState(embeddedTrack = embedded, initialVolume = 0.7f)
        val engine = FakeExternalAudioPlaybackEngine()
        val state = synchronizedState(primary, engine)
        val narration = narrationTrack()

        try {
            assertTrue(state.capabilities.supportsExternalAudioTracks)
            state.addExternalAudioTrack(narration)

            assertEquals(
                listOf(embedded, narration.asAudioTrack()),
                state.availableAudioTracks,
            )
            assertEquals(
                TrackSelectionResult.Selected(narration.id),
                state.selectAudioTrack(narration.id),
            )
            assertEquals(narration.asAudioTrack(), state.currentAudioTrack)
            assertEquals(0.7f, primary.volume)
            assertEquals(narration, engine.preparedTrack)
            assertEquals(5.seconds, engine.currentTime)

            engine.ready = true
            state.synchronizeExternalAudio()

            assertEquals(0f, primary.volume)
            assertEquals(0.7f, engine.volume)
            assertTrue(engine.isPlaying)

            state.removeExternalAudioTrack(narration.id)

            assertEquals(0.7f, primary.volume)
            assertEquals(embedded, state.currentAudioTrack)
            assertEquals(listOf(embedded), state.availableAudioTracks)
            assertTrue(engine.releaseCalls > 0)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun playbackControlsKeepTheAuxiliaryTransportInSync() {
        val primary = FakePrimaryVideoPlayerState(initialVolume = 0.8f)
        val engine = FakeExternalAudioPlaybackEngine(ready = true)
        val state = synchronizedState(primary, engine)
        val narration = narrationTrack()

        try {
            state.addExternalAudioTrack(narration)
            state.selectAudioTrack(narration.id)
            state.synchronizeExternalAudio()

            state.pause()
            assertFalse(primary.isPlaying)
            assertFalse(engine.isPlaying)

            state.play()
            assertTrue(primary.isPlaying)
            assertTrue(engine.isPlaying)

            state.seekTo(8.seconds)
            assertEquals(8.seconds, primary.preciseCurrentTime)
            assertEquals(8.seconds, engine.currentTime)

            state.playbackSpeed = 1.5f
            state.loop = false
            state.volume = 0.4f

            assertEquals(1.5f, primary.playbackSpeed)
            assertEquals(1.5f, engine.playbackSpeed)
            assertFalse(primary.loop)
            assertFalse(engine.loop)
            assertEquals(0f, primary.volume)
            assertEquals(0.4f, state.volume)
            assertEquals(0.4f, engine.volume)

            engine.currentTime = 6.seconds
            state.synchronizeExternalAudio()
            assertEquals(8.seconds, engine.currentTime)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun auxiliaryFailureRestoresEmbeddedAudioWithoutLeakingTheSource() {
        val embedded = AudioTrack(id = "embedded", label = "Embedded")
        val primary = FakePrimaryVideoPlayerState(embeddedTrack = embedded, initialVolume = 0.6f)
        val engine = FakeExternalAudioPlaybackEngine(ready = true)
        val state = synchronizedState(primary, engine)
        val narration = narrationTrack(uri = "https://media.invalid/signed-audio?token=redacted-test-value")

        try {
            state.addExternalAudioTrack(narration)
            state.selectAudioTrack(narration.id)
            state.synchronizeExternalAudio()
            assertEquals(0f, primary.volume)

            engine.failed = true
            state.synchronizeExternalAudio()

            assertEquals(0.6f, primary.volume)
            assertEquals(embedded, state.currentAudioTrack)
            val error = assertIs<VideoPlayerError.SourceError>(state.error)
            assertEquals("External audio playback failed.", error.message)
            assertFalse(error.toString().contains("token="))

            state.selectAudioTrack(embedded)
            assertNull(state.error)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun replacingThePrimarySourceClearsSessionBoundExternalAudio() {
        val primary = FakePrimaryVideoPlayerState(initialVolume = 0.5f)
        val engine = FakeExternalAudioPlaybackEngine(ready = true)
        val state = synchronizedState(primary, engine)
        val narration = narrationTrack()

        try {
            state.addExternalAudioTrack(narration)
            state.selectAudioTrack(narration.id)
            state.synchronizeExternalAudio()

            state.openUri("https://media.invalid/replacement.mp4")

            assertTrue(state.externalAudioTracks.isEmpty())
            assertEquals(0.5f, primary.volume)
            assertEquals("https://media.invalid/replacement.mp4", primary.openedUri)
            assertTrue(engine.releaseCalls > 0)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun stopClearsExternalAudioWhenThePrimaryBackendReleasesItsSource() {
        val primary = FakePrimaryVideoPlayerState(stopReleasesSource = true)
        val engine = FakeExternalAudioPlaybackEngine(ready = true)
        val state = synchronizedState(primary, engine)

        try {
            state.addExternalAudioTrack(narrationTrack())
            state.selectAudioTrack("narration")

            state.stop()

            assertFalse(primary.hasMedia)
            assertTrue(state.externalAudioTracks.isEmpty())
            assertEquals(1f, primary.volume)
            assertTrue(engine.releaseCalls > 0)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun disposeReleasesBothTransportsExactlyOnce() {
        val primary = FakePrimaryVideoPlayerState()
        val engine = FakeExternalAudioPlaybackEngine()
        val state = synchronizedState(primary, engine)

        state.addExternalAudioTrack(narrationTrack())
        state.selectAudioTrack("narration")
        state.dispose()
        state.dispose()

        assertEquals(1, primary.disposeCalls)
        assertEquals(1, engine.disposeCalls)
    }

    private fun synchronizedState(
        primary: FakePrimaryVideoPlayerState,
        engine: FakeExternalAudioPlaybackEngine,
    ): SynchronizedExternalAudioVideoPlayerState =
        SynchronizedExternalAudioVideoPlayerState(
            primaryState = primary,
            engineFactory = { engine },
            synchronizationDispatcher = Dispatchers.Unconfined,
        )

    private fun narrationTrack(uri: String = "https://media.invalid/narration.m4a"): ExternalAudioTrack =
        ExternalAudioTrack(
            id = "narration",
            label = "Narration",
            source = MediaSourceSpec(uri = uri, mimeType = "audio/mp4"),
            language = "pl",
            channels = 2,
            sampleRate = 48_000,
        )
}

private class FakePrimaryVideoPlayerState(
    embeddedTrack: AudioTrack? = null,
    initialVolume: Float = 1f,
    private val stopReleasesSource: Boolean = false,
    private val preview: PreviewableVideoPlayerState =
        PreviewableVideoPlayerState(
            currentAudioTrack = embeddedTrack,
            availableAudioTracks = mutableListOf<AudioTrack>().apply { embeddedTrack?.let(::add) },
            volume = initialVolume,
            loop = true,
            currentTime = 5.seconds,
            preciseCurrentTime = 5.seconds,
        ),
) : VideoPlayerState by preview {
    private var playing = true
    private var position = 5.seconds
    private var mediaPresent = true

    override val hasMedia: Boolean
        get() = mediaPresent

    override val isPlaying: Boolean
        get() = playing

    override val currentTime: Duration
        get() = position

    override val preciseCurrentTime: Duration
        get() = position

    override var volume: Float = initialVolume
    override var loop: Boolean = true
    override var playbackSpeed: Float = 1f
    var openedUri: String? = null
        private set
    var disposeCalls: Int = 0
        private set

    override fun play() {
        playing = true
    }

    override fun pause() {
        playing = false
    }

    override fun stop() {
        playing = false
        position = Duration.ZERO
        if (stopReleasesSource) mediaPresent = false
    }

    override fun seekTo(time: Duration) {
        position = time.coerceAtLeast(Duration.ZERO)
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        openedUri = uri
        mediaPresent = true
        playing = initializePlayerState == InitialPlayerState.PLAY
        position = Duration.ZERO
    }

    override fun releaseSource() {
        mediaPresent = false
        playing = false
        position = Duration.ZERO
    }

    override fun dispose() {
        disposeCalls += 1
    }
}

private class FakeExternalAudioPlaybackEngine(
    var ready: Boolean = false,
) : ExternalAudioPlaybackEngine {
    override val isReady: Boolean
        get() = ready
    override var isPlaying: Boolean = false
        private set
    override var currentTime: Duration = Duration.ZERO
    override val hasError: Boolean
        get() = failed
    var failed: Boolean = false
    var preparedTrack: ExternalAudioTrack? = null
        private set
    var volume: Float = 1f
        private set
    var playbackSpeed: Float = 1f
        private set
    var loop: Boolean = false
        private set
    var releaseCalls: Int = 0
        private set
    var disposeCalls: Int = 0
        private set

    override fun prepare(
        track: ExternalAudioTrack,
        position: Duration,
        volume: Float,
        playbackSpeed: Float,
        loop: Boolean,
    ) {
        preparedTrack = track
        currentTime = position
        this.volume = volume
        this.playbackSpeed = playbackSpeed
        this.loop = loop
        failed = false
    }

    override fun play() {
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun seekTo(time: Duration) {
        currentTime = time
    }

    override fun setVolume(value: Float) {
        volume = value
    }

    override fun setPlaybackSpeed(value: Float) {
        playbackSpeed = value
    }

    override fun setLoop(value: Boolean) {
        loop = value
    }

    override fun release() {
        releaseCalls += 1
        isPlaying = false
    }

    override fun dispose() {
        disposeCalls += 1
        isPlaying = false
    }
}
