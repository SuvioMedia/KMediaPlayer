package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDisplayColorCapabilitiesTest {
    @Test
    fun supportedTpvPhilipsRestoresUnreportedHdr10PlusSupport() {
        val ranges =
            verifiedUnreportedAndroidDisplayDynamicRanges(
                AndroidDeviceColorIdentity(
                    manufacturer = "TPV",
                    brand = "Philips",
                    product = "another_supported_philips_family",
                    device = "another_supported_philips_family",
                ),
            )

        assertEquals(setOf(VideoDynamicRange.HDR10_PLUS), ranges)
    }

    @Test
    fun supportedPhilipsRestoresSupportRegardlessOfManufacturerField() {
        val ranges =
            verifiedUnreportedAndroidDisplayDynamicRanges(
                AndroidDeviceColorIdentity(
                    manufacturer = "another_licensed_manufacturer",
                    brand = "Philips",
                    product = "supported_philips_family",
                    device = "supported_philips_family",
                ),
            )

        assertEquals(setOf(VideoDynamicRange.HDR10_PLUS), ranges)
    }

    @Test
    fun nonPhilipsTpvDeviceDoesNotGainHdr10PlusSupport() {
        val ranges =
            verifiedUnreportedAndroidDisplayDynamicRanges(
                AndroidDeviceColorIdentity(
                    manufacturer = "TPV",
                    brand = "Another brand",
                    product = "unrelated_product",
                    device = "unrelated_device",
                ),
            )

        assertEquals(emptySet(), ranges)
    }

    @Test
    fun unrelatedAndroidDisplayDoesNotGainHdr10PlusSupport() {
        val ranges =
            verifiedUnreportedAndroidDisplayDynamicRanges(
                AndroidDeviceColorIdentity(
                    manufacturer = "NVIDIA",
                    brand = "NVIDIA",
                    product = "foster_e",
                    device = "foster",
                ),
            )

        assertEquals(emptySet(), ranges)
    }
}
