package io.github.kdroidfilter.composemediaplayer

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalHlsFallbackSupportTest {
    @Test
    fun autoPrefersVlcWhenVlcAndFfmpegAreAvailable() {
        withFakeExecutables { vlcPath, ffmpegPath ->
            withSystemProperties(
                mapOf(
                    "composemediaplayer.vlc" to vlcPath.toString(),
                    "composemediaplayer.ffmpeg" to ffmpegPath.toString(),
                    "composemediaplayer.hlsFallbackBackend" to "auto",
                    "composemediaplayer.macos.hlsFallbackBackend" to null,
                    "composemediaplayer.windows.hlsFallbackBackend" to null,
                    "composemediaplayer.linux.hlsFallbackBackend" to null,
                ),
            ) {
                assertEquals(
                    ExternalHlsFallbackBackend.VLC,
                    ExternalHlsFallbackSupport.selectBackend(requiresSubtitleRendering = false),
                )
            }
        }
    }

    @Test
    fun configuredFfmpegBackendOverridesVlcFirstAutoSelection() {
        withFakeExecutables { vlcPath, ffmpegPath ->
            withSystemProperties(
                mapOf(
                    "composemediaplayer.vlc" to vlcPath.toString(),
                    "composemediaplayer.ffmpeg" to ffmpegPath.toString(),
                    "composemediaplayer.hlsFallbackBackend" to "ffmpeg",
                    "composemediaplayer.macos.hlsFallbackBackend" to null,
                    "composemediaplayer.windows.hlsFallbackBackend" to null,
                    "composemediaplayer.linux.hlsFallbackBackend" to null,
                ),
            ) {
                assertEquals(
                    ExternalHlsFallbackBackend.FFMPEG,
                    ExternalHlsFallbackSupport.selectBackend(requiresSubtitleRendering = false),
                )
            }
        }
    }

    private fun withFakeExecutables(block: (Path, Path) -> Unit) {
        val vlcPath = fakeExecutable("vlc")
        val ffmpegPath = fakeExecutable("ffmpeg")
        try {
            block(vlcPath, ffmpegPath)
        } finally {
            Files.deleteIfExists(vlcPath)
            Files.deleteIfExists(ffmpegPath)
        }
    }

    private fun fakeExecutable(name: String): Path {
        val path = Files.createTempFile(name, null)
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        path.toFile().setExecutable(true)
        return path
    }

    private fun withSystemProperties(
        values: Map<String, String?>,
        block: () -> Unit,
    ) {
        val previous = values.keys.associateWith(System::getProperty)
        try {
            values.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
            block()
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
        }
    }
}
