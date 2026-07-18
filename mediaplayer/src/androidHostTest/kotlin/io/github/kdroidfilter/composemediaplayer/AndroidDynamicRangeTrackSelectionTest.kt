package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidDynamicRangeTrackSelectionTest {
    @Test
    fun `AUTO preflights an unsupported Profile 7 when Media3 has no playable video track`() {
        val selection =
            selectAndroidPreDecoderProfile7Track(
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                groups =
                    listOf(
                        group(
                            selected = false,
                            track(
                                index = 0,
                                range = VideoDynamicRange.DOLBY_VISION,
                                supported = false,
                                dolbyVisionProfile = 7,
                            ),
                        ),
                    ),
            )

        assertEquals(AndroidPreDecoderProfile7Selection(0, 0), selection)
    }

    @Test
    fun `AUTO leaves Profile 7 alone when Media3 has a supported native video track`() {
        val selection =
            selectAndroidPreDecoderProfile7Track(
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(
                                index = 0,
                                range = VideoDynamicRange.DOLBY_VISION,
                                supported = false,
                                dolbyVisionProfile = 7,
                            ),
                            track(index = 1, range = VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertNull(selection)
    }

    @Test
    fun `explicit conversion preflights Profile 7 even when a native decoder exists`() {
        val selection =
            selectAndroidPreDecoderProfile7Track(
                dolbyVisionPolicy = DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1,
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(
                                index = 4,
                                range = VideoDynamicRange.DOLBY_VISION,
                                dolbyVisionProfile = 7,
                            ),
                        ),
                    ),
            )

        assertEquals(AndroidPreDecoderProfile7Selection(0, 4), selection)
    }

    @Test
    fun `AUTO retains every HDR10 bitrate and excludes SDR on an HDR10 output`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = setOf(VideoDynamicRange.HDR10),
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.SDR),
                            track(1, VideoDynamicRange.HDR10, pixels = 1280L * 720),
                            track(2, VideoDynamicRange.HDR10, pixels = 3840L * 2160),
                        ),
                    ),
            )

        assertEquals(VideoDynamicRange.HDR10, selection?.dynamicRange)
        assertEquals(listOf(1, 2), selection?.trackIndices)
    }

    @Test
    fun `AUTO uses supported HDR10 when Dolby Vision variants exceed decoder capabilities`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = setOf(VideoDynamicRange.DOLBY_VISION, VideoDynamicRange.HDR10),
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.SDR),
                            track(1, VideoDynamicRange.DOLBY_VISION, supported = false),
                            track(2, VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertEquals(VideoDynamicRange.HDR10, selection?.dynamicRange)
        assertEquals(listOf(2), selection?.trackIndices)
    }

    @Test
    fun `AUTO defers a supported Dolby Vision choice until Media3 exposes the decoder format`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = setOf(VideoDynamicRange.DOLBY_VISION, VideoDynamicRange.HDR10),
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.SDR),
                            track(1, VideoDynamicRange.DOLBY_VISION),
                            track(2, VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertNull(selection)
    }

    @Test
    fun `AUTO retains Media3 HDR10 decoder choice instead of forcing Dolby Vision`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = setOf(VideoDynamicRange.DOLBY_VISION, VideoDynamicRange.HDR10),
                currentDynamicRange = VideoDynamicRange.HDR10,
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.SDR),
                            track(1, VideoDynamicRange.DOLBY_VISION),
                            track(2, VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertEquals(VideoDynamicRange.HDR10, selection?.dynamicRange)
        assertEquals(listOf(2), selection?.trackIndices)
    }

    @Test
    fun `AUTO selects publisher SDR rendition when no HDR output route exists`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = emptySet(),
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.SDR),
                            track(1, VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertEquals(VideoDynamicRange.SDR, selection?.dynamicRange)
        assertEquals(listOf(0), selection?.trackIndices)
    }

    @Test
    fun `AUTO selects SDR immediately on an SDR route even when Dolby Vision is advertised`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = emptySet(),
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.SDR),
                            track(1, VideoDynamicRange.DOLBY_VISION),
                            track(2, VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertEquals(VideoDynamicRange.SDR, selection?.dynamicRange)
        assertEquals(listOf(0), selection?.trackIndices)
    }

    @Test
    fun `PREFER HDR keeps HDR source for controlled SDR fallback`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = emptySet(),
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.SDR),
                            track(1, VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertEquals(VideoDynamicRange.HDR10, selection?.dynamicRange)
    }

    @Test
    fun `FORCE SDR selects SDR instead of tone mapping when publisher supplied it`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = setOf(VideoDynamicRange.HDR10),
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.SDR),
                            track(1, VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertEquals(VideoDynamicRange.SDR, selection?.dynamicRange)
    }

    @Test
    fun `HDR10 base layer policy ranks HDR10 ahead of native Dolby Vision`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR,
                dolbyVisionPolicy = DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER,
                hdrOutputSourceRanges = setOf(VideoDynamicRange.DOLBY_VISION, VideoDynamicRange.HDR10),
                groups =
                    listOf(
                        group(
                            selected = true,
                            track(0, VideoDynamicRange.DOLBY_VISION),
                            track(1, VideoDynamicRange.HDR10),
                        ),
                    ),
            )

        assertEquals(VideoDynamicRange.HDR10, selection?.dynamicRange)
    }

    @Test
    fun `single known dynamic range does not install an unnecessary override`() {
        val selection =
            selectAndroidDynamicRangeTracks(
                dynamicRangePolicy = DynamicRangePolicy.AUTO,
                dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                hdrOutputSourceRanges = setOf(VideoDynamicRange.HDR10),
                groups = listOf(group(selected = true, track(0, VideoDynamicRange.HDR10))),
            )

        assertNull(selection)
    }

    private fun group(
        selected: Boolean,
        vararg tracks: AndroidVideoTrackCandidate,
    ): AndroidVideoTrackGroupCandidate =
        AndroidVideoTrackGroupCandidate(
            index = 0,
            isSelected = selected,
            tracks = tracks.toList(),
        )

    private fun track(
        index: Int,
        range: VideoDynamicRange,
        supported: Boolean = true,
        pixels: Long = 0,
        dolbyVisionProfile: Int? = null,
    ): AndroidVideoTrackCandidate =
        AndroidVideoTrackCandidate(
            index = index,
            dynamicRange = range,
            isSupported = supported,
            dolbyVisionProfile = dolbyVisionProfile,
            pixelCount = pixels,
        )
}
