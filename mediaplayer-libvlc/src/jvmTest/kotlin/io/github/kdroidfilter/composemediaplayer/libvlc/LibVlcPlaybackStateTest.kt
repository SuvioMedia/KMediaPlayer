package io.github.kdroidfilter.composemediaplayer.libvlc

import dev.nucleusframework.window.WindowDynamicRangeMode
import dev.nucleusframework.window.tao.MacTextureViewProducerInfo
import dev.nucleusframework.window.tao.TextureViewHostCapabilities
import dev.nucleusframework.window.tao.TextureViewHostDynamicRange
import dev.nucleusframework.window.tao.TextureViewHostPixelFormat
import dev.nucleusframework.window.tao.TextureViewHostPresentationState
import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoColorTransfer
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.shusek.kmediavlc.runtime.desktop.VlcPlaybackState
import io.github.shusek.kmediavlc.runtime.desktop.VlcSourceDynamicRange
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
    fun pausedOpenWaitsUntilPrerollFinishesBeforeSendingPause() {
        assertEquals(
            VlcPendingTransportAction.WAIT,
            VlcPlaybackState.BUFFERING.pendingTransportAction(playWhenReady = false),
        )
        assertEquals(
            VlcPendingTransportAction.PAUSE,
            VlcPlaybackState.PLAYING.pendingTransportAction(playWhenReady = false),
        )
        assertEquals(
            VlcPendingTransportAction.APPLIED,
            VlcPlaybackState.PAUSED.pendingTransportAction(playWhenReady = false),
        )
    }

    @Test
    fun transportCommandIsNotCompleteUntilNativeStateConfirmsIt() {
        assertEquals(
            VlcPendingTransportAction.PLAY,
            VlcPlaybackState.PAUSED.pendingTransportAction(playWhenReady = true),
        )
        assertEquals(
            VlcPendingTransportAction.WAIT,
            VlcPlaybackState.BUFFERING.pendingTransportAction(playWhenReady = true),
        )
        assertEquals(
            VlcPendingTransportAction.APPLIED,
            VlcPlaybackState.PLAYING.pendingTransportAction(playWhenReady = true),
        )
    }

    @Test
    fun autoplayIsNotRepeatedWhileLibVlcStillReportsPausedOrBuffering() {
        assertEquals(
            VlcPendingTransportAction.WAIT,
            VlcPlaybackState.PAUSED.pendingTransportAction(
                playWhenReady = true,
                playbackCommandIssued = true,
            ),
        )
        assertEquals(
            VlcPendingTransportAction.WAIT,
            VlcPlaybackState.BUFFERING.pendingTransportAction(
                playWhenReady = true,
                playbackCommandIssued = true,
            ),
        )
        assertEquals(
            VlcPendingTransportAction.APPLIED,
            VlcPlaybackState.PLAYING.pendingTransportAction(
                playWhenReady = true,
                playbackCommandIssued = true,
            ),
        )
    }

    @Test
    fun nativeSourceDynamicRangeMapsWithoutUsingHostHdrCapability() {
        assertEquals(VideoDynamicRange.SDR, VlcSourceDynamicRange.SDR.toVideoColorInfo().dynamicRange)
        assertEquals(VideoColorTransfer.PQ, VlcSourceDynamicRange.HDR10.toVideoColorInfo().transfer)
        assertEquals(VideoDynamicRange.HLG, VlcSourceDynamicRange.HLG.toVideoColorInfo().dynamicRange)
        assertEquals(VideoDynamicRange.UNKNOWN, VlcSourceDynamicRange.UNKNOWN.toVideoColorInfo().dynamicRange)
    }

    @Test
    fun cpuPipelineReportsWhyHdrBecameSdr() {
        val hdrSource = VlcSourceDynamicRange.HDR10.toVideoColorInfo()

        assertEquals(
            ColorPipelineFallbackReason.HDR_PROJECTION_UNAVAILABLE,
            cpuPipelineFallbackReason(DynamicRangePolicy.AUTO, hdrSource, projectionRequired = true),
        )
        assertEquals(
            ColorPipelineFallbackReason.HDR_SURFACE_UNAVAILABLE,
            cpuPipelineFallbackReason(DynamicRangePolicy.AUTO, hdrSource, projectionRequired = false),
        )
        assertEquals(
            ColorPipelineFallbackReason.EXPLICIT_SDR_REQUEST,
            cpuPipelineFallbackReason(DynamicRangePolicy.FORCE_SDR, VideoColorInfo(), projectionRequired = true),
        )
        assertEquals(
            ColorPipelineFallbackReason.NONE,
            cpuPipelineFallbackReason(
                DynamicRangePolicy.AUTO,
                VlcSourceDynamicRange.SDR.toVideoColorInfo(),
                projectionRequired = true,
            ),
        )
    }

    @Test
    fun gpuOutputUsesSourceDisplayGeometryOnceKnown() {
        assertEquals(
            LibVlcOutputSize(1_920, 1_080),
            sourceSizedLibVlcOutputSize(3_440, 1_440, 1_920, 1_080),
        )
        assertEquals(
            LibVlcOutputSize(1_920, 1_080),
            sourceSizedLibVlcOutputSize(1_600, 900, 1_920, 1_080),
        )
        assertEquals(
            LibVlcOutputSize(1_920, 1_080),
            sourceSizedLibVlcOutputSize(2_300, 1_000, 1_920, 1_080),
        )
        assertEquals(
            LibVlcOutputSize(1_080, 1_920),
            sourceSizedLibVlcOutputSize(1_920, 1_080, 1_080, 1_920),
        )
    }

    @Test
    fun gpuOutputUsesViewportUntilSourceGeometryIsKnown() {
        assertEquals(
            LibVlcOutputSize(2_300, 1_000),
            sourceSizedLibVlcOutputSize(2_300, 1_000, 0, 0),
        )
    }

    @Test
    fun frameSerialGapsBecomeDroppedFrameTelemetry() {
        assertEquals(0L, skippedLibVlcFrameCount(previousSerial = 0L, currentSerial = 1L))
        assertEquals(3L, skippedLibVlcFrameCount(previousSerial = 0L, currentSerial = 4L))
        assertEquals(2L, skippedLibVlcFrameCount(previousSerial = 7L, currentSerial = 10L))
        assertEquals(0L, skippedLibVlcFrameCount(previousSerial = 10L, currentSerial = 10L))
        assertEquals(0L, skippedLibVlcFrameCount(previousSerial = 10L, currentSerial = 1L))
    }

    @Test
    fun liveLuminanceUpdatesDoNotRebuildLibVlcOutput() {
        val baseline = macHostCapabilities()

        assertTrue(
            baseline.hasSameOutputConfigurationAs(
                baseline.copy(
                    presentationState = TextureViewHostPresentationState.PRESENTED,
                    sdrWhiteLevelNits = 100f,
                    maximumLuminanceNits = 1_600f,
                    headroom = 2.5f,
                    presentedFrameCount = 1L,
                ),
            ),
        )
        assertFalse(baseline.hasSameOutputConfigurationAs(baseline.copy(generation = 8L)))
        assertFalse(
            baseline.hasSameOutputConfigurationAs(
                baseline.copy(producerInfo = MacTextureViewProducerInfo(device = 30L, commandQueue = 20L)),
            ),
        )
        assertFalse(
            baseline.hasSameOutputConfigurationAs(
                baseline.copy(actualDynamicRange = TextureViewHostDynamicRange.SDR),
            ),
        )
    }

    @Test
    fun frameRateEstimatorUsesSerialAndPresentationTimestamps() {
        val estimator = LibVlcFrameRateEstimator()

        assertNull(estimator.observe(serial = 1L, ptsMicroseconds = 0L))
        assertNull(estimator.observe(serial = 5L, ptsMicroseconds = 400_000L))
        assertEquals(24f, estimator.observe(serial = 13L, ptsMicroseconds = 500_000L))

        estimator.reset()
        assertNull(estimator.observe(serial = 100L, ptsMicroseconds = 5_000_000L))
        assertNull(estimator.observe(serial = 105L, ptsMicroseconds = 4_000_000L))
        assertEquals(24f, estimator.observe(serial = 117L, ptsMicroseconds = 4_500_000L))
    }

    private fun macHostCapabilities(): TextureViewHostCapabilities =
        TextureViewHostCapabilities(
            requestedMode = WindowDynamicRangeMode.EXTENDED_IF_AVAILABLE,
            actualDynamicRange = TextureViewHostDynamicRange.HDR,
            presentationState = TextureViewHostPresentationState.PENDING,
            sdrWhiteLevelNits = 80f,
            maximumLuminanceNits = 1_000f,
            headroom = 1.5f,
            generation = 7L,
            presentedFrameCount = 0L,
            outputPixelFormat = TextureViewHostPixelFormat.RGBA16_FLOAT_SCRGB,
            producerInfo = MacTextureViewProducerInfo(device = 10L, commandQueue = 20L),
        )
}
