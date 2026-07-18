package io.github.kdroidfilter.composemediaplayer

import android.hardware.DataSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidNativeSurfaceColorConfigurationTest {
    @Test
    fun mapsStandardHdrSignalsToTheirAndroidDataspaces() {
        assertEquals(
            DataSpace.DATASPACE_BT2020_PQ,
            VideoDynamicRange.HDR10.androidNativeSurfaceDataSpaceOrNull(),
        )
        assertEquals(
            DataSpace.DATASPACE_BT2020_PQ,
            VideoDynamicRange.HDR10_PLUS.androidNativeSurfaceDataSpaceOrNull(),
        )
        assertEquals(
            DataSpace.DATASPACE_BT2020_HLG,
            VideoDynamicRange.HLG.androidNativeSurfaceDataSpaceOrNull(),
        )
    }

    @Test
    fun doesNotOverrideSdrUnknownOrDolbyVisionDataspaces() {
        assertNull(VideoDynamicRange.SDR.androidNativeSurfaceDataSpaceOrNull())
        assertNull(VideoDynamicRange.UNKNOWN.androidNativeSurfaceDataSpaceOrNull())
        assertNull(VideoDynamicRange.DOLBY_VISION.androidNativeSurfaceDataSpaceOrNull())
    }

    @Test
    fun usesNativeWindowReadbackFromAndroid9() {
        assertFalse(
            canConfirmAndroidNativeHdrWithSurfaceDataSpace(
                VideoDynamicRange.HLG,
                sdkInt = 27,
                nativeBridgeAvailable = true,
            ),
        )
        assertTrue(
            canConfirmAndroidNativeHdrWithSurfaceDataSpace(
                VideoDynamicRange.HLG,
                sdkInt = 28,
                nativeBridgeAvailable = true,
            ),
        )
        assertFalse(
            canConfirmAndroidNativeHdrWithSurfaceDataSpace(
                VideoDynamicRange.HLG,
                sdkInt = 32,
                nativeBridgeAvailable = false,
            ),
        )
        assertFalse(
            canConfirmAndroidNativeHdrWithSurfaceDataSpace(
                VideoDynamicRange.HLG,
                sdkInt = 33,
                nativeBridgeAvailable = false,
            ),
        )
        assertFalse(
            canConfirmAndroidNativeHdrWithSurfaceDataSpace(
                VideoDynamicRange.DOLBY_VISION,
                sdkInt = 36,
                nativeBridgeAvailable = true,
            ),
        )
    }

    @Test
    fun latchesAConfirmedNativeHdrCompositionUntilTheSurfaceIsReset() {
        val confirmation = AndroidSystemReportedHdrConfirmation()

        confirmation.observe(
            route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
            outputDynamicRange = VideoDynamicRange.HLG,
            displayReportsActiveHdr = true,
        )
        confirmation.observe(
            route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
            outputDynamicRange = VideoDynamicRange.HLG,
            displayReportsActiveHdr = false,
        )

        assertTrue(confirmation.confirms(VideoDynamicRange.HLG))
        confirmation.reset()
        assertFalse(confirmation.confirms(VideoDynamicRange.HLG))
    }

    @Test
    fun doesNotLatchInferredOrControlledHdrOutput() {
        val confirmation = AndroidSystemReportedHdrConfirmation()

        confirmation.observe(
            route = ColorPipelineRoute.SYSTEM_NATIVE_SURFACE,
            outputDynamicRange = VideoDynamicRange.HDR10,
            displayReportsActiveHdr = false,
        )
        confirmation.observe(
            route = ColorPipelineRoute.CONTROLLED_HDR_RENDERER,
            outputDynamicRange = VideoDynamicRange.HDR10,
            displayReportsActiveHdr = true,
        )

        assertFalse(confirmation.confirms(VideoDynamicRange.HDR10))
    }
}
