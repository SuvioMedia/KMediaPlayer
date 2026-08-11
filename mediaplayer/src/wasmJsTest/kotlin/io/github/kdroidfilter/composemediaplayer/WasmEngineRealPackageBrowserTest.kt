@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import io.github.shusek.kmedia.engine.wasm.PlayerSurface
import io.github.vinceglb.filekit.BrowserFile
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Browser smoke test for the directly linked Kotlin/Wasm player and packaged runtime.
 * The media fixture is a 0.6 s MKV containing VP9 video and two Opus tracks tagged
 * `en` and `pl`; keeping it inline makes media playback independent of HTTP servers.
 */
class WasmEngineRealPackageBrowserTest {
    @Test
    fun realWasmEngineLoadsMp4AndWebM() =
        runTest(timeout = 45.seconds) {
            val state = DefaultVideoPlayerState(VideoPlaybackOptions())
            val surfaceHost = WasmEngineTestSurfaceHost()
            var session: WasmEnginePlaybackSession? = null
            try {
                listOf(
                    WASM_ENGINE_SMOKE_MP4_DATA_URI to "mp4",
                    WASM_ENGINE_SMOKE_WEBM_DATA_URI to "webm",
                ).forEach { (dataUri, expectedFormat) ->
                    session?.destroy()
                    state.openUri(dataUri, InitialPlayerState.PAUSE)
                    val createdSession =
                        WasmEnginePlaybackSession(
                            playerState = state,
                            mediaSessionId = state.mediaSessionId,
                            onSurface = surfaceHost::mount,
                            onVideoRatio = {},
                        )
                    session = createdSession
                    createdSession.load(
                        sourceUri = requireNotNull(state.sourceUri),
                        sourceFile = null,
                        mediaHeaders = emptyMap(),
                        drmConfiguration = null,
                    )

                    assertEquals(null, state.error, "WasmEngine $expectedFormat load error: ${state.error}")
                    assertContains(
                        state.metadata.mimeType
                            .orEmpty()
                            .lowercase(),
                        expectedFormat,
                    )
                    assertTrue(state.duration >= 500.milliseconds)
                    assertTrue(state.availableAudioTracks.isNotEmpty())
                }
            } finally {
                session?.destroy()
                state.dispose()
                surfaceHost.close()
            }
        }

    @Test
    fun realWasmEngineLoadsMkvSwitchesAudioAndSeeks() =
        runTest(timeout = 45.seconds) {
            val state = DefaultVideoPlayerState(VideoPlaybackOptions())
            val surfaceHost = WasmEngineTestSurfaceHost()
            val sourceFile =
                PlatformFile(
                    WebFile.FileWrapper(createDualOpusBrowserFile(DUAL_OPUS_MKV_DATA_URI)),
                )
            val session =
                WasmEnginePlaybackSession(
                    playerState = state,
                    mediaSessionId = 1L,
                    onSurface = surfaceHost::mount,
                    onVideoRatio = {},
                )
            try {
                state.openFile(sourceFile, InitialPlayerState.PAUSE)
                session.load(
                    sourceUri = requireNotNull(state.sourceUri),
                    sourceFile = sourceFile,
                    mediaHeaders = emptyMap(),
                    drmConfiguration = null,
                )

                assertEquals(null, state.error, "WasmEngine load error: ${state.error}")
                assertEquals(listOf("en", "pl"), state.availableAudioTracks.map(AudioTrack::language))
                val polish = state.availableAudioTracks.single { it.language == "pl" }
                assertIs<TrackSelectionResult.Selected>(state.selectAudioTrack(polish))
                var audioSwitchPollAttempt = 0
                while (
                    state.currentAudioTrack?.id != polish.id &&
                    state.error == null &&
                    audioSwitchPollAttempt < AUDIO_SWITCH_POLL_ATTEMPTS
                ) {
                    awaitBrowserDelay(AUDIO_SWITCH_POLL_INTERVAL_MS)
                    audioSwitchPollAttempt += 1
                }
                assertEquals(
                    polish.id,
                    state.currentAudioTrack?.id,
                    "WasmEngine audio selection was not confirmed; error=${state.error}",
                )

                state.seekTo(200.milliseconds)
                session.seekPending()
                var seekPollAttempt = 0
                while (state.isSeeking && seekPollAttempt < SEEK_POLL_ATTEMPTS) {
                    awaitBrowserDelay(SEEK_POLL_INTERVAL_MS)
                    seekPollAttempt += 1
                }
                assertFalse(state.isSeeking, "WasmEngine did not complete the seek.")
                assertTrue(
                    state.preciseCurrentTime >= 150.milliseconds,
                    "WasmEngine seek position was ${state.preciseCurrentTime}; " +
                        "duration=${state.duration}; error=${state.error}.",
                )
                assertTrue(
                    state.duration >= 500.milliseconds,
                    "WasmEngine duration was ${state.duration}; error=${state.error}.",
                )
            } finally {
                session.destroy()
                session.destroy()
                state.dispose()
                surfaceHost.close()
            }
        }

