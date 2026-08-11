package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AndroidLibVlcOptionsTest {
    @Test
    fun acceptsTheBoundedDefaultSurfaceContract() {
        assertEquals(null, validateAndroidLibVlcOptions(LibVlcPlaybackOptions()))
    }

    @Test
    fun rejectsDesktopFrameDeliveryModes() {
        val unavailable =
            assertNotNull(
                validateAndroidLibVlcOptions(
                    LibVlcPlaybackOptions(frameDeliveryPolicy = LibVlcFrameDeliveryPolicy.GPU_PUSH),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.INVALID_RUNTIME, unavailable.reason)
    }

    @Test
    fun rejectsProjectionAndTextureCrop() {
        val projection =
            assertNotNull(
                validateAndroidLibVlcOptions(
                    LibVlcPlaybackOptions(
                        projection = VideoProjectionSettings(projectionType = VideoProjectionType.Equirect360),
                    ),
                ),
            )
        val crop =
            assertNotNull(
                validateAndroidLibVlcOptions(
                    LibVlcPlaybackOptions(projectionTextureCrop = VideoTextureCrop(left = 0.1f)),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE, projection.reason)
        assertEquals(LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE, crop.reason)
    }

    @Test
    fun rejectsColorPoliciesTheSurfaceBridgeCannotVerify() {
        val forceSdr =
            assertNotNull(
                validateAndroidLibVlcOptions(
                    LibVlcPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR),
                ),
            )
        val requireHdr =
            assertNotNull(
                validateAndroidLibVlcOptions(
                    LibVlcPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE, forceSdr.reason)
        assertEquals(LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE, requireHdr.reason)
    }

    @Test
    fun rejectsRequiredNativeDolbyVision() {
        val unavailable =
            assertNotNull(
                validateAndroidLibVlcOptions(
                    LibVlcPlaybackOptions(dolbyVisionPolicy = DolbyVisionPolicy.REQUIRE_NATIVE),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.NATIVE_DOLBY_VISION_UNSUPPORTED, unavailable.reason)
    }

    @Test
    fun rejectsUnverifiedDolbyVisionConversions() {
        val baseLayer =
            assertNotNull(
                validateAndroidLibVlcOptions(
                    LibVlcPlaybackOptions(dolbyVisionPolicy = DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER),
                ),
            )
        val conversion =
            assertNotNull(
                validateAndroidLibVlcOptions(
                    LibVlcPlaybackOptions(dolbyVisionPolicy = DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1),
                ),
            )

        assertEquals(LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE, baseLayer.reason)
        assertEquals(LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE, conversion.reason)
    }
}
