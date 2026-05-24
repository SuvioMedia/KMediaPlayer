package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaCodecTest {
    @Test
    fun supportedAudioCodecsContainOnlyAudioCodecs() =
        runTest {
            assertTrue(MediaSupport.queryAudioCodecs().all { it.type == MediaCodecType.AUDIO })
        }

    @Test
    fun supportedVideoCodecsContainOnlyVideoCodecs() =
        runTest {
            assertTrue(MediaSupport.queryVideoCodecs().all { it.type == MediaCodecType.VIDEO })
        }

    @Test
    fun queryReturnsSnapshotWithAudioAndVideoCodecs() =
        runTest {
            val support = MediaSupport.query()

            assertEquals(
                MediaSupport.queryAudioCodecs(),
                support.audioCodecs,
            )
            assertEquals(
                MediaSupport.queryVideoCodecs(),
                support.videoCodecs,
            )
        }

    @Test
    fun allCodecsIsTheUnionOfAudioAndVideoCodecs() =
        runTest {
            val support = MediaSupport.query()

            assertEquals(
                support.audioCodecs + support.videoCodecs,
                support.allCodecs,
            )
        }

    @Test
    fun codecExtensionMatchesProviderResult() =
        runTest {
            MediaCodec.entries.forEach { codec ->
                assertEquals(MediaSupport.queryIsCodecSupported(codec), codec.isSupported())
            }
        }

    @Test
    fun snapshotChecksCodecSupportWithoutQueryingAgain() =
        runTest {
            val support = MediaSupport.query()

            MediaCodec.entries.forEach { codec ->
                assertEquals(MediaSupport.queryIsCodecSupported(codec), support.isCodecSupported(codec))
            }
        }

    @Test
    fun queryReturnsPlayerCapabilitiesAndCodecSupportTogether() =
        runTest {
            val support = MediaSupport.query()

            assertEquals(MediaSupport.queryCapabilities(), support.capabilities)
            assertEquals(MediaSupport.queryCodecs(), support.codecs)
        }
}