    @Test
    fun realWasmEngineLoadsBrowserBlobUrl() =
        runTest(timeout = 45.seconds) {
            val state = DefaultVideoPlayerState(VideoPlaybackOptions())
            val surfaceHost = WasmEngineTestSurfaceHost()
            val blobUrl = createBrowserObjectUrl(createDualOpusBrowserFile(DUAL_OPUS_MKV_DATA_URI))
            val session =
                WasmEnginePlaybackSession(
                    playerState = state,
                    mediaSessionId = 1L,
                    onSurface = surfaceHost::mount,
                    onVideoRatio = {},
                )
            try {
                state.openUri(blobUrl, InitialPlayerState.PAUSE)
                session.load(
                    sourceUri = blobUrl,
                    sourceFile = null,
                    mediaHeaders = emptyMap(),
                    drmConfiguration = null,
                )

                assertEquals(null, state.error, "WasmEngine Blob load error: ${state.error}")
                assertEquals(listOf("en", "pl"), state.availableAudioTracks.map(AudioTrack::language))
                assertTrue(state.duration >= 500.milliseconds)
            } finally {
                session.destroy()
                state.dispose()
                surfaceHost.close()
                revokeBrowserObjectUrl(blobUrl)
            }
        }

    @Test
    fun realWasmEngineExposesTypedAdvancedControlsWithoutChangingTheMainPlaybackState() =
        runTest(timeout = 45.seconds) {
            val state =
                DefaultVideoPlayerState(
                    VideoPlaybackOptions(
                        webDecoderPreference = WebDecoderPreference.SOFTWARE,
                    ),
                )
            val surfaceHost = WasmEngineTestSurfaceHost()
            val session =
                WasmEnginePlaybackSession(
                    playerState = state,
                    mediaSessionId = 1L,
                    onSurface = surfaceHost::mount,
                    onVideoRatio = {},
                )
            try {
                state.openSource(
                    MediaSourceSpec(WASM_ENGINE_SMOKE_MP4_DATA_URI, "video/mp4"),
                    InitialPlayerState.PAUSE,
                )
                session.load(
                    sourceUri = requireNotNull(state.sourceUri),
                    sourceMimeType = state.sourceMimeType,
                    sourceFile = null,
                    mediaHeaders = emptyMap(),
                    drmConfiguration = null,
                )

                val controls = assertNotNull(state.webMediaAdvancedControls)
                assertTrue(controls === session)
                assertNotNull(controls.surface)
                assertNotNull(controls.renderingDiagnostics)
                controls.setStableVolume(true)
                controls.setAudioOnly(true)
                controls.setAudioOnly(false)
                assertTrue(controls.prefetchSubtitleCues().isEmpty())
                assertEquals(null, state.error)
            } finally {
                session.destroy()
                assertNull(state.webMediaAdvancedControls)
                state.dispose()
                surfaceHost.close()
            }
        }

