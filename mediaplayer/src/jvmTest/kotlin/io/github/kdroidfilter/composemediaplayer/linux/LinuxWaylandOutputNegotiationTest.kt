package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxWaylandOutputNegotiationTest {
    @Test
    fun `PQ and HLG require a first 10-bit frame with the matching transfer`() {
        val common = WAYLAND_OUTPUT_ATTACHED or WAYLAND_OUTPUT_CAPS_NEGOTIATED or WAYLAND_OUTPUT_FIRST_FRAME

        assertEquals(
            LinuxWaylandNegotiationState.VERIFIED,
            LinuxWaylandOutputNegotiation
                .evaluate(common or WAYLAND_OUTPUT_TEN_BIT or WAYLAND_OUTPUT_PQ, VideoDynamicRange.HDR10)
                .state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.VERIFIED,
            LinuxWaylandOutputNegotiation
                .evaluate(common or WAYLAND_OUTPUT_TEN_BIT or WAYLAND_OUTPUT_HLG, VideoDynamicRange.HLG)
                .state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.FAILED,
            LinuxWaylandOutputNegotiation.evaluate(common or WAYLAND_OUTPUT_PQ, VideoDynamicRange.HDR10).state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.FAILED,
            LinuxWaylandOutputNegotiation
                .evaluate(common or WAYLAND_OUTPUT_TEN_BIT or WAYLAND_OUTPUT_PQ, VideoDynamicRange.HLG)
                .state,
        )
    }

    @Test
    fun `caps without a rendered frame stay pending and native errors fail`() {
        assertEquals(
            LinuxWaylandNegotiationState.PENDING,
            LinuxWaylandOutputNegotiation
                .evaluate(
                    WAYLAND_OUTPUT_ATTACHED or WAYLAND_OUTPUT_CAPS_NEGOTIATED or WAYLAND_OUTPUT_TEN_BIT or
                        WAYLAND_OUTPUT_PQ,
                    VideoDynamicRange.HDR10,
                ).state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.FAILED,
            LinuxWaylandOutputNegotiation.evaluate(WAYLAND_OUTPUT_ERROR, VideoDynamicRange.HDR10).state,
        )
    }

    @Test
    fun `adaptive SDR output accepts an eight bit frame but rejects stale HDR transfer flags`() {
        val rendered = WAYLAND_OUTPUT_ATTACHED or WAYLAND_OUTPUT_CAPS_NEGOTIATED or WAYLAND_OUTPUT_FIRST_FRAME

        assertEquals(
            LinuxWaylandNegotiationState.VERIFIED,
            LinuxWaylandOutputNegotiation.evaluate(rendered, VideoDynamicRange.SDR).state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.FAILED,
            LinuxWaylandOutputNegotiation
                .evaluate(rendered or WAYLAND_OUTPUT_PQ, VideoDynamicRange.SDR)
                .state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.FAILED,
            LinuxWaylandOutputNegotiation.evaluate(rendered, VideoDynamicRange.UNKNOWN).state,
        )
    }

    @Test
    fun `projection verification requires a P010 DMA-BUF input`() {
        val renderedPq =
            WAYLAND_OUTPUT_ATTACHED or
                WAYLAND_OUTPUT_CAPS_NEGOTIATED or
                WAYLAND_OUTPUT_FIRST_FRAME or
                WAYLAND_OUTPUT_TEN_BIT or
                WAYLAND_OUTPUT_PQ

        assertEquals(
            LinuxWaylandNegotiationState.FAILED,
            LinuxWaylandOutputNegotiation
                .evaluate(renderedPq, VideoDynamicRange.HDR10, requireDmaBuf = true)
                .state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.VERIFIED,
            LinuxWaylandOutputNegotiation
                .evaluate(renderedPq or WAYLAND_OUTPUT_DMABUF, VideoDynamicRange.HDR10, requireDmaBuf = true)
                .state,
        )
    }

    @Test
    fun `HDR10 plus is verified only after the per-frame curve was applied`() {
        val renderedPq =
            WAYLAND_OUTPUT_ATTACHED or
                WAYLAND_OUTPUT_CAPS_NEGOTIATED or
                WAYLAND_OUTPUT_FIRST_FRAME or
                WAYLAND_OUTPUT_TEN_BIT or
                WAYLAND_OUTPUT_PQ or
                WAYLAND_OUTPUT_DMABUF

        assertEquals(
            LinuxWaylandNegotiationState.PENDING,
            LinuxWaylandOutputNegotiation
                .evaluate(
                    renderedPq,
                    VideoDynamicRange.HDR10_PLUS,
                    requireDmaBuf = true,
                    requireHdr10PlusApplication = true,
                ).state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.VERIFIED,
            LinuxWaylandOutputNegotiation
                .evaluate(
                    renderedPq or WAYLAND_OUTPUT_HDR10_PLUS_APPLIED,
                    VideoDynamicRange.HDR10_PLUS,
                    requireDmaBuf = true,
                    requireHdr10PlusApplication = true,
                ).state,
        )
        assertEquals(
            LinuxWaylandNegotiationState.FAILED,
            LinuxWaylandOutputNegotiation
                .evaluate(
                    WAYLAND_OUTPUT_HDR10_PLUS_UNAVAILABLE,
                    VideoDynamicRange.HDR10_PLUS,
                    requireHdr10PlusApplication = true,
                ).state,
        )
    }
}
