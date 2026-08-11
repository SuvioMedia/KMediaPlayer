package io.github.kdroidfilter.composemediaplayer.libvlc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosLibVlcPlaybackStateTest {
    @Test
    fun mapsOpeningAndBufferingToUnknownPlayingIntent() {
        assertEquals(true, IosLibVlcPlaybackState.PLAYING.playingSnapshot())
        assertEquals(false, IosLibVlcPlaybackState.PAUSED.playingSnapshot())
        assertEquals(null, IosLibVlcPlaybackState.OPENING.playingSnapshot())
        assertEquals(null, IosLibVlcPlaybackState.BUFFERING.playingSnapshot())
    }

    @Test
    fun completesSeekOnlyOnALaterSnapshotNearTheTarget() {
        assertFalse(
            shouldCompleteIosLibVlcSeek(
                isSeeking = true,
                state = IosLibVlcPlaybackState.PLAYING,
                positionMicroseconds = 5_000_000,
                targetMicroseconds = 5_000_000,
                seekAppliedThisSnapshot = true,
            ),
        )
        assertTrue(
            shouldCompleteIosLibVlcSeek(
                isSeeking = true,
                state = IosLibVlcPlaybackState.PLAYING,
                positionMicroseconds = 5_100_000,
                targetMicroseconds = 5_000_000,
                seekAppliedThisSnapshot = false,
            ),
        )
        assertFalse(
            shouldCompleteIosLibVlcSeek(
                isSeeking = true,
                state = IosLibVlcPlaybackState.BUFFERING,
                positionMicroseconds = 5_000_000,
                targetMicroseconds = 5_000_000,
                seekAppliedThisSnapshot = false,
            ),
        )
    }

    @Test
    fun rejectsNulBeforePassingRequestHeadersToC() {
        assertFailsWith<IllegalArgumentException> {
            validatedIosLibVlcRequestHeaders(mapOf("Cookie" to "session=before\u0000after"))
        }
        assertEquals(
            mapOf("User-Agent" to "KMediaPlayer"),
            validatedIosLibVlcRequestHeaders(mapOf("User-Agent" to " KMediaPlayer ")),
        )
    }
}
