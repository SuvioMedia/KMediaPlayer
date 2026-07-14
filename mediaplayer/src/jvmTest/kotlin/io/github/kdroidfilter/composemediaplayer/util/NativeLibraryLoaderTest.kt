package io.github.kdroidfilter.composemediaplayer.util

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeLibraryLoaderTest {
    @Test
    fun nativePlatformDetectionAcceptsOnlyPackagedArchitectures() {
        assertEquals("win32-x86-64", nativePlatformFor("Windows 11", "amd64"))
        assertEquals("win32-arm64", nativePlatformFor("Windows 11", "aarch64"))
        assertEquals("linux-x86-64", nativePlatformFor("Linux", "x86_64"))
        assertEquals("linux-aarch64", nativePlatformFor("Linux", "arm64"))
        assertEquals("darwin-x86-64", nativePlatformFor("Mac OS X", "x64"))
        assertEquals("darwin-arm64", nativePlatformFor("Darwin", "arm64"))

        val error = assertFailsWith<UnsupportedOperationException> { nativePlatformFor("Linux", "riscv64") }
        assertTrue(error.message.orEmpty().contains("Unsupported native architecture"))
    }

    @Test
    fun nativeCacheIsVersionedAndContentAddressed() {
        val root = Files.createTempDirectory("native-cache-test")
        val bytes = "trusted-native-content".encodeToByteArray()

        try {
            val cached =
                materializeNativeLibrary(
                    cacheRoot = root,
                    version = "1.2.3",
                    platform = "test-x86-64",
                    fileName = "libTest.so",
                    resource = { ByteArrayInputStream(bytes) },
                )

            assertContentEquals(bytes, cached.readBytes())
            assertEquals(bytes.sha256(), cached.parent.fileName.toString())
            assertTrue(cached.startsWith(root.resolve("native/1.2.3/test-x86-64")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptedCacheEntryIsReplacedBeforeUse() {
        val root = Files.createTempDirectory("native-cache-repair-test")
        val bytes = "expected-native-content".encodeToByteArray()
        val extract = {
            materializeNativeLibrary(
                cacheRoot = root,
                version = "test",
                platform = "test",
                fileName = "libTest.so",
                resource = { ByteArrayInputStream(bytes) },
            )
        }

        try {
            val cached = extract()
            Files.delete(cached)
            Files.write(cached, "tampered".encodeToByteArray())
            assertTrue(cached.toFile().setReadOnly())

            assertEquals(cached, extract())
            assertContentEquals(bytes, cached.readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentExtractionConvergesOnOneVerifiedFile() {
        val root = Files.createTempDirectory("native-cache-lock-test")
        val bytes = ByteArray(128 * 1024) { index -> (index % 251).toByte() }
        val executor = Executors.newFixedThreadPool(8)

        try {
            val tasks =
                List(16) {
                    Callable {
                        materializeNativeLibrary(
                            cacheRoot = root,
                            version = "test",
                            platform = "test",
                            fileName = "libConcurrent.so",
                            resource = { ByteArrayInputStream(bytes) },
                        )
                    }
                }
            val cachedPaths = executor.invokeAll(tasks).map { it.get() }

            assertEquals(1, cachedPaths.toSet().size)
            assertContentEquals(bytes, cachedPaths.first().readBytes())
        } finally {
            executor.shutdownNow()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun nativeFileNameCannotEscapeCacheDirectory() {
        val root = Files.createTempDirectory("native-cache-path-test")

        try {
            assertFailsWith<IllegalArgumentException> {
                materializeNativeLibrary(
                    cacheRoot = root,
                    version = "test",
                    platform = "test",
                    fileName = "../outside.so",
                    resource = { ByteArrayInputStream(byteArrayOf(1)) },
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun nonRegularCacheEntryIsRejectedBeforeItCanBeRead() {
        val root = Files.createTempDirectory("native-cache-non-regular-test")
        val bytes = "expected-native-content".encodeToByteArray()
        val digest = bytes.sha256()
        val cachedPath = root.resolve("native/test/test/$digest/libTest.so")

        try {
            Files.createDirectories(cachedPath)

            val error =
                assertFailsWith<IllegalStateException> {
                    materializeNativeLibrary(
                        cacheRoot = root,
                        version = "test",
                        platform = "test",
                        fileName = "libTest.so",
                        resource = { ByteArrayInputStream(bytes) },
                    )
                }

            assertTrue(error.message.orEmpty().contains("non-regular native cache entry"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
