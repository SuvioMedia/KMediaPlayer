package io.github.kdroidfilter.composemediaplayer.desktop

import io.github.kdroidfilter.composemediaplayer.MediaSourceSpec
import io.github.kdroidfilter.composemediaplayer.PlaybackEvent
import io.github.kdroidfilter.composemediaplayer.PlayerCapabilities
import io.github.kdroidfilter.composemediaplayer.PreviewableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerBackendInfo
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class DesktopPlaybackSessionTest {
    @Test
    fun attachingReplacementSurfaceNeverDisposesRetiredPlayerOnCallerThread() =
        runTest {
            val disposeStarted = CountDownLatch(1)
            val allowDispose = CountDownLatch(1)
            val disposeFinished = CountDownLatch(1)
            val firstDelegate = PreviewableVideoPlayerState(isPlaying = false)
            val first =
                object : VideoPlayerState by firstDelegate {
                    override fun dispose() {
                        disposeStarted.countDown()
                        allowDispose.await(5, TimeUnit.SECONDS)
                        disposeFinished.countDown()
                    }
                }
            val replacement = PreviewableVideoPlayerState(isPlaying = false)
            val firstBackend = fakeBackend("platform", DesktopBackendRoutingTier.PLATFORM_DIRECT) { first }
            val replacementBackend = fakeBackend("mpv", DesktopBackendRoutingTier.MPV_NATIVE) { replacement }
            val session = DesktopPlaybackSession(listOf(firstBackend, replacementBackend), readyTimeout = 1.seconds)

            try {
                session.open(DesktopPlaybackRequest(MediaSourceSpec("file:///first.mp4")), "platform")
                session.switchBackend("mpv")

                val elapsed = measureTime { session.notifySurfaceAttached(replacement) }

                assertTrue(elapsed < 500.milliseconds)
                assertTrue(disposeStarted.await(1, TimeUnit.SECONDS))
                assertEquals(1L, disposeFinished.count)
            } finally {
                allowDispose.countDown()
                assertTrue(disposeFinished.await(1, TimeUnit.SECONDS))
                session.close()
            }
        }

    @Test
    fun replacingLibVlcSourceReusesTheActivePlayer() =
        runTest {
            val player = SourceReplacingVideoPlayerState()
            var createCount = 0
            val backend =
                fakeBackend("libvlc", DesktopBackendRoutingTier.LIBVLC_NATIVE) {
                    createCount += 1
                    player
                }
            val session = DesktopPlaybackSession(listOf(backend), readyTimeout = 1.seconds)

            try {
                val first = session.open(DesktopPlaybackRequest(MediaSourceSpec("file:///first.mp4")), "libvlc")
                val second = session.open(DesktopPlaybackRequest(MediaSourceSpec("file:///second.mp4")), "libvlc")

                assertSame(first, second)
                assertSame(player, second)
                assertEquals(1, createCount)
                assertEquals(listOf("file:///first.mp4", "file:///second.mp4"), player.openedUris)
                assertEquals(2L, player.mediaSessionId)
                assertEquals(0, player.disposeCount)
            } finally {
                session.close()
            }
        }

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
    fun unsupportedExplicitSwitchReportsFailureAndRetainsCurrentPlayer() =
        runTest {
            val current = PreviewableVideoPlayerState(isPlaying = true)
            val platform = fakeBackend("platform", DesktopBackendRoutingTier.PLATFORM_DIRECT) { current }
            val unsupported =
                fakeBackend(
                    id = "mpv",
                    tier = DesktopBackendRoutingTier.MPV_NATIVE,
                    sourceProbe = { DesktopBackendProbeResult.Unsupported("Unsupported test source.") },
                ) {
                    error("An unsupported backend must not be created.")
                }
            val session = DesktopPlaybackSession(listOf(platform, unsupported), readyTimeout = 1.seconds)

            try {
                session.open(DesktopPlaybackRequest(MediaSourceSpec("file:///movie.avi")), "platform")

                assertFailsWith<DesktopPlaybackOpenException> { session.switchBackend("mpv") }
                assertSame(current, session.playerState.value)
                assertTrue(current.isPlaying)
                val failure = assertIs<DesktopPlaybackSessionState.Failed>(session.state.value)
                assertEquals("mpv", failure.backendId)
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

    @Test
    fun bundledMpvRoutesRemoteHlsThroughAnOwnedLoopbackProxy() =
        runTest {
            var proxyOpened = false
            var proxyClosed = false
            val mpv =
                fakeBackend(
                    id = "mpv",
                    tier = DesktopBackendRoutingTier.MPV_NATIVE,
                    sourceProbe = { DesktopBackendProbeResult.Unsupported("Direct remote input is disabled.") },
                ) {
                    PreviewableVideoPlayerState(isPlaying = false)
                }
            val proxyFactory =
                JvmHlsMediaProxyFactory { request ->
                    assertTrue(request.source.uri.endsWith("master.m3u8"))
                    proxyOpened = true
                    object : JvmHlsMediaProxy {
                        override val localUri: String = "http://127.0.0.1:49152/hls/1"

                        override fun close() {
                            proxyClosed = true
                        }
                    }
                }
            val session =
                DesktopPlaybackSession(
                    backends = listOf(mpv),
                    readyTimeout = 1.seconds,
                    hlsMediaProxyFactory = proxyFactory,
                )

            try {
                session.open(
                    DesktopPlaybackRequest(
                        source = MediaSourceSpec("https://media.invalid/master.m3u8"),
                        requestHeaders = mapOf("Authorization" to "test-only-placeholder"),
                    ),
                    backendId = "mpv",
                )
                assertTrue(proxyOpened)
                assertIs<DesktopPlaybackSessionState.Ready>(session.state.value)
            } finally {
                session.close()
                assertTrue(proxyClosed)
            }
        }

    @Test
    fun bundledMpvRoutesProgressiveRemoteMediaThroughLoopbackWithoutMaterializing() =
        runTest {
            var proxyOpened = false
            var proxyClosed = false
            var dataSourceOpened = false
            val mpv =
                fakeBackend(
                    id = "mpv",
                    tier = DesktopBackendRoutingTier.MPV_NATIVE,
                    sourceProbe = { DesktopBackendProbeResult.Unsupported("Direct remote input is disabled.") },
                ) {
                    PreviewableVideoPlayerState(isPlaying = false)
                }
            val proxyFactory =
                JvmHlsMediaProxyFactory { request ->
                    assertTrue(request.source.uri.endsWith("movie.mp4"))
                    proxyOpened = true
                    object : JvmHlsMediaProxy {
                        override val localUri: String = "http://127.0.0.1:49152/media/1"

                        override fun close() {
                            proxyClosed = true
                        }
                    }
                }
            val session =
                DesktopPlaybackSession(
                    backends = listOf(mpv),
                    readyTimeout = 1.seconds,
                    seekableMediaDataSourceFactory = JvmSeekableMediaDataSourceFactory {
                        dataSourceOpened = true
                        error("The progressive source must not be materialized when a proxy is available.")
                    },
                    hlsMediaProxyFactory = proxyFactory,
                )

            try {
                session.open(
                    DesktopPlaybackRequest(
                        source = MediaSourceSpec("https://media.invalid/movie.mp4"),
                        requestHeaders = mapOf("Authorization" to "test-only-placeholder"),
                    ),
                    backendId = "mpv",
                )
                assertTrue(proxyOpened)
                assertTrue(!dataSourceOpened)
                assertIs<DesktopPlaybackSessionState.Ready>(session.state.value)
            } finally {
                session.close()
                assertTrue(proxyClosed)
            }
        }
}

private fun fakeBackend(
    id: String,
    tier: DesktopBackendRoutingTier,
    automaticSelection: Boolean = true,
    sourceProbe: (DesktopPlaybackRequest) -> DesktopBackendProbeResult = {
        DesktopBackendProbeResult.Supported(tier)
    },
    create: () -> VideoPlayerState,
): DesktopPlaybackBackend =
    object : DesktopPlaybackBackend {
        override val routingTier: DesktopBackendRoutingTier = tier
        override val automaticSelection: Boolean = automaticSelection
        override val info: VideoPlayerBackendInfo =
            VideoPlayerBackendInfo(id, id, PlayerCapabilities(supportsMkv = true))

        override fun inspectAvailability(): DesktopBackendAvailability = DesktopBackendAvailability.Available()

        override fun probe(request: DesktopPlaybackRequest): DesktopBackendProbeResult = sourceProbe(request)

        override fun createPlayerState(): VideoPlayerState = create()
    }

private class SourceReplacingVideoPlayerState :
    VideoPlayerState by PreviewableVideoPlayerState(isPlaying = false) {
    private val mutablePlaybackEvents = MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 8)
    private var mutableMediaSessionId = 0L

    val openedUris = mutableListOf<String>()
    var disposeCount = 0
        private set

    override val mediaSessionId: Long
        get() = mutableMediaSessionId
    override val playbackEvents: SharedFlow<PlaybackEvent>
        get() = mutablePlaybackEvents

    override fun openSource(
        source: MediaSourceSpec,
        initializePlayerState: io.github.kdroidfilter.composemediaplayer.InitialPlayerState,
        requestHeaders: Map<String, String>,
    ) {
        openedUris += source.uri
        mutableMediaSessionId += 1L
        mutablePlaybackEvents.tryEmit(
            PlaybackEvent.SourceLoaded(
                mediaSessionId = mutableMediaSessionId,
                sampledAtMs = 0L,
                duration = 10.seconds,
            ),
        )
    }

    override fun dispose() {
        disposeCount += 1
    }
}
