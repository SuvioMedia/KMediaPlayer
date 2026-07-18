package io.github.kdroidfilter.composemediaplayer.linux

import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxWaylandColorCapabilitiesTest {
    @Test
    fun `parses compositor inputs and per-output HDR state`() {
        val snapshot = LinuxWaylandColorCapabilitiesParser.parse(WAYLAND_INFO)

        assertTrue(snapshot.hasColorManager)
        assertTrue(snapshot.supportsParametricDescriptions)
        assertTrue(snapshot.supportsBt2020Primaries)
        assertEquals(
            setOf(WaylandOutputTransfer.SDR, WaylandOutputTransfer.PQ, WaylandOutputTransfer.HLG),
            snapshot.supportedTransfers,
        )

        val hdr = snapshot.displayCapabilitiesFor(displayName = "DP-1")
        assertTrue(hdr.isKnown)
        assertTrue(hdr.supports(VideoDynamicRange.HDR10))
        assertFalse(hdr.supports(VideoDynamicRange.HDR10_PLUS))
        assertTrue(hdr.supports(VideoDynamicRange.HLG))
        assertEquals(1000f, hdr.maxLuminanceNits)

        val sdr = snapshot.displayCapabilitiesFor(displayName = "HDMI-A-1")
        assertTrue(sdr.isKnown)
        assertFalse(sdr.supportsHdr)
    }

    @Test
    fun `unknown output is never inferred as HDR`() {
        val snapshot = LinuxWaylandColorCapabilitiesParser.parse(WAYLAND_INFO)
        assertFalse(snapshot.displayCapabilitiesFor(displayName = "missing").isKnown)
    }

    @Test
    fun `decodes a directly queried native PQ output`() {
        val flags =
            (1L shl 0) or // completed
                (1L shl 1) or // manager
                (1L shl 2) or // parametric
                (1L shl 3) or // BT.2020 input
                (1L shl 4) or // PQ input
                (1L shl 5) or // HLG input
                (1L shl 6) or // output description
                (1L shl 7) or // output PQ
                (1L shl 9) // output BT.2020
        val snapshot =
            LinuxNativeWaylandColorCapabilitiesDecoder.decode(
                longArrayOf(flags, 37, 50, 1_000, 203),
            ) ?: error("native snapshot")

        assertTrue(snapshot.hasColorManager)
        assertTrue(snapshot.supportsParametricDescriptions)
        val display = snapshot.displayCapabilitiesFor(globalId = 37)
        assertTrue(display.isKnown)
        assertTrue(display.supports(VideoDynamicRange.HDR10))
        assertFalse(display.supports(VideoDynamicRange.HDR10_PLUS))
        assertTrue(display.supports(VideoDynamicRange.HLG))
        assertEquals(0.005f, display.minLuminanceNits)
        assertEquals(1_000f, display.maxLuminanceNits)
        assertEquals(203f, display.referenceWhiteNits)
    }

    @Test
    fun `native output without a confirmed transfer remains unknown`() {
        val completedDescription = (1L shl 0) or (1L shl 1) or (1L shl 6)
        val snapshot =
            LinuxNativeWaylandColorCapabilitiesDecoder.decode(
                longArrayOf(completedDescription, 42, 0, 0, 0),
            ) ?: error("native snapshot")

        assertFalse(snapshot.displayCapabilitiesFor(globalId = 42).isKnown)
        assertNull(LinuxNativeWaylandColorCapabilitiesDecoder.decode(longArrayOf(0, 42, 0, 0, 0)))
    }

    @Test
    fun `native SDR transfer is reported as known SDR only`() {
        val completedSdr = (1L shl 0) or (1L shl 1) or (1L shl 6) or (1L shl 10)
        val snapshot =
            LinuxNativeWaylandColorCapabilitiesDecoder.decode(
                longArrayOf(completedSdr, 42, 2_000, 80, 80),
            ) ?: error("native snapshot")

        val display = snapshot.displayCapabilitiesFor(globalId = 42)
        assertTrue(display.isKnown)
        assertFalse(display.supportsHdr)
    }

    private companion object {
        val WAYLAND_INFO =
            """
            interface: 'wl_output',                                  version:  4, name: 37
            \tname: DP-1
            \tdescription: HDR monitor
            interface: 'wl_output',                                  version:  4, name: 42
            \tname: HDMI-A-1
            \tdescription: SDR monitor
            interface: 'wp_color_manager_v1',                         version:  2, name: 51
            \tsupported rendering intents:
            \t\tperceptual
            \tsupported features:
            \t\tparametric
            \t\tset_mastering_display_primaries
            \tsupported named transfer functions:
            \t\tbt1886
            \t\tst2084_pq
            \t\thlg
            \tsupported named primaries:
            \t\tsrgb
            \t\tbt2020
            \toutput: 37
            \t\timage description id: 101
            \t\tprimaries_named: bt2020
            \t\ttf_named: st2084_pq
            \t\tluminances (cd/m²): min 0.0050 max 1000 reference 203
            \toutput: 42
            \t\timage description id: 102
            \t\tprimaries_named: srgb
            \t\ttf_named: bt1886
            \t\tluminances (cd/m²): min 0.2000 max 80 reference 80
            """.trimIndent().replace("\\t", "\t")
    }
}
