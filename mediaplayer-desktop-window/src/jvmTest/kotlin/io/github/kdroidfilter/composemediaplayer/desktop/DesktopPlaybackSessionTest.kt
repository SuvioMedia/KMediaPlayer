package io.github.kdroidfilter.composemediaplayer.desktop

import io.github.kdroidfilter.composemediaplayer.MediaSourceSpec
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.PreviewableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerBackendInfo
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopPlaybackSessionTest {
    @Test
    fun requestStringRedactsUriAndHeaders() {
        val request =
            DesktopPlaybackRequest(
                source = MediaSourceSpec("https://media.invalid/private.wmv?token=never-print"),
                requestHeaders = mapOf("Authorization" to "never-print"),
            )

        val rendered = request.toString()
        assertTrue("never-print" !in rendered)
        assertTrue("media.invalid" !in rendered)
        assertTrue("<redacted:1>" in rendered)
    }

    @Test
    fun autoRouteFallsThroughWithoutDestroyingTheSession() =
        runTest {
            val failing = fakeBackend("platform", DesktopBackendRoutingTier.PLATFORM_DIRECT) { error("rejected") }
            val expected = PreviewableVideoPlayerState(isPlaying = false)
            val working = fakeBackend("mpv", DesktopBackendRoutingTier.MPV_NATIVE) { expected }
            val session = DesktopPlaybackSession(listOf(working, failing), readyTimeout = 1.seconds)

            try {
                val actual = session.open(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.wmv")))

                assertSame(expected, actual)
                assertEquals("mpv", assertIs<DesktopPlaybackSessionState.Ready>(session.state.value).backend.id)
            } finally {
                session.close()
            }
        }

    @Test
    fun autoRouteUsesDeclaredStagesAndSkipsExplicitOnlyBackends() =
        runTest {
            val attempts = mutableListOf<String>()
            val expected = PreviewableVideoPlayerState(isPlaying = false)

            fun stage(
                id: String,
                tier: DesktopBackendRoutingTier,
                automatic: Boolean = true,
            ): DesktopPlaybackBackend =
                fakeBackend(id, tier, automatic) {
                    attempts += id
                    if (id == "transcode") expected else error("rejected")
                }
            val session =
                DesktopPlaybackSession(
                    backends =
                        listOf(
                            stage("forced-adapter", DesktopBackendRoutingTier.PLATFORM_DIRECT, automatic = false),
                            stage("libvlc", DesktopBackendRoutingTier.LIBVLC_NATIVE),
                            stage("platform", DesktopBackendRoutingTier.PLATFORM_DIRECT),
                            stage("transcode", DesktopBackendRoutingTier.KMEDIA_BRIDGE_TRANSCODE),
                            stage("mpv", DesktopBackendRoutingTier.MPV_NATIVE),
                            stage("remux", DesktopBackendRoutingTier.KMEDIA_BRIDGE_REMUX),
                        ),
                    readyTimeout = 1.seconds,
                )

            try {
                assertSame(
                    expected,
                    session.open(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.wmv"))),
                )
                assertEquals(listOf("platform", "remux", "mpv", "libvlc", "transcode"), attempts)
            } finally {
                session.close()
            }
        }

    @Test
    fun failedExplicitSwitchRollsBackToCurrentPlayer() =
        runTest {
            val current = PreviewableVideoPlayerState(isPlaying = false)
            val platform = fakeBackend("platform", DesktopBackendRoutingTier.PLATFORM_DIRECT) { current }
            val failing = fakeBackend("mpv", DesktopBackendRoutingTier.MPV_NATIVE) { error("rejected") }
            val session = DesktopPlaybackSession(listOf(platform, failing), readyTimeout = 1.seconds)

            try {
                session.open(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.avi")), "platform")

                assertFailsWith<DesktopPlaybackOpenException> { session.switchBackend("mpv") }
                assertSame(current, session.playerState.value)
                assertIs<DesktopPlaybackSessionState.Failed>(session.state.value)
            } finally {
                session.close()
            }
        }

    @Test
    fun bundledMpvMaterializesRemoteBytesWithoutPassingHeadersToNativeRuntime() =
        runTest {
            val payload = "credential-safe-media".encodeToByteArray()
            var sourceClosed = false
            val cacheDirectory = Files.createTempDirectory("desktop-session-test-")
            val mpv =
                object : DesktopPlaybackBackend {
                    override val routingTier = DesktopBackendRoutingTier.MPV_NATIVE
                    override val info =
                        VideoPlayerBackendInfo(
                            id = "mpv",
                            displayName = "mpv",
                            capabilities = PlayerCapabilities(supportedUriSchemes = setOf("file")),
                        )

                    override fun inspectAvailability() = DesktopBackendAvailability.Available()

                    override fun probe(request: DesktopPlaybackRequest) =
                        DesktopBackendProbeResult.Unsupported("Remote networking is disabled.")

                    override fun createPlayerState(): VideoPlayerState = PreviewableVideoPlayerState(isPlaying = false)
                }
            val factory =
                JvmSeekableMediaDataSourceFactory {
                    object : JvmSeekableMediaDataSource {
                        override val length: Long = payload.size.toLong()

                        override suspend fun read(
                            position: Long,
                            destination: ByteBuffer,
                        ): Int {
                            if (position >= payload.size) return -1
                            val count = minOf(destination.remaining(), payload.size - position.toInt())
                            destination.put(payload, position.toInt(), count)
                            return count
                        }

                        override fun close() {
                            sourceClosed = true
                        }
                    }
                }
            val session =
                DesktopPlaybackSession(
                    backends = listOf(mpv),
                    readyTimeout = 1.seconds,
                    seekableMediaDataSourceFactory = factory,
                    mediaCacheDirectory = cacheDirectory,
                )

            try {
                session.open(
                    request =
                        DesktopPlaybackRequest(
                            source = MediaSourceSpec("https://media.invalid/private.wmv?token=redacted"),
                            requestHeaders = mapOf("Authorization" to "redacted"),
                        ),
                    backendId = "mpv",
                )
                Files.list(cacheDirectory).use { files ->
                    val materialized = files.findFirst().orElseThrow()
                    assertEquals(payload.toList(), Files.readAllBytes(materialized).toList())
                }
            } finally {
                session.close()
                assertTrue(sourceClosed)
                Files.list(cacheDirectory).use { files -> assertEquals(0L, files.count()) }
                Files.deleteIfExists(cacheDirectory)
            }
        }
}

private fun fakeBackend(
    id: String,
    tier: DesktopBackendRoutingTier,
    automaticSelection: Boolean = true,
    create: () -> VideoPlayerState,
): DesktopPlaybackBackend =
    object : DesktopPlaybackBackend {
        override val routingTier: DesktopBackendRoutingTier = tier
        override val automaticSelection: Boolean = automaticSelection
        override val info: VideoPlayerBackendInfo =
            VideoPlayerBackendInfo(id, id, PlayerCapabilities(supportsMkv = true))

        override fun inspectAvailability(): DesktopBackendAvailability = DesktopBackendAvailability.Available()

        override fun probe(request: DesktopPlaybackRequest): DesktopBackendProbeResult =
            DesktopBackendProbeResult.Supported(tier)

        override fun createPlayerState(): VideoPlayerState = create()
    }
