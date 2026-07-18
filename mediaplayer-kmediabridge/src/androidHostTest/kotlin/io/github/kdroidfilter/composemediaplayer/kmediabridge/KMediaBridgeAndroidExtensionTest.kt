package io.github.kdroidfilter.composemediaplayer.kmediabridge

import io.github.kdroidfilter.composemediaplayer.ColorPipelineFallbackReason
import io.github.kdroidfilter.composemediaplayer.DolbyVisionPolicy
import io.github.kdroidfilter.composemediaplayer.DynamicRangePolicy
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoDynamicRange
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourcePreparation
import io.github.kdroidfilter.composemediaplayer.VideoPipelineSourceRequest
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KMediaBridgeAndroidExtensionTest {
    private val extension = KMediaBridgeAndroidExtension()

    @Test
    fun `capability contribution is limited to bounded VOD source tone mapping`() {
        val capabilities = extension.colorConversionCapabilities
        val runtimeAvailable = extension.availability.canContribute

        assertEquals(runtimeAvailable, capabilities.supportsHdrToSdrSourceBridge)
        assertEquals(runtimeAvailable, capabilities.supportsStreamingVOD)
        assertEquals(false, capabilities.supportsDolbyVisionProfile7To8)
        assertEquals(false, capabilities.supportsHdr10PlusApplication)
    }

    @Test
    fun `requests outside SDR replacement are not owned`() =
        runTest {
            val result = extension.prepareSource(request().copy(requestedOutputDynamicRange = null))

            assertEquals(VideoPipelineSourcePreparation.NotApplicable, result)
        }

    @Test
    fun `live and DRM are rejected before native runtime loading`() =
        runTest {
            val live =
                assertIs<VideoPipelineSourcePreparation.Rejected>(
                    extension.prepareSource(request().copy(isLive = true)),
                )
            val drm =
                assertIs<VideoPipelineSourcePreparation.Rejected>(
                    extension.prepareSource(request().copy(isDrmProtected = true)),
                )

            assertEquals(ColorPipelineFallbackReason.LIVE_SOURCE_CONVERSION_UNSUPPORTED, live.reason)
            assertEquals(ColorPipelineFallbackReason.DRM_CONVERSION_UNSUPPORTED, drm.reason)
        }

    @Test
    fun `remote source is rejected without exposing its locator`() =
        runTest {
            val result =
                assertIs<VideoPipelineSourcePreparation.Rejected>(
                    extension.prepareSource(request().copy(uri = "https://media.invalid/signed.mkv?token=secret")),
                )

            assertEquals(ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE, result.reason)
            assertTrue("token" !in result.detail)
            assertTrue("secret" !in result.detail)
        }

    @Test
    fun `Dolby Vision cannot enter the generic tone mapper`() =
        runTest {
            val result =
                assertIs<VideoPipelineSourcePreparation.Rejected>(
                    extension.prepareSource(
                        request().copy(source = VideoColorInfo(VideoDynamicRange.DOLBY_VISION)),
                    ),
                )

            assertEquals(ColorPipelineFallbackReason.DOLBY_VISION_PROFILE_UNSUPPORTED, result.reason)
        }

    @Test
    fun `memory and fragment limits fail at construction`() {
        assertFailsWith<IllegalArgumentException> { KMediaBridgeAndroidExtension(maximumBufferedFragments = 0) }
        assertFailsWith<IllegalArgumentException> { KMediaBridgeAndroidExtension(maximumFragmentBytes = 512) }
        assertFailsWith<IllegalArgumentException> { KMediaBridgeAndroidExtension(fragmentDurationUs = 100_000L) }
    }

    private fun request(): VideoPipelineSourceRequest =
        VideoPipelineSourceRequest(
            uri = "/data/local/tmp/movie.mkv",
            source = VideoColorInfo(VideoDynamicRange.HLG),
            dynamicRangePolicy = DynamicRangePolicy.AUTO,
            dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
            requestedOutputDynamicRange = VideoDynamicRange.SDR,
        )
}
