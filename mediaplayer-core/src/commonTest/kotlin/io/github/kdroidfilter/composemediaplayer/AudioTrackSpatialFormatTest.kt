package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioTrackSpatialFormatTest {
    @Test
    fun eac3JocIsRecognizedAsDolbyAtmos() {
        val track = AudioTrack(id = "atmos", label = "Atmos", mimeType = "audio/eac3-joc", channels = 6)

        assertEquals(AudioSpatialFormat.DOLBY_ATMOS, track.spatialAudioFormat)
        assertTrue(track.spatialAudioFormat.mayCarryObjectBasedMetadata)
    }

    @Test
    fun plainEac3AndTrueHdRemainPossibleAtmos() {
        assertEquals(
            AudioSpatialFormat.POSSIBLE_DOLBY_ATMOS,
            AudioTrack(id = "eac3", label = "E-AC-3", codec = "ec-3").spatialAudioFormat,
        )
        assertEquals(
            AudioSpatialFormat.POSSIBLE_DOLBY_ATMOS,
            AudioTrack(id = "truehd", label = "TrueHD", codec = "truehd").spatialAudioFormat,
        )
    }

    @Test
    fun channelBasedSurroundDoesNotPretendToContainObjects() {
        val format = AudioTrack(id = "surround", label = "5.1", codec = "ac3", channels = 6).spatialAudioFormat

        assertEquals(AudioSpatialFormat.MULTICHANNEL, format)
        assertFalse(format.mayCarryObjectBasedMetadata)
    }
}
