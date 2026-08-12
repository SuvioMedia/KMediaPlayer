package io.github.kdroidfilter.composemediaplayer.libvlc

import androidx.compose.ui.layout.ContentScale
import io.github.kdroidfilter.composemediaplayer.LibVlcAndroidDecodeMode
import io.github.shusek.kmediavlc.runtime.android.VlcAndroidPlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidLibVlcPlaybackStateTest {
    @Test
    fun translatesNativePlaybackStateWithoutInventingBufferingIntent() {
        assertEquals(true, VlcAndroidPlaybackState.PLAYING.playingSnapshot())
        assertEquals(false, VlcAndroidPlaybackState.PAUSED.playingSnapshot())
        assertEquals(false, VlcAndroidPlaybackState.ENDED.playingSnapshot())
        assertEquals(null, VlcAndroidPlaybackState.OPENING.playingSnapshot())
        assertEquals(null, VlcAndroidPlaybackState.BUFFERING.playingSnapshot())
    }

    @Test
    fun exposesOnlyTheVerifiedDirectSurfaceScale() {
        assertTrue(ContentScale.Fit.isSupportedAndroidLibVlcContentScale())
        assertFalse(ContentScale.Crop.isSupportedAndroidLibVlcContentScale())
        assertFalse(ContentScale.FillBounds.isSupportedAndroidLibVlcContentScale())
    }

    @Test
    fun requestsAnHdrWindowOnlyForTheDirectMediaCodecRoute() {
        assertTrue(LibVlcAndroidDecodeMode.AUTOMATIC.requestsAndroidLibVlcHdrWindow())
        assertFalse(LibVlcAndroidDecodeMode.SOFTWARE_ONLY.requestsAndroidLibVlcHdrWindow())
    }

    @Test
    fun completesASeekOnlyAfterItsPendingNativeRequestWasApplied() {
        assertFalse(
            shouldCompleteAndroidLibVlcSeek(
                isSeeking = true,
                state = VlcAndroidPlaybackState.PLAYING,
                pendingSeekRequested = true,
                pendingSeekApplied = false,
            ),
        )
        assertTrue(
            shouldCompleteAndroidLibVlcSeek(
                isSeeking = true,
                state = VlcAndroidPlaybackState.PLAYING,
                pendingSeekRequested = true,
                pendingSeekApplied = true,
            ),
        )
        assertFalse(
            shouldCompleteAndroidLibVlcSeek(
                isSeeking = true,
                state = VlcAndroidPlaybackState.BUFFERING,
                pendingSeekRequested = false,
                pendingSeekApplied = false,
            ),
        )
    }
}
