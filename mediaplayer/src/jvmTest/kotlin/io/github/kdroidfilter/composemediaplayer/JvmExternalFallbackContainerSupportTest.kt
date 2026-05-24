package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmExternalFallbackContainerSupportTest {
    @Test
    fun detectsMatroskaByMagicBytes() =
        runTest {
            val file = Files.createTempFile("compose-media-player-", ".bin")
            try {
                Files.write(
                    file,
                    byteArrayOf(
                        0x1A,
                        0x45,
                        0xDF.toByte(),
                        0xA3.toByte(),
                        0xA3.toByte(),
                        0x42,
                        0x86.toByte(),
                        0x81.toByte(),
                        0x01,
                    ) + "matroska".toByteArray(),
                )

                assertTrue(JvmExternalFallbackContainerSupport.needsContainerFallback(file.toString()))
            } finally {
                file.deleteIfExists()
            }
        }

    @Test
    fun detectsWebmByExtension() =
        runTest {
            val file = Files.createTempFile("compose-media-player-", ".webm")
            try {
                assertTrue(JvmExternalFallbackContainerSupport.needsContainerFallback(file.toString()))
            } finally {
                file.deleteIfExists()
            }
        }

    @Test
    fun keepsMp4OnNativePlaybackPath() =
        runTest {
            val file = Files.createTempFile("compose-media-player-", ".mp4")
            try {
                Files.write(file, byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70))

                assertFalse(JvmExternalFallbackContainerSupport.needsContainerFallback(file.toString()))
            } finally {
                file.deleteIfExists()
            }
        }

    @Test
    fun rejectsEbmlHeaderWithoutMatroskaDocType() {
        val bytes =
            byteArrayOf(
                0x1A,
                0x45,
                0xDF.toByte(),
                0xA3.toByte(),
                0x42,
                0x86.toByte(),
                0x81.toByte(),
                0x01,
            )

        assertFalse(JvmExternalFallbackContainerSupport.hasMatroskaSignature(bytes))
    }
}
