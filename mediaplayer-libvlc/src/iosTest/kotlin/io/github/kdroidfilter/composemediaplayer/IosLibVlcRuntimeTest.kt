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
    fun embeddedCandidateCreatesAPlayerAndCopiesARealFrame() =
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
            val fixturePath = "${NSTemporaryDirectory()}composemediaplayer-libvlc-frame.png"
            SOLID_RGBA_PNG.writeToFile(fixturePath)
            val state = createLibVlcVideoPlayerState() as IosLibVlcVideoPlayerState
            try {
                state.openUri(fixturePath)
                withTimeout(20_000L) {
                    while (
                        state.currentFrame.value == null ||
                        state.metadata.width.orZero() <= 0 ||
                        state.metadata.height.orZero() <= 0
                    ) {
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
        val SOLID_RGBA_PNG =
            (
                "89504e470d0a1a0a0000000d4948445200000040000000240806000000390c3c92" +
                    "000000454944415478daedd0310100000803204f63d8bfe4eca11c14a0329dcf4a" +
                    "800001020408102040800001020408102040800001020408102040800001020408" +
                    "1020408080db161677571e852442cd0000000049454e44ae426082"
            ).hexToByteArray()
    }
}

private fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
