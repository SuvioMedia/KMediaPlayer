package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IctcpGamutLut3DTest {
    @Test
    fun storesRgbaWithRedAsTheFastestAxis() {
        val values = IctcpGamutLut3D.generateRgba32f(edge = 3, nominalPeakNits = 100.0)

        assertEquals(3 * 3 * 3 * 4, values.size)
        assertEquals(0.0f, values[0], absoluteTolerance = 1e-6f)
        assertEquals(1.0f, values[3], absoluteTolerance = 1e-6f)

        val neutralHalf = (((1 * 3 + 1) * 3 + 1) * 4)
        assertEquals(0.5f, values[neutralHalf], absoluteTolerance = 2e-4f)
        assertEquals(0.5f, values[neutralHalf + 1], absoluteTolerance = 2e-4f)
        assertEquals(0.5f, values[neutralHalf + 2], absoluteTolerance = 2e-4f)

        val bt2020Red = 2 * 4
        assertEquals(0.9999923f, values[bt2020Red], absoluteTolerance = 2e-4f)
        assertEquals(0.0621531f, values[bt2020Red + 1], absoluteTolerance = 2e-4f)
        assertEquals(0.0435478f, values[bt2020Red + 2], absoluteTolerance = 2e-4f)
        assertTrue(values.all { it in 0.0f..1.0f })
    }

    @Test
    fun rejectsInvalidDimensionsAndPeak() {
        assertFailsWith<IllegalArgumentException> { IctcpGamutLut3D.generateRgba32f(1, 100.0) }
        assertFailsWith<IllegalArgumentException> { IctcpGamutLut3D.generateRgba32f(66, 100.0) }
        assertFailsWith<IllegalArgumentException> { IctcpGamutLut3D.generateRgba32f(3, Double.NaN) }
    }
}
