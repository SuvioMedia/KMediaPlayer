package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaCodecTest {
    @Test
    fun supportedAudioCodecsContainOnlyAudioCodecs() =
        runBlocking {
            assertTrue(SupportedMediaCodecs.queryAudioCodecs().all { it.type == MediaCodecType.AUDIO })
        }

    @Test
    fun supportedVideoCodecsContainOnlyVideoCodecs() =
        runBlocking {
            assertTrue(SupportedMediaCodecs.queryVideoCodecs().all { it.type == MediaCodecType.VIDEO })
        }

    @Test
    fun queryReturnsSnapshotWithAudioAndVideoCodecs() =
        runBlocking {
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
        runBlocking {
            val support = SupportedMediaCodecs.query()

            assertEquals(
                support.audioCodecs + support.videoCodecs,
                support.allCodecs,
            )
        }

    @Test
    fun codecExtensionMatchesProviderResult() =
        runBlocking {
            MediaCodec.entries.forEach { codec ->
                assertEquals(SupportedMediaCodecs.queryIsSupported(codec), codec.isSupported())
            }
        }

    @Test
    fun snapshotChecksCodecSupportWithoutQueryingAgain() =
        runBlocking {
            val support = SupportedMediaCodecs.query()

            MediaCodec.entries.forEach { codec ->
                assertEquals(SupportedMediaCodecs.queryIsSupported(codec), support.isSupported(codec))
            }
        }
}