    @Test
    fun realWasmEngineLoadsHttpRangeNoRangeAndCustomMediaHeaders() =
        runTest(timeout = 45.seconds) {
            val networkFixture = WASM_ENGINE_NETWORK_FIXTURE
            val state = DefaultVideoPlayerState(VideoPlaybackOptions())
            val surfaceHost = WasmEngineTestSurfaceHost()
            var session: WasmEnginePlaybackSession? = null
            try {
                listOf(
                    WASM_ENGINE_RANGE_FIXTURE_URL to
                        mapOf(WASM_ENGINE_MEDIA_HEADER_NAME to WASM_ENGINE_MEDIA_HEADER_VALUE),
                    WASM_ENGINE_NO_RANGE_FIXTURE_URL to emptyMap(),
                ).forEach { (sourceUrl, mediaHeaders) ->
                    session?.destroy()
                    state.openUri(
                        uri = sourceUrl,
                        initializePlayerState = InitialPlayerState.PAUSE,
                        requestHeaders = mediaHeaders,
                    )
                    val createdSession =
                        WasmEnginePlaybackSession(
                            playerState = state,
                            mediaSessionId = state.mediaSessionId,
                            onSurface = surfaceHost::mount,
                            onVideoRatio = {},
                        )
                    session = createdSession
                    createdSession.load(
                        sourceUri = requireNotNull(state.sourceUri),
                        sourceFile = null,
                        mediaHeaders = state.requestHeaders,
                        drmConfiguration = null,
                    )

                    assertEquals(null, state.error, "WasmEngine HTTP load error for $sourceUrl: ${state.error}")
                    assertEquals(listOf("en", "pl"), state.availableAudioTracks.map(AudioTrack::language))
                }

                val stats = readWasmEngineNetworkFixtureStats(networkFixture)
                assertTrue(stats.mediaHeaderSeen)
                assertTrue(stats.rangePartialResponses > 0)
                assertTrue(stats.noRangeIgnoredRequests > 0)
            } finally {
                session?.destroy()
                state.dispose()
                surfaceHost.close()
            }
        }

    @Test
    fun realWasmEngineLoadsHlsAndDash() =
        runTest(timeout = 90.seconds) {
            val state = DefaultVideoPlayerState(VideoPlaybackOptions())
            val surfaceHost = WasmEngineTestSurfaceHost()
            var session: WasmEnginePlaybackSession? = null
            try {
                val adaptiveSources =
                    buildList {
                        add(
                            wasmEngineKarmaFixtureUrl("/__kmp_kmedia_wasm__/hls/index.m3u8") to
                                "application/vnd.apple.mpegurl",
                        )
                        val capabilityProbe = document.createElement("video") as HTMLVideoElement
                        val supportsDashFixture =
                            capabilityProbe.canPlayType("video/mp4; codecs=\"avc1.42c00a\"").toString().isNotEmpty() &&
                                capabilityProbe.canPlayType("audio/mp4; codecs=\"mp4a.40.2\"").toString().isNotEmpty()
                        if (supportsDashFixture) {
                            add(
                                wasmEngineKarmaFixtureUrl("/__kmp_kmedia_wasm__/dash/manifest.mpd") to
                                    "application/dash+xml",
                            )
                        }
                    }
                adaptiveSources.forEach { (sourceUrl, sourceMimeType) ->
                    session?.destroy()
                    state.openSource(
                        MediaSourceSpec(sourceUrl, sourceMimeType),
                        InitialPlayerState.PAUSE,
                    )
                    val createdSession =
                        WasmEnginePlaybackSession(
                            playerState = state,
                            mediaSessionId = state.mediaSessionId,
                            onSurface = surfaceHost::mount,
                            onVideoRatio = {},
                        )
                    session = createdSession
                    val loadResult =
                        runCatching {
                            withContext(Dispatchers.Default.limitedParallelism(1)) {
                                withTimeout(ADAPTIVE_LOAD_TIMEOUT) {
                                    createdSession.load(
                                        sourceUri = requireNotNull(state.sourceUri),
                                        sourceMimeType = state.sourceMimeType,
                                        sourceFile = null,
                                        mediaHeaders = emptyMap(),
                                        drmConfiguration = null,
                                    )
                                }
                            }
                        }

                    assertTrue(
                        loadResult.isSuccess,
                        "WasmEngine adaptive load timed out for $sourceUrl; " +
                            "error=${state.error}; diagnostics=${state.diagnostics}; " +
                            "requests=${readWasmEngineNetworkFixtureStats(
                                WASM_ENGINE_NETWORK_FIXTURE,
                            ).adaptiveRequests}.",
                    )
                    assertEquals(null, state.error, "WasmEngine adaptive load error for $sourceUrl: ${state.error}")
                    assertTrue(
                        state.duration >= 500.milliseconds,
                        "WasmEngine adaptive duration for $sourceUrl was ${state.duration}.",
                    )
                    assertTrue(
                        state.availableHlsQualities.isNotEmpty(),
                        "WasmEngine exposed no adaptive video qualities for $sourceUrl.",
                    )
                }
            } finally {
                session?.destroy()
                state.dispose()
                surfaceHost.close()
            }
        }
}

