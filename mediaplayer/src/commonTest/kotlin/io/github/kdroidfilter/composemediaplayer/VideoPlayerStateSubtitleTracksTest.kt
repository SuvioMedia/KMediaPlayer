package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoPlayerStateSubtitleTracksTest {
    @Test
    fun previewableSubtitleTrackApiMutatesDefaultTrackList() {
        val state = PreviewableVideoPlayerState()
        val track = externalSubtitleTrack(id = "default", label = "Default")

        state.addSubtitleTrack(track)

        assertEquals(listOf(track), state.availableSubtitleTracks)
        assertEquals(TrackSelectionResult.Selected(track.id), state.selectSubtitleTrack(track.id))
        assertTrue(state.subtitlesEnabled)
        assertEquals(track, state.currentSubtitleTrack)

        state.removeSubtitleTrack(track.id)

        assertTrue(state.availableSubtitleTracks.isEmpty())
        assertFalse(state.subtitlesEnabled)
        assertEquals(null, state.currentSubtitleTrack)
    }

    @Test
    fun previewableAudioTrackSelectionReturnsNotFoundForMissingTrack() {
        val state = PreviewableVideoPlayerState()
        val track = AudioTrack(id = "missing-audio", label = "Missing audio")

        val result = state.selectAudioTrack(track)

        assertEquals(TrackSelectionResult.NotFound(track.id), result)
        assertEquals(null, state.currentAudioTrack)
    }

    @Test
    fun previewableEmbeddedSubtitleSelectionReturnsNotFoundForMissingTrack() {
        val state = PreviewableVideoPlayerState()
        val track =
            SubtitleTrack(
                id = "missing-embedded",
                label = "Missing embedded",
                language = "en",
                src = "",
                isEmbedded = true,
            )

        val result = state.selectSubtitleTrack(track)

        assertEquals(TrackSelectionResult.NotFound(track.id), result)
        assertFalse(state.subtitlesEnabled)
        assertEquals(null, state.currentSubtitleTrack)
    }

    @Test
    fun replaceExternalSubtitleTracksPreservesEmbeddedTracks() {
        val embeddedTrack =
            SubtitleTrack(
                id = "embedded-en",
                label = "Embedded English",
                language = "en",
                src = "",
                isEmbedded = true,
            )
        val oldExternalTrack = externalSubtitleTrack(id = "old", label = "Old")
        val newExternalTrack = externalSubtitleTrack(id = "new", label = "New", isEmbedded = true)
        val tracks = mutableListOf(embeddedTrack, oldExternalTrack)
        val state =
            PreviewableVideoPlayerState(
                availableSubtitleTracks = tracks,
                currentSubtitleTrack = oldExternalTrack,
                subtitlesEnabled = true,
            )

        state.replaceExternalSubtitleTracks(listOf(newExternalTrack))

        assertEquals(listOf(embeddedTrack, newExternalTrack.copy(isEmbedded = false)), tracks)
        assertFalse(state.subtitlesEnabled)
        assertEquals(null, state.currentSubtitleTrack)
    }

    @Test
    fun replaceExternalSubtitleTracksRestoresSelectedExternalTrackById() {
        val selectedTrack = externalSubtitleTrack(id = "selected", label = "Selected")
        val replacementTrack = selectedTrack.copy(label = "Selected replacement")
        val tracks = mutableListOf(selectedTrack)
        val state =
            PreviewableVideoPlayerState(
                availableSubtitleTracks = tracks,
                currentSubtitleTrack = selectedTrack,
                subtitlesEnabled = true,
            )

        state.replaceExternalSubtitleTracks(listOf(replacementTrack))

        assertEquals(listOf(replacementTrack), tracks)
        assertTrue(state.subtitlesEnabled)
        assertEquals(replacementTrack, state.currentSubtitleTrack)
    }

    private fun externalSubtitleTrack(
        id: String,
        label: String,
        isEmbedded: Boolean = false,
    ): SubtitleTrack =
        SubtitleTrack(
            id = id,
            label = label,
            language = "en",
            src = "$id.vtt",
            isEmbedded = isEmbedded,
        )
}
