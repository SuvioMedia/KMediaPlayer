package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DolbyVisionInfo
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DolbyVisionExtensionPolicyTest {
    private val extension = DolbyVisionExtension(availableConverter)

    @Test
    fun `AUTO conversion requires an explicit platform planner decision`() =
        runTest {
            assertEquals(
                VideoPipelineSourcePreparation.NotApplicable,
                extension.prepareSource(request(policy = DolbyVisionPolicy.AUTO)),
            )
            assertRejection(
                extension.prepareSource(
                    request(policy = DolbyVisionPolicy.AUTO, profile = 5).copy(
                        automaticDolbyVisionConversionAllowed = true,
                    ),
                ),
                ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED,
            )
            assertEquals(
                VideoPipelineSourcePreparation.NotApplicable,
                extension.prepareSource(
                    request().copy(source = VideoColorInfo(dynamicRange = VideoDynamicRange.HDR10)),
                ),
            )
        }

    @Test
    fun `unknown RPU signalling is delegated to the bridge rather than rejected as missing`() =
        runTest {
            val unavailableExtension = DolbyVisionExtension(unavailableConverter)

            assertRejection(
                unavailableExtension.prepareSource(
                    request(hasRpu = null).copy(automaticDolbyVisionConversionAllowed = true),
                ),
                ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE,
            )
        }

    @Test
    fun `prepared output identifies profile 8_1 and no enhancement layer`() {
        val output = request().profile81OutputColorInfo()

        assertEquals(8, output.dolbyVision?.profile)
        assertEquals(true, output.dolbyVision?.hasRpu)
        assertEquals(true, output.dolbyVision?.hasHdr10CompatibleBaseLayer)
        assertEquals(
            io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer.NONE,
            output.dolbyVision?.enhancementLayer,
        )
    }

    @Test
    fun `extension rejects unsupported profile and missing RPU before opening a bridge`() =
        runTest {
            assertRejection(
                extension.prepareSource(request(profile = 5)),
                ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED,
            )
            assertRejection(
                extension.prepareSource(request(hasRpu = false)),
                ColorPipelineFallbackReason.DOLBY_VISION_RPU_UNAVAILABLE,
            )
        }

    @Test
    fun `extension rejects live and DRM sources without invoking conversion`() =
        runTest {
            assertRejection(
                extension.prepareSource(request().copy(isLive = true)),
                ColorPipelineFallbackReason.LIVE_SOURCE_CONVERSION_UNSUPPORTED,
            )
            assertRejection(
                extension.prepareSource(request().copy(isDrmProtected = true)),
                ColorPipelineFallbackReason.DRM_CONVERSION_UNSUPPORTED,
            )
        }

    private fun request(
        policy: DolbyVisionPolicy = DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1,
        profile: Int = 7,
        hasRpu: Boolean? = true,
    ) = VideoPipelineSourceRequest(
        uri = "https://example.invalid/movie.mp4",
        source =
            VideoColorInfo(
                dynamicRange = VideoDynamicRange.DOLBY_VISION,
                dolbyVision = DolbyVisionInfo(profile = profile, hasRpu = hasRpu),
            ),
        dynamicRangePolicy = DynamicRangePolicy.AUTO,
        dolbyVisionPolicy = policy,
    )

    private fun assertRejection(
        preparation: VideoPipelineSourcePreparation,
        reason: ColorPipelineFallbackReason,
    ) {
        val rejected = assertIs<VideoPipelineSourcePreparation.Rejected>(preparation)
        assertEquals(reason, rejected.reason)
    }

    private companion object {
        val availableConverter =
            object : DolbyVisionRpuConverter {
                override val isAvailable = true

                override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult =
                    error("Conversion must not run for rejected requests.")
            }
        val unavailableConverter =
            object : DolbyVisionRpuConverter {
                override val isAvailable = false

                override suspend fun prepare(): Boolean = false

                override suspend fun convertProfile7To81(rpuNalUnit: ByteArray): DolbyVisionRpuConversionResult =
                    error("Conversion must not run when the runtime is unavailable.")
            }
    }
}
