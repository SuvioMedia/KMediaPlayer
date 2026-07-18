package io.github.kdroidfilter.composemediaplayer.ass

import io.github.kdroidfilter.composemediaplayer.DesktopSubtitlePipelineExtension
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleAssRendererTest {
    @Test
    fun bundledMacRendererMatrixIsArm64Only() {
        assertEquals("darwin-aarch64", macOsAssResourcePlatform("arm64"))
        assertFailsWith<UnsupportedOperationException> {
            macOsAssResourcePlatform("x86_64")
        }
    }

    @Test
    fun systemLibraryCandidatesCoverWindowsAndLinuxNames() {
        assertEquals(
            listOf(
                "C:\\native\\libass-9.dll",
                "ass",
                "libass",
                "libass-9",
                "ass-9",
            ),
            systemLibAssCandidates(
                osName = "Windows 11",
                configuredPath = "C:\\native\\libass-9.dll",
                searchDirectories = emptyList(),
            ),
        )
        assertEquals(
            listOf(
                "/opt/lib/libass.so.9",
                "ass",
                "libass.so.9",
                "libass.so",
            ),
            systemLibAssCandidates(
                osName = "Linux",
                configuredPath = "/opt/lib/libass.so.9",
                searchDirectories = emptyList(),
            ),
        )
    }

    @Test
    fun bundledDesktopRendererMatrixCoversX64AndArm64() {
        assertEquals("windows-x86-64", desktopAssResourcePlatform("Windows 11", "amd64"))
        assertEquals("windows-aarch64", desktopAssResourcePlatform("Windows 11", "arm64"))
        assertEquals("linux-x86-64", desktopAssResourcePlatform("Linux", "x86_64"))
        assertEquals("linux-aarch64", desktopAssResourcePlatform("Linux", "aarch64"))
        assertFailsWith<UnsupportedOperationException> {
            desktopAssResourcePlatform("Linux", "x86")
        }
    }

    @Test
    fun bundledDesktopManifestPinsEveryRuntimeFile() {
        val checksum = "a".repeat(64)
        val manifest =
            readBundledLibAssManifest(
                ByteArrayInputStream(
                    """
                    version=0.17.5
                    mainLibrary=libass.so.9
                    file.count=2
                    file.0.name=libass.so.9
                    file.0.sha256=$checksum
                    file.1.name=libfribidi.so.0
                    file.1.sha256=$checksum
                    """.trimIndent().encodeToByteArray(),
                ),
            )

        assertEquals("libass.so.9", manifest.mainLibrary)
        assertEquals("0.17.5", manifest.declaredVersion)
        assertEquals(listOf("libass.so.9", "libfribidi.so.0"), manifest.files.map { it.name })
    }

    @Test
    fun bundledDesktopManifestRejectsPathTraversal() {
        assertFailsWith<IllegalArgumentException> {
            readBundledLibAssManifest(
                ByteArrayInputStream(
                    """
                    version=0.17.5
                    mainLibrary=../libass.so.9
                    file.count=1
                    file.0.name=../libass.so.9
                    file.0.sha256=${"a".repeat(64)}
                    """.trimIndent().encodeToByteArray(),
                ),
            )
        }
    }

    @Test
    fun windowsAbsoluteLoaderCanUseScopedDependencySearch() {
        if (!System.getProperty("os.name", "").contains("windows", ignoreCase = true)) return
        val windowsDirectory = System.getenv("WINDIR") ?: return
        val kernel32 = Path.of(windowsDirectory, "System32", "kernel32.dll")
        if (!Files.isRegularFile(kernel32)) return

        loadWindowsLibraryWithSiblingDependencies(kernel32)
    }

    @Test
    fun bundledMacRendererBlendsAuthoredAssIntoDirectBgraFrame() {
        if (!isMacOs()) return

        val extension: DesktopSubtitlePipelineExtension = AssSubtitleExtension()
        assertTrue(extension.availability.canContribute)
        val renderer = assertNotNull(extension.createRenderer())
        renderer.use {
            assertTrue(renderer.setTrack(SAMPLE_ASS.encodeToByteArray()))
            val width = 640
            val height = 360
            val frame = ByteBuffer.allocateDirect(width * height * BGRA_BYTES)
            assertTrue(
                renderer.blendBgraFrame(
                    pixels = frame,
                    rowBytes = width * BGRA_BYTES,
                    width = width,
                    height = height,
                    timeMs = 1_000L,
                ),
            )

            val bytes = ByteArray(frame.capacity())
            frame.get(bytes)
            assertTrue(
                bytes.indices.any { index ->
                    index % BGRA_BYTES != ALPHA_INDEX && bytes[index] != 0.toByte()
                },
                "libass returned no visible subtitle pixels.",
            )
        }
    }

    @Test
    fun bundledDesktopRendererBlendsAuthoredAssWhenPayloadIsPresent() {
        if (!isWindowsOrLinux()) return
        val platform =
            desktopAssResourcePlatform(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
            )
        val bundledManifest =
            AppleAssRendererTest::class.java.classLoader
                .getResource("composemediaplayer/ass/native/$platform/runtime.properties")
        if (bundledManifest == null && !SystemLibAssRuntime.isAvailable) return
        assertTrue(SystemLibAssRuntime.isAvailable, SystemLibAssRuntime.failureDetail)

        val extension: DesktopSubtitlePipelineExtension = AssSubtitleExtension()
        assertTrue(extension.availability.canContribute)
        val renderer = assertNotNull(extension.createRenderer())
        renderer.use {
            val overrideConfigured =
                !System.getProperty("composemediaplayer.ass.libraryPath").isNullOrBlank() ||
                    !System.getenv("KMEDIA_ASS_LIBRARY_PATH").isNullOrBlank()
            if (bundledManifest != null && !overrideConfigured) {
                assertTrue(
                    renderer.backendDescription.startsWith("bundled libass"),
                    "Expected bundled libass, got ${renderer.backendDescription}.",
                )
            }
            assertTrue(renderer.setTrack(SAMPLE_ASS.encodeToByteArray()))
            val width = 640
            val height = 360
            val frame = ByteBuffer.allocateDirect(width * height * BGRA_BYTES)
            assertTrue(
                renderer.blendBgraFrame(
                    pixels = frame,
                    rowBytes = width * BGRA_BYTES,
                    width = width,
                    height = height,
                    timeMs = 1_000L,
                ),
            )

            val bytes = ByteArray(frame.capacity())
            frame.get(bytes)
            assertTrue(
                bytes.indices.any { index ->
                    index % BGRA_BYTES != ALPHA_INDEX && bytes[index] != 0.toByte()
                },
                "Desktop libass returned no visible subtitle pixels.",
            )
        }
    }

    private companion object {
        const val BGRA_BYTES = 4
        const val ALPHA_INDEX = 3
        val SAMPLE_ASS =
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 360

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,1,2,20,20,24,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:00.00,0:00:05.00,Default,,0,0,0,,KMediaPlayer
            """.trimIndent()
    }
}

private fun isMacOs(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return "mac" in osName || "darwin" in osName
}

private fun isWindowsOrLinux(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return "windows" in osName || "linux" in osName
}
