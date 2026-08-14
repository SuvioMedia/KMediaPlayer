@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.libvlc.IosLibVlcVideoPlayerState
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSBundle
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosLibVlcRuntimeTest {
    @Test
    fun bundledProbeNeverFallsBackToAUserInstalledVlc() {
        when (val availability = inspectLibVlcBackend()) {
            is LibVlcBackendAvailability.Available ->
                assertEquals(LibVlcFrameDeliveryMode.CPU_PULL, availability.deliveryMode)
            is LibVlcBackendAvailability.Unavailable -> {
                assertFalse(
                    requiresBundledCandidate,
                    "The simulator integration bundle requires an available KMediaVlc runtime: " +
                        availability.guidance,
                )
                assertTrue(
                    availability.reason in
                        setOf(
                            LibVlcBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
                            LibVlcBackendUnavailableReason.INVALID_RUNTIME,
                            LibVlcBackendUnavailableReason.INITIALIZATION_FAILED,
                        ),
                )
            }
        }
    }

    @Test
    fun embeddedCandidateCreatesAPlayerAndCopiesARealVideoFrame() =
        runBlocking {
            val availability = inspectLibVlcBackend()
            if (availability is LibVlcBackendAvailability.Unavailable) {
                assertFalse(
                    requiresBundledCandidate,
                    "The simulator integration bundle requires an available KMediaVlc runtime: " +
                        availability.guidance,
                )
                return@runBlocking
            }
            val fixturePath = "${NSTemporaryDirectory()}composemediaplayer-libvlc-frame.mkv"
            SOLID_VIDEO_MKV.writeToFile(fixturePath)
            val state = createLibVlcVideoPlayerState() as IosLibVlcVideoPlayerState
            try {
                state.openUri(fixturePath)
                withTimeout(20_000L) {
                    while (
                        state.currentFrame.value == null ||
                        state.metadata.width.orZero() <= 0 ||
                        state.metadata.height.orZero() <= 0
                    ) {
                        check(state.error == null) {
                            "KMediaPlayer rejected the bundled iOS video frame: ${state.error}"
                        }
                        delay(25L)
                    }
                }
                assertNotNull(state.currentFrame.value)
                assertTrue(state.metadata.width.orZero() > 0)
                assertTrue(state.metadata.height.orZero() > 0)
            } finally {
                state.dispose()
            }
        }

    private fun Int?.orZero(): Int = this ?: 0

    private val requiresBundledCandidate: Boolean
        get() =
            (NSBundle.mainBundle.objectForInfoDictionaryKey(REQUIRE_RUNTIME_INFO_KEY) as? NSNumber)
                ?.boolValue == true

    private fun ByteArray.writeToFile(path: String) {
        val descriptor = open(path, O_WRONLY or O_CREAT or O_TRUNC, 0x180)
        check(descriptor >= 0) { "Unable to create the iOS libVLC test fixture." }
        try {
            usePinned { pinned ->
                var completed = 0
                while (completed < size) {
                    val count = write(descriptor, pinned.addressOf(completed), (size - completed).toULong())
                    check(count > 0) { "Unable to write the iOS libVLC test fixture." }
                    completed += count.toInt()
                }
            }
        } finally {
            close(descriptor)
        }
    }

    private companion object {
        const val REQUIRE_RUNTIME_INFO_KEY = "ComposeMediaPlayerRequireBundledKMediaVlc"

        @Suppress("ktlint:standard:max-line-length")
        val SOLID_VIDEO_MKV =
            (
                "1a45dfa3a34286810142f7810142f2810442f381084282886d6174726f736b614287810442858102185380670100000000000bff114d9b74c0bf84ac0414624d" +
                    "bb8b53ab841549a96653ac81a14dbb8b53ab841654ae6b53ac81f14dbb8c53ab841254c36753ac82018c4dbb8c53ab841c53bb6b53ac820bbdec010000000000" +
                    "00530000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
                    "0000000000000000000000000000000000000000001549a966cbbf84d3be997d2ad7b1830f42404d808d4c61766636322e31322e31303257418d4c6176663632" +
                    "2e31322e31303273a490dc4d1e7098e6dc5d359760a95e8a4c8144898840a77000000000001654ae6b4095bf84d27ef2b7ae0100000000000086d7810173c588" +
                    "e1a628f5b5d512b39c810022b59c83756e64888100868f565f4d504547342f49534f2f41564383810123e38384027bc86ae091b0820140ba81b49a810255b084" +
                    "55b9810155ee8100ec0100000000000002000063a2aa0142c01effe100196742c01ea61105067e7c0440000003004000000c03c58b846001000668c8420312c8" +
                    "1254c3674083bf84685e21497373a063c08067c89a45a387454e434f44455244878d4c61766636322e31322e3130327373d763c08b63c588e1a628f5b5d512b3" +
                    "67c8a245a387454e434f4445524487954c61766336322e32382e313032206c69627832363467c8a145a3884455524154494f4e44879330303a30303a30332e30" +
                    "3030303030303030001f43b67549a2bf84ccf3b83ae78100a3434581000080000002710605ffff6ddc45e9bde6d948b7962cd820d923eeef78323634202d2063" +
                    "6f7265203136352072333232322062333536303561202d20482e3236342f4d5045472d342041564320636f646563202d20436f70796c65667420323030332d32" +
                    "303235202d20687474703a2f2f7777772e766964656f6c616e2e6f72672f783236342e68746d6c202d206f7074696f6e733a2063616261633d30207265663d31" +
                    "36206465626c6f636b3d313a303a3020616e616c7973653d3078313a3078313331206d653d756d68207375626d653d3130207073793d31207073795f72643d31" +
                    "2e30303a302e3030206d697865645f7265663d31206d655f72616e67653d3234206368726f6d615f6d653d31207472656c6c69733d32203878386463743d3020" +
                    "63716d3d3020646561647a6f6e653d32312c313120666173745f70736b69703d31206368726f6d615f71705f6f66667365743d2d3220746872656164733d3620" +
                    "6c6f6f6b61686561645f746872656164733d3120736c696365645f746872656164733d30206e723d3020646563696d6174653d3120696e7465726c616365643d" +
                    "3020626c757261795f636f6d7061743d3020636f6e73747261696e65645f696e7472613d3020626672616d65733d3020776569676874703d30206b6579696e74" +
                    "3d3234206b6579696e745f6d696e3d3133207363656e656375743d3020696e7472615f726566726573683d302072635f6c6f6f6b61686561643d32342072633d" +
                    "637266206d62747265653d31206372663d33382e302071636f6d703d302e36302071706d696e3d302071706d61783d3639207170737465703d342069705f7261" +
                    "74696f3d312e34302061713d313a312e30300080000000c86588820678898500010338e00020171c000417ae7befbefbefbefbefbefbefbefbefaebaebaebaeb" +
                    "aebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebae" +
                    "baebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaeba" +
                    "ebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebc0a38f81002a0000000007419a1c0cf01e30a38f8100530000000007419a2a033c" +
                    "078ca38f81007d0000000007419a3b033c078ca38f8100a70000000007419a4900cf01e3a38f8100d00000000007419a5940cf01e3a38f8100fa000000000741" +
                    "9a6980cf01e3a38f8101240000000007419a79c0cf01e3a39081014d0000000008419a888033c078c0a3908101770000000008419a989033c078c0a3908101a1" +
                    "0000000008419aa8a033c078c0a3908101ca0000000008419ab8b033c078c0a3908101f40000000008419ac8c033c078c0a39081021e0000000008419ad8d033" +
                    "c078c0a3908102470000000008419ae8e033c078c0a3908102710000000008419af8f033c078c0a38f81029b0000000007419b0019e03c60a38f8102c4000000" +
                    "0007419b1019e03c60a38f8102ee0000000007419b2019e03c60a38f8103180000000007419b3019e03c60a38f8103410000000007419b4019e03c60a38f8103" +
                    "6b0000000007419b5019e03c60a38f8103950000000007419b6019e03c60a38f8103be0000000007419b7019e03c60a340cf8103e880000000c765888101fe23" +
                    "14000421e3800081ac700010debefbefbefbefbefbefbefbefbefbebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaeb" +
                    "aebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebae" +
                    "baebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaeba" +
                    "f0a38f8104120000000007419a1c0cf01e30a38f81043b0000000007419a2a033c078ca38f8104650000000007419a3b033c078ca38f81048f0000000007419a" +
                    "4900cf01e3a38f8104b80000000007419a5940cf01e3a38f8104e20000000007419a6980cf01e3a38f81050c0000000007419a79c0cf01e3a390810535000000" +
                    "0008419a888033c078c0a39081055f0000000008419a989033c078c0a3908105890000000008419aa8a033c078c0a3908105b20000000008419ab8b033c078c0" +
                    "a3908105dc0000000008419ac8c033c078c0a3908106060000000008419ad8d033c078c0a39081062f0000000008419ae8e033c078c0a3908106590000000008" +
                    "419af8f033c078c0a38f8106830000000007419b0019e03c60a38f8106ac0000000007419b1019e03c60a38f8106d60000000007419b2019e03c60a38f810700" +
                    "0000000007419b3019e03c60a38f8107290000000007419b4019e03c60a38f8107530000000007419b5019e03c60a38f81077d0000000007419b6019e03c60a3" +
                    "8f8107a60000000007419b7019e03c60a340cf8107d080000000c765888207f88c500010878e000206b1c000437afbefbefbefbefbefbefbefbefbefaebaebae" +
                    "baebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaeba" +
                    "ebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaeb" +
                    "aebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebaebc0a38f8107fa0000000007419a1c0cf01e30a38f8108230000000007419a2a" +
                    "033c078ca38f81084d0000000007419a3b033c078ca38f8108770000000007419a4900cf01e3a38f8108a00000000007419a5940cf01e3a38f8108ca00000000" +
                    "07419a6980cf01e3a38f8108f40000000007419a79c0cf01e3a39081091d0000000008419a888033c078c0a3908109470000000008419a989033c078c0a39081" +
                    "09710000000008419aa8a033c078c0a39081099a0000000008419ab8b033c078c0a3908109c40000000008419ac8c033c078c0a3908109ee0000000008419ad8" +
                    "d033c078c0a390810a170000000008419ae8e033c078c0a390810a410000000008419af8f033c078c0a38f810a6b0000000007419b0019e03c60a38f810a9400" +
                    "00000007419b1019e03c60a38f810abe0000000007419b2019e03c60a38f810ae80000000007419b3019e03c60a38f810b110000000007419b4019e03c60a38f" +
                    "810b3b0000000007419b5019e03c60a38f810b650000000007419b6017e03c60a38f810b8e0000000007419b7015e03c601c53bb6bbdbf84b4eb83d8bb8fb381" +
                    "00b78af78101f1820215f08109bb91b38203e8b78bf78101f1820215f08204e0bb91b38207d0b78bf78101f1820215f0820741"
            ).hexToByteArray()
    }
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
