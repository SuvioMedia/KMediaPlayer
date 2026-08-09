package io.github.kdroidfilter.composemediaplayer.libvlc

import io.github.shusek.kmediavlc.runtime.desktop.VlcPlaybackState
import io.github.shusek.kmediavlc.runtime.desktop.VlcSourceDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibVlcPlaybackStateTest {
    @Test
    fun transientStatesPreservePlaybackIntent() {
        assertNull(VlcPlaybackState.OPENING.playingSnapshot())
        assertNull(VlcPlaybackState.BUFFERING.playingSnapshot())
    }

    @Test
    fun stableStatesUpdatePlaybackIntent() {
        assertTrue(VlcPlaybackState.PLAYING.playingSnapshot() == true)
        listOf(
            VlcPlaybackState.IDLE,
            VlcPlaybackState.PAUSED,
            VlcPlaybackState.STOPPED,
            VlcPlaybackState.ENDED,
            VlcPlaybackState.ERROR,
        ).forEach { state -> assertFalse(state.playingSnapshot() ?: true, state.name) }
        assertEquals(false, VlcPlaybackState.PAUSED.playingSnapshot())
    }

    @Test
    fun nativeSourceDynamicRangeMapsWithoutUsingHostHdrCapability() {
        assertEquals(VideoDynamicRange.SDR, VlcSourceDynamicRange.SDR.toVideoColorInfo().dynamicRange)
        assertEquals(VideoColorTransfer.PQ, VlcSourceDynamicRange.HDR10.toVideoColorInfo().transfer)
        assertEquals(VideoDynamicRange.HLG, VlcSourceDynamicRange.HLG.toVideoColorInfo().dynamicRange)
        assertEquals(VideoDynamicRange.UNKNOWN, VlcSourceDynamicRange.UNKNOWN.toVideoColorInfo().dynamicRange)
    }
}
