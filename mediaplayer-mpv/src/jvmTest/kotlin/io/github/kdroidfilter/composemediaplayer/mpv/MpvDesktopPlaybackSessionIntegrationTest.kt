@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer.mpv

import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.MediaSourceSpec
import io.github.kdroidfilter.composemediaplayer.MpvMacRenderer
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.createMpvVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendAvailability
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackRequest
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackSession
import io.github.kdroidfilter.composemediaplayer.desktop.JvmHttpHlsMediaProxyFactory
import io.github.kdroidfilter.composemediaplayer.mpvDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.withSynchronizedExternalAudioPlayback
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MpvDesktopPlaybackSessionIntegrationTest {
    @Test
    fun bundledMacVkOpensProxiedMediaDirectly() {
        exerciseDirectProxiedPlayback(MpvMacRenderer.MOLTENVK, "macvk")
    }

    @Test
    fun bundledOpenGlOpensProxiedMediaDirectly() {
        exerciseDirectProxiedPlayback(MpvMacRenderer.OPENGL, "OpenGL")
    }

    private fun exerciseDirectProxiedPlayback(
        macRenderer: MpvMacRenderer,
        expectedRenderer: String,
    ) = runBlocking {
        if (!isMacArm64()) return@runBlocking
        val source = configuredLoopbackSource() ?: return@runBlocking
        val proxy =
            JvmHttpHlsMediaProxyFactory().openProxy(
                DesktopPlaybackRequest(source = MediaSourceSpec(source)),
            )
        val player =
            createMpvVideoPlayerState(MpvPlaybackOptions(macRenderer = macRenderer))
                .withSynchronizedExternalAudioPlayback()

        try {
            val connection = URI.create(proxy.localUri).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
            } finally {
                connection.disconnect()
            }
            player.openUri(proxy.localUri, InitialPlayerState.PAUSE)
            val deadline = System.nanoTime() + TEST_TIMEOUT_NANOS
            while (System.nanoTime() < deadline && player.error == null && (!player.hasMedia || player.isLoading)) {
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
            assertEquals(null, player.error)
            assertTrue(player.hasMedia && !player.isLoading)
            assertContains(player.renderingInfo.videoRenderer.orEmpty(), expectedRenderer)
        } finally {
            player.dispose()
            proxy.close()
        }
    }

    @Test
    fun bundledMacVkOpensProxiedMediaAfterAvailabilityProbes() =
        runBlocking {
            if (!isMacArm64()) return@runBlocking
            val source = configuredLoopbackSource() ?: return@runBlocking
            val backend = mpvDesktopPlaybackBackend().withExternalAudioFallback()
            assertIs<DesktopBackendAvailability.Available>(backend.inspectAvailability())
            val session =
                DesktopPlaybackSession(
                    backends = listOf(backend),
                    readyTimeout = 30.seconds,
                    hlsMediaProxyFactory = JvmHttpHlsMediaProxyFactory(),
                )

            try {
                val player =
                    session.open(
                        request =
                            DesktopPlaybackRequest(
                                source = MediaSourceSpec(source),
                                initialPlayerState = InitialPlayerState.PAUSE,
                            ),
                        backendId = backend.info.id,
                    )
                assertTrue(player.hasMedia)
                assertContains(player.renderingInfo.videoRenderer.orEmpty(), "macvk")
            } finally {
                session.close()
            }
        }

    private fun configuredLoopbackSource(): String? =
        System.getProperty(TEST_SOURCE_PROPERTY)?.takeIf(String::isNotBlank)?.also { source ->
            require(source.isNumericLoopbackHttpUri()) {
                "$TEST_SOURCE_PROPERTY accepts an uncredentialed numeric-loopback HTTP URI only."
            }
        }

    private fun DesktopPlaybackBackend.withExternalAudioFallback(): DesktopPlaybackBackend {
        val backend = this
        return object : DesktopPlaybackBackend by backend {
            override fun createPlayerState(): VideoPlayerState =
                backend.createPlayerState().withSynchronizedExternalAudioPlayback()
        }
    }

    private fun String.isNumericLoopbackHttpUri(): Boolean {
        val uri = runCatching { URI.create(this) }.getOrNull() ?: return false
        return uri.scheme.equals("http", ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port in 1..65535 &&
            uri.host?.removeSurrounding("[", "]") in setOf("127.0.0.1", "::1")
    }

    private fun isMacArm64(): Boolean {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val architecture = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
        return (os.contains("mac") || os.contains("darwin")) && architecture in setOf("aarch64", "arm64")
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 20L
        const val TEST_TIMEOUT_NANOS = 30_000_000_000L
        const val TEST_SOURCE_PROPERTY = "composemediaplayer.test.loopbackHttpSource"
    }
}
