package io.github.kdroidfilter.composemediaplayer.dolbyvision

import io.github.kdroidfilter.composemediaplayer.DolbyVisionEnhancementLayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DolbyVisionStreamingBridgeTest {
    @Test
    fun `planner reports FEL loss and rejects live DRM and missing runtime`() {
        val request = request(DolbyVisionEnhancementLayer.FEL)
        val fel = DolbyVisionConversionPlanner.plan(request, runtimeAvailable = true)
        val live = DolbyVisionConversionPlanner.plan(request.copy(container = DolbyVisionContainer.HLS_LIVE), true)
        val drm = DolbyVisionConversionPlanner.plan(request.copy(isDrmProtected = true), true)
        val missing = DolbyVisionConversionPlanner.plan(request, runtimeAvailable = false)

        assertTrue(fel.canConvert)
        assertTrue(fel.discardsEnhancementLayer)
        assertTrue(fel.discardsFelMapping)
        assertEquals(DolbyVisionConversionRejection.LIVE_SOURCE_UNSUPPORTED, live.rejection)
        assertEquals(DolbyVisionConversionRejection.DRM_UNSUPPORTED, drm.rejection)
        assertEquals(DolbyVisionConversionRejection.MODULE_RUNTIME_UNAVAILABLE, missing.rejection)
        assertEquals(
            DolbyVisionConversionRejection.LIVE_SOURCE_UNSUPPORTED,
            DolbyVisionConversionPlanner.plan(request.copy(container = DolbyVisionContainer.HLS_LIVE), false).rejection,
        )
    }

    @Test
    fun `bridge preserves VFR timestamps and bounds fragment memory`() =
        runTest {
            val bridge =
                DolbyVisionStreamingBridge(
                    request = request(DolbyVisionEnhancementLayer.MEL).copy(maximumBufferedFragments = 2),
                    converter = passthroughConverter,
                    remuxer = confirmingRemuxer,
                )

            assertIs<DolbyVisionFragmentConversionResult.Success>(bridge.convert(fragment(1, 0, 33_367, 0)))
            assertIs<DolbyVisionFragmentConversionResult.Success>(bridge.convert(fragment(2, 33_367, 75_042, 0)))
            assertIs<DolbyVisionFragmentConversionResult.Success>(bridge.convert(fragment(3, 75_042, 116_708, 75_042)))

            assertEquals(listOf(2L, 3L), bridge.bufferedFragments().map { it.fragment.sequence })
            assertEquals(75_042L, bridge.restartTimeForSeek(100_000))
            assertEquals(0L, bridge.restartTimeForSeek(70_000))
        }

    @Test
    fun `bridge prepares a lazy converter before freezing a runtime decision`() =
        runTest {
            var available = false
            val bridge =
                DolbyVisionStreamingBridge(
                    request = request(DolbyVisionEnhancementLayer.MEL),
                    converter =
                        object : DolbyVisionRpuConverter {
                            override val isAvailable: Boolean get() = available

                            override suspend fun prepare(): Boolean {
                                available = true
                                return true
                            }

                            override suspend fun convertProfile7To81(
                                rpuNalUnit: ByteArray,
                            ): DolbyVisionRpuConversionResult = DolbyVisionRpuConversionResult.Success(rpuNalUnit)
                        },
                    remuxer = confirmingRemuxer,
                )

            assertFalse(bridge.plan.canConvert)
            assertIs<DolbyVisionFragmentConversionResult.Success>(bridge.convert(fragment(1, 0, 30_000, 0)))
            assertTrue(bridge.plan.canConvert)
        }

    @Test
    fun `malformed or mis-timestamped RPU never reaches remuxer`() =
        runTest {
            var remuxCalled = false
            val bridge =
                DolbyVisionStreamingBridge(
                    request = request(DolbyVisionEnhancementLayer.MEL),
                    converter = passthroughConverter,
                    remuxer =
                        object : DolbyVisionFragmentRemuxer {
                            override val isAvailable = true

                            override suspend fun remux(
                                source: DolbyVisionMediaFragment,
                                convertedRpus: List<TimedDolbyVisionRpu>,
                            ): ConvertedDolbyVisionFragment {
                                remuxCalled = true
                                return ConvertedDolbyVisionFragment(source, convertedRpus, true)
                            }
                        },
                )
            val invalid =
                fragment(1, 0, 30_000, 0).copy(
                    rpus = listOf(TimedDolbyVisionRpu(40_000, byteArrayOf(1))),
                )

            assertIs<DolbyVisionFragmentConversionResult.Failure>(bridge.convert(invalid))
            assertFalse(remuxCalled)
        }

    @Test
    fun `out of order fragments fail without invoking converter or throwing`() =
        runTest {
            var conversions = 0
            val bridge =
                DolbyVisionStreamingBridge(
                    request = request(DolbyVisionEnhancementLayer.MEL),
                    converter =
                        object : DolbyVisionRpuConverter {
                            override val isAvailable = true

                            override suspend fun convertProfile7To81(
                                rpuNalUnit: ByteArray,
                            ): DolbyVisionRpuConversionResult {
                                conversions++
                                return DolbyVisionRpuConversionResult.Success(rpuNalUnit)
                            }
                        },
                    remuxer = confirmingRemuxer,
                )

            assertIs<DolbyVisionFragmentConversionResult.Success>(bridge.convert(fragment(2, 0, 30_000, 0)))
            val rejected =
                assertIs<DolbyVisionFragmentConversionResult.Failure>(
                    bridge.convert(fragment(1, 30_000, 60_000, 0)),
                )

            assertTrue(rejected.message.contains("sequence"))
            assertEquals(1, conversions)
            assertEquals(listOf(2L), bridge.bufferedFragments().map { it.fragment.sequence })
        }

    @Test
    fun `remuxer must preserve fragment timing and RPU timestamp association`() =
        runTest {
            val bridge =
                DolbyVisionStreamingBridge(
                    request = request(DolbyVisionEnhancementLayer.MEL),
                    converter = passthroughConverter,
                    remuxer =
                        object : DolbyVisionFragmentRemuxer {
                            override val isAvailable = true

                            override suspend fun remux(
                                source: DolbyVisionMediaFragment,
                                convertedRpus: List<TimedDolbyVisionRpu>,
                            ) = ConvertedDolbyVisionFragment(
                                fragment = source.copy(endPresentationTimeUs = source.endPresentationTimeUs + 1),
                                convertedRpus = convertedRpus,
                                timestampsAndAudioPreserved = true,
                            )
                        },
                )

            val rejected =
                assertIs<DolbyVisionFragmentConversionResult.Failure>(
                    bridge.convert(fragment(1, 0, 30_000, 0)),
                )

            assertTrue(rejected.message.contains("timing"))
            assertTrue(bridge.bufferedFragments().isEmpty())
        }

    @Test
    fun `remux failures become typed failures while cancellation propagates`() =
        runTest {
            fun bridge(error: Throwable) =
                DolbyVisionStreamingBridge(
                    request = request(DolbyVisionEnhancementLayer.MEL),
                    converter = passthroughConverter,
                    remuxer =
                        object : DolbyVisionFragmentRemuxer {
                            override val isAvailable = true

                            override suspend fun remux(
                                source: DolbyVisionMediaFragment,
                                convertedRpus: List<TimedDolbyVisionRpu>,
                            ): ConvertedDolbyVisionFragment = throw error
                        },
                )

            val failure =
                assertIs<DolbyVisionFragmentConversionResult.Failure>(
                    bridge(IllegalStateException("broken mux")).convert(fragment(1, 0, 30_000, 0)),
                )
            assertTrue(failure.message.contains("broken mux"))
            assertFailsWith<CancellationException> {
                bridge(CancellationException("seek cancelled")).convert(fragment(1, 0, 30_000, 0))
            }
        }

    @Test
    fun `converter preparation failures are typed while cancellation propagates`() =
        runTest {
            fun bridge(error: Throwable) =
                DolbyVisionStreamingBridge(
                    request = request(DolbyVisionEnhancementLayer.MEL),
                    converter =
                        object : DolbyVisionRpuConverter {
                            override val isAvailable = false

                            override suspend fun prepare(): Boolean = throw error

                            override suspend fun convertProfile7To81(
                                rpuNalUnit: ByteArray,
                            ): DolbyVisionRpuConversionResult = error("conversion must not run")
                        },
                    remuxer = confirmingRemuxer,
                )

            val failure =
                assertIs<DolbyVisionFragmentConversionResult.Failure>(
                    bridge(IllegalStateException("broken runtime")).convert(fragment(1, 0, 30_000, 0)),
                )
            assertTrue(failure.message.contains("broken runtime"))
            assertFailsWith<CancellationException> {
                bridge(CancellationException("cancelled load")).convert(fragment(1, 0, 30_000, 0))
            }
        }

    @Test
    fun `byte budget evicts old fragments and rejects a single oversized output`() =
        runTest {
            val oneMiB = 1024 * 1024
            val bridge =
                DolbyVisionStreamingBridge(
                    request =
                        request(DolbyVisionEnhancementLayer.MEL).copy(
                            maximumBufferedFragments = 4,
                            maximumBufferedBytes = oneMiB.toLong(),
                        ),
                    converter = passthroughConverter,
                    remuxer = confirmingRemuxer,
                )
            val first = fragment(1, 0, 30_000, 0).copy(payload = ByteArray(600_000))
            val second = fragment(2, 30_000, 60_000, 0).copy(payload = ByteArray(600_000))

            assertIs<DolbyVisionFragmentConversionResult.Success>(bridge.convert(first))
            assertIs<DolbyVisionFragmentConversionResult.Success>(bridge.convert(second))
            assertEquals(listOf(2L), bridge.bufferedFragments().map { it.fragment.sequence })

            val oversized = fragment(3, 60_000, 90_000, 60_000).copy(payload = ByteArray(oneMiB))
            assertIs<DolbyVisionFragmentConversionResult.Failure>(bridge.convert(oversized))
            assertEquals(listOf(2L), bridge.bufferedFragments().map { it.fragment.sequence })
        }

    private fun request(layer: DolbyVisionEnhancementLayer) =
        DolbyVisionConversionRequest(
            container = DolbyVisionContainer.FRAGMENTED_MP4,
            profile = 7,
            hasRpu = true,
            enhancementLayer = layer,
        )

    private fun fragment(
        sequence: Long,
        start: Long,
        end: Long,
        keyframe: Long,
    ) = DolbyVisionMediaFragment(
        sequence = sequence,
        startPresentationTimeUs = start,
        endPresentationTimeUs = end,
        precedingKeyframeTimeUs = keyframe,
        payload = byteArrayOf(sequence.toByte()),
        rpus = listOf(TimedDolbyVisionRpu(start, byteArrayOf(0x7c, 0x01))),
    )

    private val passthroughConverter =
        object : DolbyVisionRpuConverter {
            override val isAvailable = true

            override suspend fun convertProfile7To81(rpuNalUnit: ByteArray) =
                DolbyVisionRpuConversionResult.Success(rpuNalUnit + 0x08)
        }

    private val confirmingRemuxer =
        object : DolbyVisionFragmentRemuxer {
            override val isAvailable = true

            override suspend fun remux(
                source: DolbyVisionMediaFragment,
                convertedRpus: List<TimedDolbyVisionRpu>,
            ) = ConvertedDolbyVisionFragment(source, convertedRpus, timestampsAndAudioPreserved = true)
        }
}
