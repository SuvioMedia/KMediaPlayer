package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VideoPipelineExtensionTest {
    @Test
    fun `source request rejects a negative bridge start position`() {
        assertFailsWith<IllegalArgumentException> { request().copy(startPositionMs = -1L) }
    }

    @Test
    fun `extension capabilities are merged without erasing platform capabilities`() {
        val options =
            VideoPlaybackOptions(
                extensions =
                    listOf(
                        object : VideoPipelineExtension {
                            override val id = "test-converter"
                            override val colorConversionCapabilities =
                                ColorConversionCapabilities(
                                    supportsDolbyVisionProfile7To8 = true,
                                    supportsHdrToSdrSourceBridge = true,
                                    supportsStreamingVOD = true,
                                )
                        },
                    ),
            )

        val result =
            PlayerCapabilities(
                colorConversionCapabilities =
                    ColorConversionCapabilities(supportsHdr10PlusApplication = true),
            ).withPipelineExtensions(options)

        assertTrue(result.colorConversionCapabilities.supportsDolbyVisionProfile7To8)
        assertTrue(result.colorConversionCapabilities.supportsHdrToSdrSourceBridge)
        assertTrue(result.colorConversionCapabilities.supportsStreamingVOD)
        assertTrue(result.colorConversionCapabilities.supportsHdr10PlusApplication)
    }

    @Test
    fun `duplicate or blank extension ids are rejected before player creation`() {
        assertFailsWith<IllegalArgumentException> {
            VideoPlaybackOptions(
                extensions =
                    listOf(
                        sourceExtension("duplicate") { VideoPipelineSourcePreparation.NotApplicable },
                        sourceExtension("duplicate") { VideoPipelineSourcePreparation.NotApplicable },
                    ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VideoPlaybackOptions(
                extensions = listOf(sourceExtension(" ") { VideoPipelineSourcePreparation.NotApplicable }),
            )
        }
    }

    @Test
    fun `unavailable source extension is never invoked`() =
        runTest {
            val extension =
                object : VideoSourcePipelineExtension {
                    override val id = "unavailable"
                    override val availability =
                        VideoPipelineExtensionAvailability.unavailable("Runtime missing.")

                    override suspend fun prepareSource(
                        request: VideoPipelineSourceRequest,
                    ): VideoPipelineSourcePreparation = error("Unavailable extension must not run.")
                }

            val result =
                VideoPlaybackOptions(extensions = listOf(extension))
                    .prepareSourceWithExtensions(request())

            assertEquals(VideoPipelineSourcePreparation.NotApplicable, result)
        }

    @Test
    fun `source resolver skips non owners and returns one managed source`() =
        runTest {
            var closed = false
            val prepared =
                object : PreparedVideoPipelineSource {
                    override val uri = "http://127.0.0.1/prepared.m3u8"
                    override val outputColorInfo = VideoColorInfo(VideoDynamicRange.DOLBY_VISION)
                    override val metadataHandling = DynamicMetadataHandling.CONVERTED

                    override fun close() {
                        closed = true
                    }
                }
            val options =
                VideoPlaybackOptions(
                    extensions =
                        listOf(
                            sourceExtension("skip") { VideoPipelineSourcePreparation.NotApplicable },
                            sourceExtension("owner") { VideoPipelineSourcePreparation.Ready(prepared) },
                            sourceExtension("must-not-run") { error("Only one extension may own a source") },
                        ),
                )

            val result = options.prepareSourceWithExtensions(request())

            assertEquals(prepared, assertIs<VideoPipelineSourcePreparation.Ready>(result).source)
            assertFalse(closed)
            prepared.close()
            assertTrue(closed)
        }

    @Test
    fun `extension exceptions become typed conversion rejection`() =
        runTest {
            val options =
                VideoPlaybackOptions(
                    extensions = listOf(sourceExtension("broken") { error("bad bridge") }),
                )

            val rejected =
                assertIs<VideoPipelineSourcePreparation.Rejected>(
                    options.prepareSourceWithExtensions(request()),
                )

            assertEquals(ColorPipelineFallbackReason.DOLBY_VISION_CONVERTER_UNAVAILABLE, rejected.reason)
            assertTrue(rejected.detail.contains("bad bridge"))
        }

    @Test
    fun `source bridge exceptions become typed tone mapper rejection`() =
        runTest {
            val options =
                VideoPlaybackOptions(
                    extensions = listOf(sourceExtension("broken-tone-mapper") { error("bad tone mapper") }),
                )

            val rejected =
                assertIs<VideoPipelineSourcePreparation.Rejected>(
                    options.prepareSourceWithExtensions(
                        request().copy(
                            source = VideoColorInfo(VideoDynamicRange.HLG),
                            dolbyVisionPolicy = DolbyVisionPolicy.AUTO,
                            requestedOutputDynamicRange = VideoDynamicRange.SDR,
                        ),
                    ),
                )

            assertEquals(ColorPipelineFallbackReason.TONE_MAPPER_UNAVAILABLE, rejected.reason)
            assertTrue(rejected.detail.contains("bad tone mapper"))
        }

    private fun sourceExtension(
        id: String,
        prepare: suspend (VideoPipelineSourceRequest) -> VideoPipelineSourcePreparation,
    ) = object : VideoSourcePipelineExtension {
        override val id = id

        override suspend fun prepareSource(request: VideoPipelineSourceRequest) = prepare(request)
    }

    private fun request() =
        VideoPipelineSourceRequest(
            uri = "movie.mp4",
            source = VideoColorInfo(VideoDynamicRange.DOLBY_VISION),
            dynamicRangePolicy = DynamicRangePolicy.AUTO,
            dolbyVisionPolicy = DolbyVisionPolicy.CONVERT_PROFILE_7_TO_8_1,
        )
}