private class WasmEngineTestSurfaceHost {
    private val container =
        (document.createElement("div") as HTMLElement).also { document.body?.appendChild(it) }

    fun mount(surface: PlayerSurface?) {
        clear()
        when (surface) {
            is PlayerSurface.Canvas -> {
                container.appendChild(surface.element)
                surface.mediaElement?.let { container.appendChild(it) }
            }
            is PlayerSurface.NativeVideo -> container.appendChild(surface.element)
            null -> Unit
        }
    }

    fun close() {
        clear()
        container.remove()
    }

    private fun clear() {
        while (container.firstChild != null) {
            container.removeChild(container.firstChild ?: break)
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun createDualOpusBrowserFile(dataUri: String): BrowserFile =
    js(
        """
        (function() {
            const encoded = String(dataUri).split(",", 2)[1] || "";
            const binary = atob(encoded);
            const bytes = new Uint8Array(binary.length);
            for (let index = 0; index < binary.length; index += 1) {
                bytes[index] = binary.charCodeAt(index);
            }
            return new File([bytes], "dual-opus-en-pl.mkv", { type: "video/x-matroska" });
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun createBrowserObjectUrl(file: BrowserFile): String = js("URL.createObjectURL(file)")

@Suppress("UNUSED_PARAMETER")
private fun revokeBrowserObjectUrl(url: String): Unit = js("URL.revokeObjectURL(url)")

@Suppress("UNUSED_PARAMETER")
private fun wasmEngineKarmaFixtureUrl(path: String): String = js("globalThis.location.origin + path")

private suspend fun awaitBrowserDelay(milliseconds: Int) {
    val deferred = CompletableDeferred<Unit>()
    scheduleBrowserTimeout(milliseconds) { deferred.complete(Unit) }
    deferred.await()
}

@Suppress("UNUSED_PARAMETER")
private fun scheduleBrowserTimeout(
    milliseconds: Int,
    onElapsed: () -> Unit,
): Unit = js("globalThis.setTimeout(onElapsed, milliseconds)")

private data class WasmEngineNetworkFixtureStats(
    val mediaHeaderSeen: Boolean,
    val rangePartialResponses: Int,
    val noRangeIgnoredRequests: Int,
    val adaptiveRequests: String,
)

/*
 * Install before the first test initializes WasmEngine. Shaka captures fetch when its module is
 * evaluated, so a per-test replacement would be too late once another real-module test had loaded WasmEngine.
 */
private val WASM_ENGINE_NETWORK_FIXTURE: JsAny = installWasmEngineNetworkFixtures()

private fun installWasmEngineNetworkFixtures(): JsAny =
    installWasmEngineNetworkFixtures(
        mkvBase64 = DUAL_OPUS_MKV_DATA_URI.substringAfter(','),
        mediaHeaderName = WASM_ENGINE_MEDIA_HEADER_NAME,
        mediaHeaderValue = WASM_ENGINE_MEDIA_HEADER_VALUE,
    )

@Suppress("LongMethod", "UNUSED_PARAMETER")
private fun installWasmEngineNetworkFixtures(
    mkvBase64: String,
    mediaHeaderName: String,
    mediaHeaderValue: String,
): JsAny =
    js(
        """
        (function() {
            const decode = function(encoded) {
                const binary = atob(encoded);
                const bytes = new Uint8Array(binary.length);
                for (let index = 0; index < binary.length; index += 1) {
                    bytes[index] = binary.charCodeAt(index);
                }
                return bytes;
            };
            const routes = {
                "/__kmp_kmedia_wasm__/range/media.mkv": {
                    bytes: decode(mkvBase64),
                    contentType: "video/x-matroska",
                    supportsRange: true
                },
                "/__kmp_kmedia_wasm__/no-range/media.mkv": {
                    bytes: decode(mkvBase64),
                    contentType: "video/x-matroska",
                    supportsRange: false
                }
            };
            const originalFetch = globalThis.fetch;
            const stats = {
                requests: Object.create(null),
                partialResponses: Object.create(null),
                ignoredRangeRequests: Object.create(null),
                adaptiveRequests: Object.create(null),
                mediaHeaderSeen: false,
                wrapper: null,
                originalFetch: originalFetch
            };
            const resolveRequest = function(rawUrl, method, headers) {
                let parsedUrl;
                try {
                    parsedUrl = new URL(rawUrl, globalThis.location && globalThis.location.href);
                } catch (_) {
                    return null;
                }
                const route = routes[parsedUrl.pathname];
                if (!route) return null;

                stats.requests[parsedUrl.pathname] = (stats.requests[parsedUrl.pathname] || 0) + 1;
                if (
                    parsedUrl.pathname === "/__kmp_kmedia_wasm__/range/media.mkv" &&
                    headers.get(mediaHeaderName) === mediaHeaderValue
                ) {
                    stats.mediaHeaderSeen = true;
                }

                const responseHeaders = new Headers({
                    "Content-Type": route.contentType,
                    "Content-Length": String(route.bytes.byteLength),
                    "Accept-Ranges": route.supportsRange ? "bytes" : "none"
                });
                if (method === "HEAD") {
                    return {
                        status: 200,
                        body: null,
                        headers: responseHeaders,
                        url: parsedUrl.href
                    };
                }

                const rangeHeader = headers.get("Range");
                if (rangeHeader && route.supportsRange) {
                    const match = /^bytes=(\d+)-(\d*)$/i.exec(rangeHeader.trim());
                    if (match) {
                        const start = Number(match[1]);
                        const requestedEnd = match[2] ? Number(match[2]) : route.bytes.byteLength - 1;
                        if (start >= route.bytes.byteLength) {
                            responseHeaders.set("Content-Range", "bytes */" + route.bytes.byteLength);
                            responseHeaders.set("Content-Length", "0");
                            return {
                                status: 416,
                                body: null,
                                headers: responseHeaders,
                                url: parsedUrl.href
                            };
                        }
                        const end = Math.min(requestedEnd, route.bytes.byteLength - 1);
                        const body = route.bytes.slice(start, end + 1);
                        responseHeaders.set(
                            "Content-Range",
                            "bytes " + start + "-" + end + "/" + route.bytes.byteLength
                        );
                        responseHeaders.set("Content-Length", String(body.byteLength));
                        stats.partialResponses[parsedUrl.pathname] =
                            (stats.partialResponses[parsedUrl.pathname] || 0) + 1;
                        return {
                            status: 206,
                            body: body,
                            headers: responseHeaders,
                            url: parsedUrl.href
                        };
                    }
                }
                if (rangeHeader && !route.supportsRange) {
                    stats.ignoredRangeRequests[parsedUrl.pathname] =
                        (stats.ignoredRangeRequests[parsedUrl.pathname] || 0) + 1;
                }
                return {
                    status: 200,
                    body: route.bytes.slice(),
                    headers: responseHeaders,
                    url: parsedUrl.href
                };
            };
            const wrapper = function(input, init) {
                const rawUrl =
                    typeof input === "string" ? input :
                    input && typeof input.url === "string" ? input.url :
                    String(input);
                let parsedUrl;
                try {
                    parsedUrl = new URL(rawUrl, globalThis.location && globalThis.location.href);
                } catch (_) {
                    return originalFetch(input, init);
                }
                const route = routes[parsedUrl.pathname];
                if (!route) {
                    if (
                        parsedUrl.pathname.startsWith("/__kmp_kmedia_wasm__/hls/") ||
                        parsedUrl.pathname.startsWith("/__kmp_kmedia_wasm__/dash/")
                    ) {
                        const row = stats.adaptiveRequests[parsedUrl.pathname] || {
                            count: 0,
                            status: "pending"
                        };
                        row.count += 1;
                        stats.adaptiveRequests[parsedUrl.pathname] = row;
                        return Promise.resolve(originalFetch(input, init)).then(
                            function(response) {
                                row.status = String(response.status);
                                return response;
                            },
                            function(error) {
                                row.status = "failed";
                                throw error;
                            }
                        );
                    }
                    return originalFetch(input, init);
                }

                const headers = new Headers(
                    input && typeof input === "object" && input.headers ? input.headers : undefined
                );
                if (init && init.headers) {
                    new Headers(init.headers).forEach(function(value, name) {
                        headers.set(name, value);
                    });
                }
                const method = String(
                    init && init.method ||
                    input && typeof input === "object" && input.method ||
                    "GET"
                ).toUpperCase();
                const resolved = resolveRequest(rawUrl, method, headers);
                return Promise.resolve(new Response(resolved.body, {
                    status: resolved.status,
                    headers: resolved.headers
                }));
            };

            stats.wrapper = wrapper;
            globalThis.fetch = wrapper;
            return stats;
        })()
        """,
    )

@Suppress("UNUSED_PARAMETER")
private fun readWasmEngineNetworkFixtureStats(fixture: JsAny): WasmEngineNetworkFixtureStats {
    val fields: List<String> =
        readWasmEngineNetworkFixtureStatsRow(fixture)
            .split('|')
    return WasmEngineNetworkFixtureStats(
        mediaHeaderSeen = fields.getOrNull(0) == "1",
        rangePartialResponses = fields.getOrNull(1)?.toIntOrNull() ?: 0,
        noRangeIgnoredRequests = fields.getOrNull(2)?.toIntOrNull() ?: 0,
        adaptiveRequests = fields.getOrNull(3).orEmpty(),
    )
}

@Suppress("UNUSED_PARAMETER")
private fun readWasmEngineNetworkFixtureStatsRow(fixture: JsAny): String =
    js(
        """
        [
            fixture.mediaHeaderSeen ? "1" : "0",
            fixture.partialResponses["/__kmp_kmedia_wasm__/range/media.mkv"] || 0,
            fixture.ignoredRangeRequests["/__kmp_kmedia_wasm__/no-range/media.mkv"] || 0,
            Object.keys(fixture.adaptiveRequests || {}).sort().map(function(path) {
                const row = fixture.adaptiveRequests[path];
                return path + ":" + row.count + ":" + row.status;
            }).join(",")
        ].join("|")
        """,
    )

private const val SEEK_POLL_ATTEMPTS = 200
private const val SEEK_POLL_INTERVAL_MS = 25
private const val AUDIO_SWITCH_POLL_ATTEMPTS = 200
private const val AUDIO_SWITCH_POLL_INTERVAL_MS = 25
private val ADAPTIVE_LOAD_TIMEOUT = 30.seconds
private const val WASM_ENGINE_FIXTURE_ORIGIN = "https://kmp-wasm-engine-fixture.invalid"
private const val WASM_ENGINE_FIXTURE_PATH = "/__kmp_kmedia_wasm__"
private const val WASM_ENGINE_RANGE_FIXTURE_URL =
    "$WASM_ENGINE_FIXTURE_ORIGIN$WASM_ENGINE_FIXTURE_PATH/range/media.mkv"
private const val WASM_ENGINE_NO_RANGE_FIXTURE_URL =
    "$WASM_ENGINE_FIXTURE_ORIGIN$WASM_ENGINE_FIXTURE_PATH/no-range/media.mkv"
private const val WASM_ENGINE_MEDIA_HEADER_NAME = "X-KMP-Media"
private const val WASM_ENGINE_MEDIA_HEADER_VALUE = "fixture-header-value"

private const val DUAL_OPUS_MKV_DATA_URI =
    "data:video/x-matroska;base64," +
        "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"
