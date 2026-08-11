@file:OptIn(ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer

import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AbstractBackendVideoPlayerStateTest {
    @Test
    fun commonCommandsNormalizeValuesBeforeCallingBackend() {
        val state = FakeBackendVideoPlayerState()

        state.volume = 2f
        state.playbackSpeed = 20f
        state.loop = true
        state.subtitleOffset = 3.seconds

        assertEquals(1f, state.lastVolume)
        assertEquals(2f, state.lastSpeed)
        assertTrue(state.lastLoop)
        assertEquals(3.seconds, state.lastSubtitleOffset)
    }

    @Test
    fun sourceAndSeekStateAreManagedByCommonController() {
        val state = FakeBackendVideoPlayerState()

        state.openUri("file:///movie.mkv", InitialPlayerState.PAUSE)
        state.updateSnapshot(position = 2.seconds, duration = 10.seconds)
        state.seekTo(20.seconds)

        assertTrue(state.hasMedia)
        assertFalse(state.isPlaying)
        assertEquals(10.seconds, state.lastSeek)

        state.releaseSource()

        assertFalse(state.hasMedia)
        assertEquals(Duration.ZERO, state.currentTime)
        assertEquals(Duration.ZERO, state.duration)
    }

    @Test
    fun trackBookkeepingIsSharedAcrossBackendAdapters() {
        val state = FakeBackendVideoPlayerState()
        val audio = AudioTrack(id = "backend:audio:1", label = "Audio")
        val embedded =
            SubtitleTrack(
                id = "backend:subtitle:2",
                label = "Embedded",
                language = "en",
                src = "backend://subtitle/2",
                isEmbedded = true,
            )
        val external =
            SubtitleTrack(
                id = "external",
                label = "ASS",
                language = "pl",
                src = "/tmp/subtitles.ass",
            )

        state.discover(audio = listOf(audio), subtitles = listOf(embedded))
        state.addSubtitleTrack(external)

        assertEquals(TrackSelectionResult.Selected(audio.id), state.selectAudioTrack(audio))
        assertEquals(TrackSelectionResult.Selected(external.id), state.selectSubtitleTrack(external))
        assertEquals(external, state.currentSubtitleTrack)
        assertTrue(state.subtitlesEnabled)

        state.clearExternalSubtitleTracks()

        assertEquals(listOf(embedded), state.availableSubtitleTracks.toList())
        assertFalse(state.subtitlesEnabled)
        assertEquals(listOf(external), state.removedExternalSubtitles)
    }
}

private class FakeBackendVideoPlayerState : AbstractBackendVideoPlayerState() {
    override val renderingInfo = VideoRenderingInfo(backend = "fake-backend")
    override val capabilities = PlayerCapabilities(supportsMkv = true)
    override val preciseCurrentTime: Duration
        get() = currentTime

    override var backendDisposed = false
    var lastVolume = 0f
    var lastLoop = false
    var lastSpeed = 0f
    var lastSubtitleOffset = Duration.ZERO
    var lastSeek = Duration.ZERO
    val removedExternalSubtitles = mutableListOf<SubtitleTrack>()

    override fun setBackendVolume(value: Float) {
        lastVolume = value
    }

    override fun setBackendLoop(value: Boolean) {
        lastLoop = value
    }

    override fun setBackendPlaybackSpeed(value: Float) {
        lastSpeed = value
    }

    override fun setBackendSubtitleOffset(value: Duration) {
        lastSubtitleOffset = value
    }

    override fun playBackend() = Unit

    override fun pauseBackend() = Unit

    override fun seekBackend(time: Duration) {
        lastSeek = time
    }

    override fun selectBackendAudioTrack(track: AudioTrack?): TrackSelectionResult =
        track?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Auto

    override fun selectBackendSubtitleTrack(track: SubtitleTrack): TrackSelectionResult =
        TrackSelectionResult.Selected(track.id)

    override fun disableBackendSubtitles(): TrackSelectionResult = TrackSelectionResult.Disabled

    override fun validateExternalSubtitle(track: SubtitleTrack) = Unit

    override fun removeBackendExternalSubtitle(track: SubtitleTrack) {
        removedExternalSubtitles += track
    }

    override fun openUri(
        uri: String,
        initializePlayerState: InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        beginSourcePreparation(uri, initializePlayerState)
        sourceLoaded()
    }

    override fun openFile(
        file: PlatformFile,
        initializePlayerState: InitialPlayerState,
    ) = error("Not used by the common controller test.")

    override fun releaseSource() {
        resetSourceState()
    }

    override fun dispose() {
        backendDisposed = true
    }

    fun updateSnapshot(
        position: Duration,
        duration: Duration,
    ) {
        updatePlaybackPosition(
            position = position,
            mediaDuration = duration,
            playing = false,
            loading = false,
            seeking = false,
        )
    }

    fun discover(
        audio: List<AudioTrack>,
        subtitles: List<SubtitleTrack>,
    ) {
        replaceDiscoveredTracks(
            discoveredAudio = audio,
            discoveredSubtitles = subtitles,
            selectedAudio = null,
            selectedSubtitle = null,
        )
    }
}
