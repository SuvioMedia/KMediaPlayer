package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IosLibVlcOptionsTest {
    @Test
    fun acceptsTheAuditedCpuPullDefaults() {
        assertNull(validateIosLibVlcOptions(LibVlcPlaybackOptions()))
        assertNull(
            validateIosLibVlcOptions(
                LibVlcPlaybackOptions(frameDeliveryPolicy = LibVlcFrameDeliveryPolicy.CPU_PULL),
            ),
        )
    }

    @Test
    fun rejectsGpuPushUntilIosOwnsANativeTransport() {
        val unavailable =
            assertNotNull(
                validateIosLibVlcOptions(
                    LibVlcPlaybackOptions(frameDeliveryPolicy = LibVlcFrameDeliveryPolicy.GPU_PUSH),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.GPU_OUTPUT_UNAVAILABLE, unavailable.reason)
    }

    @Test
    fun rejectsProjectionAndTextureCrop() {
        val projection =
            assertNotNull(
                validateIosLibVlcOptions(
                    LibVlcPlaybackOptions(
                        projection = VideoProjectionSettings(projectionType = VideoProjectionType.Equirect360),
                    ),
                ),
            )
        val crop =
            assertNotNull(
                validateIosLibVlcOptions(
                    LibVlcPlaybackOptions(projectionTextureCrop = VideoTextureCrop(left = 0.1f)),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE, projection.reason)
        assertEquals(LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE, crop.reason)
    }

    @Test
    fun rejectsUnverifiedColorPolicies() {
        val forceSdr =
            assertNotNull(
                validateIosLibVlcOptions(
                    LibVlcPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR),
                ),
            )
        val requireHdr =
            assertNotNull(
                validateIosLibVlcOptions(
                    LibVlcPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR),
                ),
            )
        val dolbyVision =
            assertNotNull(
                validateIosLibVlcOptions(
                    LibVlcPlaybackOptions(dolbyVisionPolicy = DolbyVisionPolicy.REQUIRE_NATIVE),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE, forceSdr.reason)
        assertEquals(LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE, requireHdr.reason)
        assertEquals(LibVlcBackendUnavailableReason.NATIVE_DOLBY_VISION_UNSUPPORTED, dolbyVision.reason)
    }

    @Test
    fun rejectsDesktopRuntimePathsOnIos() {
        val unavailable =
            assertNotNull(
                validateIosLibVlcOptions(
                    LibVlcPlaybackOptions(desktopRuntimeDirectory = "/application/private/runtime"),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.INVALID_RUNTIME, unavailable.reason)
    }
}
