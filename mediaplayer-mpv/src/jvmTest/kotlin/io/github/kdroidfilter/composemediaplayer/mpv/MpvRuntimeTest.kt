package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvBackendUnavailableReason
import io.github.kdroidfilter.composemediaplayer.inspectMpvBackend
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpvRuntimeTest {
    @Test
    fun transitiveRuntimeMatchesThePublishedDesktopMatrix() {
        val osName = System.getProperty("os.name").lowercase()
        val architecture = System.getProperty("os.arch").lowercase()
        val isSupported =
            (osName.contains("mac") && architecture in setOf("aarch64", "arm64")) ||
                (
                    osName.contains("linux") &&
                        architecture in setOf("amd64", "x86_64", "aarch64", "arm64")
                )

        val availability = inspectMpvBackend()
        if (isSupported) {
            assertIs<MpvBackendAvailability.Available>(availability)
        } else {
            val unavailable = assertIs<MpvBackendAvailability.Unavailable>(availability)
            assertEquals(MpvBackendUnavailableReason.UNSUPPORTED_PLATFORM, unavailable.reason)
        }
    }

    @Test
    fun rejectsInvalidLibrarySources() {
        assertFailsWith<IllegalArgumentException> {
            MpvLibrarySource.SystemLibrary(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            MpvLibrarySource.SystemLibrary("some/path/libmpv")
        }
        assertFailsWith<IllegalArgumentException> {
            MpvLibrarySource.SystemLibrary("some\\path\\libmpv")
        }
        assertFailsWith<IllegalArgumentException> {
            MpvLibrarySource.ExplicitPath(Path.of("relative-libmpv"))
        }
    }

    @Test
    fun validatesRenderPixelLimit() {
        assertEquals(1, MpvRuntimeConfig(maxRenderPixels = 1).maxRenderPixels)
        assertEquals(67_108_864, MpvRuntimeConfig(maxRenderPixels = 67_108_864).maxRenderPixels)

        assertFailsWith<IllegalArgumentException> {
            MpvRuntimeConfig(maxRenderPixels = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MpvRuntimeConfig(maxRenderPixels = 67_108_865)
        }
    }

    @Test
    fun exposesOnlyAnExistingExplicitSubtitleFontDirectory() {
        assertFailsWith<IllegalArgumentException> {
            MpvRuntimeConfig(subtitleFontsDirectory = Path.of("relative-fonts"))
        }

        val directory = Files.createTempDirectory("kmediampv-fonts-")
        try {
            val options =
                mpvInitializationOptions(
                    MpvRuntimeConfig(subtitleFontsDirectory = directory),
                )
            assertEquals(directory.normalize().toString(), options["sub-fonts-dir"])
            assertEquals("no", options["sub-ass-override"])
            assertEquals("yes", options["embeddedfonts"])
            assertFalse("sub-font-provider" in options)
        } finally {
            Files.delete(directory)
        }

        assertFailsWith<IllegalArgumentException> {
            mpvInitializationOptions(
                MpvRuntimeConfig(subtitleFontsDirectory = directory),
            )
        }
    }

    @Test
    fun reportsMissingExplicitLibraryWithoutRequiringInstalledMpv() {
        val missingLibrary =
            Path
                .of(System.getProperty("java.io.tmpdir"))
                .resolve("missing-libmpv-${UUID.randomUUID()}")
                .toAbsolutePath()
        val availability =
            MpvRuntime.inspect(
                MpvRuntimeConfig(
                    librarySource = MpvLibrarySource.ExplicitPath(missingLibrary),
                ),
            )

        val unavailable = assertIs<MpvRuntimeAvailability.Unavailable>(availability)
        assertEquals(MpvUnavailableReason.LIBRARY_NOT_FOUND, unavailable.reason)
    }

    @Test
    fun exposesClientApiContractWithoutLoadingNativeCode() {
        assertEquals(2, MpvRuntime.COMPILED_CLIENT_API_MAJOR)
        assertEquals(5, MpvRuntime.COMPILED_CLIENT_API_MINOR)
        assertEquals("2.5", MpvClientApiVersion(major = 2, minor = 5).toString())
        assertEquals(
            MpvRuntimeLicenseStatus.UNVERIFIED_USER_PROVIDED,
            MpvRuntimeInfo(
                clientApiVersion = MpvClientApiVersion(major = 2, minor = 5),
                loadedFrom = MpvLibrarySource.SystemLibrary("mpv"),
            ).licenseStatus,
        )
    }

    @Test
    fun boundsVerifiedRuntimeSourcesToLocalFiles() {
        assertTrue("/private/video.mkv".isLocalMpvSource())
        assertTrue("relative/video.mkv".isLocalMpvSource())
        assertTrue("file:///private/video.mkv".isLocalMpvSource())
        assertFalse("https://example.invalid/video.mkv".isLocalMpvSource())
        assertFalse("rtsp://example.invalid/live".isLocalMpvSource())
    }
}
