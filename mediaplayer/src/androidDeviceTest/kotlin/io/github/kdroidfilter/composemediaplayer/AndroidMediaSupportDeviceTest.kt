package io.github.kdroidfilter.composemediaplayer

import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidMediaSupportDeviceTest {
    @Test
    fun verifiedPhilipsReportsAllSupportedHdrRangesBeforePlayback() =
        runBlocking {
            assumeTrue(Build.BRAND.equals("Philips", ignoreCase = true))

            val supportedHdr = MediaSupport.querySupportedHdrDynamicRanges()

            assertEquals(
                setOf(
                    VideoDynamicRange.HDR10,
                    VideoDynamicRange.HDR10_PLUS,
                    VideoDynamicRange.HLG,
                    VideoDynamicRange.DOLBY_VISION,
                ),
                supportedHdr,
            )
            assertEquals(
                VideoDynamicRangeSupport.SUPPORTED,
                MediaSupport.queryDynamicRangeSupport(VideoDynamicRange.HDR10_PLUS),
            )
        }

    @Suppress("DEPRECATION")
    @Test
    fun nvidiaShieldDoesNotGainHdr10PlusFromAnOutputThatDoesNotAdvertiseIt() =
        runBlocking {
            assumeTrue(Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val display =
                context
                    .getSystemService(DisplayManager::class.java)
                    .getDisplay(Display.DEFAULT_DISPLAY)
            val advertisedHdrTypes =
                display.hdrCapabilities
                    ?.supportedHdrTypes
                    ?.toSet()
                    .orEmpty()
            assumeTrue(
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS !in advertisedHdrTypes,
            )

            assertEquals(
                VideoDynamicRangeSupport.UNSUPPORTED,
                MediaSupport.queryDynamicRangeSupport(VideoDynamicRange.HDR10_PLUS),
            )
            assertFalse(VideoDynamicRange.HDR10_PLUS in MediaSupport.querySupportedHdrDynamicRanges())
        }
}
