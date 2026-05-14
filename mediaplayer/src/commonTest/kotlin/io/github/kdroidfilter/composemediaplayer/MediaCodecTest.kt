package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaCodecTest {
    @Test
    fun supportedAudioCodecsContainOnlyAudioCodecs() =
        runTest {
            assertTrue(SupportedMediaCodecs.queryAudioCodecs().all { it.type == MediaCodecType.AUDIO })
        }

    @Test
    fun supportedVideoCodecsContainOnlyVideoCodecs() =
        runTest {
            assertTrue(SupportedMediaCodecs.queryVideoCodecs().all { it.type == MediaCodecType.VIDEO })
        }

    @Test
    fun queryReturnsSnapshotWithAudioAndVideoCodecs() =
        runTest {
            val support = SupportedMediaCodecs.query()

            assertEquals(
                SupportedMediaCodecs.queryAudioCodecs(),
                support.audioCodecs,
            )
            assertEquals(
                SupportedMediaCodecs.queryVideoCodecs(),
                support.videoCodecs,
            )
        }

    @Test
    fun allCodecsIsTheUnionOfAudioAndVideoCodecs() =
        runTest {
            val support = SupportedMediaCodecs.query()

            assertEquals(
                support.audioCodecs + support.videoCodecs,
                support.allCodecs,
            )
        }

    @Test
    fun codecExtensionMatchesProviderResult() =
        runTest {
            MediaCodec.entries.forEach { codec ->
                assertEquals(SupportedMediaCodecs.queryIsSupported(codec), codec.isSupported())
            }
        }

    @Test
    fun snapshotChecksCodecSupportWithoutQueryingAgain() =
        runTest {
            val support = SupportedMediaCodecs.query()

            MediaCodec.entries.forEach { codec ->
                assertEquals(SupportedMediaCodecs.queryIsSupported(codec), support.isSupported(codec))
            }
        }
}
