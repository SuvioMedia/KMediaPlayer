@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.js
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MoviPlaybackAdapterTest {
    @Test
    fun defaultWebRouteUsesMovi() {
        assertEquals(WebPlaybackRoute.MOVI, VideoPlaybackOptions().webPlaybackDecision().route)
    }

    @Test
    fun explicitLegacyAlwaysUsesLegacy() {
        val options =
            VideoPlaybackOptions(
                webPlaybackEngine = WebPlaybackEngine.LEGACY,
                dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR,
                webDrmConfiguration = WebDrmConfiguration("https://license.example.test"),
            )

        assertEquals(WebPlaybackRoute.LEGACY, options.webPlaybackDecision().route)
    }

    @Test
    fun legacyRejectsAdaptiveStreamingManifests() {
        val options = VideoPlaybackOptions(webPlaybackEngine = WebPlaybackEngine.LEGACY)

        listOf(
            "https://media.example.test/master.m3u8",
            "https://media.example.test/manifest.mpd?token=redacted",
            "https://media.example.test/channel.ism/Manifest",
        ).forEach { sourceUri ->
            val decision = options.webPlaybackDecision(sourceUri = sourceUri)

            assertEquals(WebPlaybackRoute.REJECTED, decision.route)
            assertIs<VideoPlayerError.SourceError>(decision.error)
        }
    }

    @Test
    fun strictColorPolicyRejectsAdaptiveStreamingInsteadOfUsingLegacy() {
        val decision =
            VideoPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR)
                .webPlaybackDecision(sourceUri = "https://media.example.test/manifest.mpd")

        assertEquals(WebPlaybackRoute.REJECTED, decision.route)
        assertIs<VideoPlayerError.ColorPipelineError>(decision.error)
    }

    @Test
    fun openingAdaptiveStreamingWithLegacyFailsBeforeSurfaceInitialization() {
        val state =
            DefaultVideoPlayerState(
                VideoPlaybackOptions(webPlaybackEngine = WebPlaybackEngine.LEGACY),
            )
        try {
            state.openUri("https://media.example.test/manifest.mpd")

            assertEquals(WebPlaybackRoute.REJECTED, state.webPlaybackDecision.route)
            assertIs<VideoPlayerError.SourceError>(state.error)
            assertFalse(state.isLoading)
            assertFalse(state.isPlaying)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun strictClearColorPoliciesUseLegacy() {
        assertEquals(
            WebPlaybackRoute.LEGACY,
            VideoPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR)
                .webPlaybackDecision()
                .route,
        )
        assertEquals(
            WebPlaybackRoute.LEGACY,
            VideoPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR)
                .webPlaybackDecision()
                .route,
        )
        assertEquals(
            WebPlaybackRoute.LEGACY,
            VideoPlaybackOptions(dolbyVisionPolicy = DolbyVisionPolicy.PREFER_HDR10_BASE_LAYER)
                .webPlaybackDecision()
                .route,
        )
        assertEquals(
            WebPlaybackRoute.MOVI,
            VideoPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.PREFER_HDR)
                .webPlaybackDecision()
                .route,
        )
    }

    @Test
    fun drmUsesMoviAndFailsClosedForStrictColorOrProjection() {
        val drm = WebDrmConfiguration("https://license.example.test")
        assertEquals(
            WebPlaybackRoute.MOVI_DRM,
            VideoPlaybackOptions(webDrmConfiguration = drm).webPlaybackDecision().route,
        )

        val strictDecision =
            VideoPlaybackOptions(
                dynamicRangePolicy = DynamicRangePolicy.REQUIRE_HDR,
                webDrmConfiguration = drm,
            ).webPlaybackDecision()
        assertEquals(WebPlaybackRoute.REJECTED, strictDecision.route)
        assertIs<VideoPlayerError.DrmError>(strictDecision.error)

        val projectionDecision =
            VideoPlaybackOptions(
                webDrmConfiguration = drm,
                projection =
                    VideoProjectionSettings(
                        projectionType = VideoProjectionType.Equirect360,
                    ),
            ).webPlaybackDecision()
        assertEquals(WebPlaybackRoute.REJECTED, projectionDecision.route)
        assertIs<VideoPlayerError.DrmError>(projectionDecision.error)
    }

    @Test
    fun drmConfigurationAndErrorsRedactRuntimeValues() {
        val configuration =
            WebDrmConfiguration(
                licenseUrl = "https://license.example.test/private",
                licenseRequestHeaders = mapOf("Authorization" to "Bearer private-token"),
            )

        val rendered = configuration.toString()
        assertFalse("license.example.test" in rendered)
        assertFalse("Authorization" in rendered)
        assertFalse("private-token" in rendered)
        val renderedOptions = VideoPlaybackOptions(webDrmConfiguration = configuration).toString()
        assertFalse("license.example.test" in renderedOptions)
        assertFalse("Authorization" in renderedOptions)
        assertFalse("private-token" in renderedOptions)

        val redacted =
            redactMoviError(
                "License https://license.example.test/private rejected Authorization: Bearer private-token",
                configuration,
            )
        assertFalse("license.example.test" in redacted)
        assertFalse("Authorization" in redacted)
        assertFalse("private-token" in redacted)
        assertContains(redacted, "license details were redacted")

        val mediaRedacted =
            redactMoviError(
                message = "Request X-Media-Token: media-private-token failed",
                drmConfiguration = null,
                mediaRequestHeaders = mapOf("X-Media-Token" to "media-private-token"),
            )
        assertFalse("X-Media-Token" in mediaRedacted)
        assertFalse("media-private-token" in mediaRedacted)
        assertContains(mediaRedacted, "media request details were redacted")
    }

    @Test
    @OptIn(ExperimentalWasmJsInterop::class)
    fun fakeMoviReceivesSeparateMediaAndLicenseHeaders() {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val module = createFakeMoviModule()
        val player =
            createMoviPlayer(
                module = module,
                canvas = canvas,
                sourceUri = "https://media.example.test/manifest.mpd",
                browserFile = null,
                mediaHeadersJson = """{"X-Media":"media-value"}""",
                drmEnabled = true,
                licenseUrl = "https://license.example.test/widevine",
                licenseHeadersJson = """{"Authorization":"license-value"}""",
            )

        assertEquals(
            listOf(
                "url",
                "https://media.example.test/manifest.mpd",
                "media-value",
                "",
                "https://license.example.test/widevine",
                "",
                "license-value",
                "https://license.example.test/widevine",
                "license-value",
                "",
                "0",
            ).joinToString("|"),
            readFakeMoviConfiguration(player),
        )
        assertEquals(0, readFakeMoviLogLevel(module))
    }

    @Test
    @OptIn(ExperimentalWasmJsInterop::class)
    fun fakeMoviReceivesBrowserFileDirectly() {
        val browserFile = createFakeBrowserFile()
        val player =
            createMoviPlayer(
                module = createFakeMoviModule(),
                canvas = document.createElement("canvas") as HTMLCanvasElement,
                sourceUri = "blob:https://media.example.test/ignored",
                browserFile = browserFile,
                mediaHeadersJson = "{}",
                drmEnabled = false,
                licenseUrl = null,
                licenseHeadersJson = "",
            )

        assertEquals("file|true|", readFakeMoviFileConfiguration(player, browserFile))
    }

    @Test
    fun snapshotMapsTracksMetadataChaptersAndSourceColorWithoutClaimingOutput() {
        val snapshot =
            parseMoviSnapshotRows(
                listOf(
                    "M|matroska%2Cwebm|12.5|2400000|Two%20languages",
                    "A|1|English|en|2|48000|128000|true|true",
                    "A|2|Polski|pl|2|48000|128000|false|false",
                    "S|3|English%20CC|en|true|s_text%2Fass|text",
                    "V|-1|av1|1920|1080|24|2100000|yuv420p10le|bt2020|smpte2084|bt2020nc|tv|true|true",
                    "V|7|av1|1920|1080|24|2100000|yuv420p10le|bt2020|smpte2084|bt2020nc|tv|true|false",
                    "C|0|5|Intro|pl|true|chapter-1",
                    "C|5|12.5|Main|en|false|chapter-2",
                    "D|progressive-wasm|webcodecs|canvas|matroska",
                ).joinToString("\n"),
            )
        val state = createVideoPlayerState() as DefaultVideoPlayerState
        try {
            state.openUri("https://example.test/movie.mkv")
            state.applyMoviSnapshot(snapshot)

            assertEquals(listOf("en", "pl"), state.availableAudioTracks.map(AudioTrack::language))
            assertEquals("${MOVI_AUDIO_TRACK_ID_PREFIX}1", state.currentAudioTrack?.id)
            assertEquals(1, state.availableSubtitleTracks.count(SubtitleTrack::isEmbedded))
            assertEquals("${MOVI_SUBTITLE_TRACK_ID_PREFIX}3", state.currentSubtitleTrack?.src)
            assertEquals(SubtitleFormat.ASS, state.currentSubtitleTrack?.format)
            assertEquals(2, state.chapters.size)
            assertEquals("pl", state.chapters.first().language)
            assertTrue(state.chapters.first().isHidden)
            assertEquals(listOf("$MOVI_VIDEO_TRACK_ID_PREFIX${7}"), state.availableHlsQualities.map { it.id })
            assertEquals(HlsQualityMode.AUTO, state.hlsQualityMode)
            assertNull(state.currentHlsQuality)
            assertEquals(1920, state.metadata.width)
            assertEquals(1080, state.metadata.height)
            assertEquals(MOVI_RENDERING_BACKEND, state.renderingInfo.backend)
            assertContains(state.renderingInfo.videoDecoder.orEmpty(), "webcodecs")
            assertEquals(VideoDynamicRange.HDR10, state.colorPipelineStatus.value.source.dynamicRange)
            assertNull(state.colorPipelineStatus.value.decoderName)
            assertFalse(state.colorPipelineStatus.value.decoderCapabilities.isKnown)
            assertEquals(VideoSurfaceKind.UNKNOWN, state.colorPipelineStatus.value.surface)
            assertEquals(VideoDynamicRange.UNKNOWN, state.colorPipelineStatus.value.outputDynamicRange)
        } finally {
            state.dispose()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun audioStateAndEventChangeOnlyAfterMoviAcceptsSelection() =
        runTest {
            val state = createVideoPlayerState() as DefaultVideoPlayerState
            val events = mutableListOf<PlaybackEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                state.playbackEvents.collect { events += it }
            }
            state.openUri("https://example.test/movie.mkv")
            state.applyMoviSnapshot(twoAudioTrackSnapshot())
            runCurrent()
            events.clear()

            state.applyAudioTrackSelectionCallback = {
                TrackSelectionResult.Failed("fake Movi rejected the switch")
            }
            val failed = state.selectAudioTrack("${MOVI_AUDIO_TRACK_ID_PREFIX}2")
            runCurrent()

            assertIs<TrackSelectionResult.Failed>(failed)
            assertEquals("${MOVI_AUDIO_TRACK_ID_PREFIX}1", state.currentAudioTrack?.id)
            assertTrue(events.none { it is PlaybackEvent.TrackChanged })

            state.applyAudioTrackSelectionCallback = { requested ->
                requested?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Auto
            }
            val selected = state.selectAudioTrack("${MOVI_AUDIO_TRACK_ID_PREFIX}2")
            runCurrent()

            assertEquals(TrackSelectionResult.Selected("${MOVI_AUDIO_TRACK_ID_PREFIX}2"), selected)
            assertEquals("${MOVI_AUDIO_TRACK_ID_PREFIX}2", state.currentAudioTrack?.id)
            assertEquals(
                listOf("${MOVI_AUDIO_TRACK_ID_PREFIX}2"),
                events.filterIsInstance<PlaybackEvent.TrackChanged>().map(PlaybackEvent.TrackChanged::trackId),
            )

            val automatic = state.selectAudioTrack(null as AudioTrack?)
            assertEquals(TrackSelectionResult.Auto, automatic)
            assertEquals("${MOVI_AUDIO_TRACK_ID_PREFIX}1", state.currentAudioTrack?.id)
            state.dispose()
        }

    @Test
    fun rapidAcceptedAudioSwitchesKeepTheLastTrack() {
        val state = createVideoPlayerState() as DefaultVideoPlayerState
        try {
            state.openUri("https://example.test/movie.mkv")
            state.applyMoviSnapshot(twoAudioTrackSnapshot())
            val applied = mutableListOf<String?>()
            state.applyAudioTrackSelectionCallback = { requested ->
                applied += requested?.id
                requested?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Auto
            }

            state.selectAudioTrack("${MOVI_AUDIO_TRACK_ID_PREFIX}2")
            state.selectAudioTrack("${MOVI_AUDIO_TRACK_ID_PREFIX}1")
            state.selectAudioTrack("${MOVI_AUDIO_TRACK_ID_PREFIX}2")

            assertEquals(
                listOf<String?>(
                    "${MOVI_AUDIO_TRACK_ID_PREFIX}2",
                    "${MOVI_AUDIO_TRACK_ID_PREFIX}1",
                    "${MOVI_AUDIO_TRACK_ID_PREFIX}2",
                ),
                applied,
            )
            assertEquals("${MOVI_AUDIO_TRACK_ID_PREFIX}2", state.currentAudioTrack?.id)
        } finally {
            state.dispose()
        }
    }

    @Test
    @OptIn(ExperimentalWasmJsInterop::class)
    fun transactionalMoviAudioSelectionReportsDefinitiveSuccessAndFailure() =
        runTest {
            val player = createTransactionalTrackPlayer()
            val accepted = CompletableDeferred<Pair<String, String?>>()
            selectMoviAudioTrack(
                player = player,
                trackId = 2,
                automatic = false,
            ) { status, message -> accepted.complete(status to message) }

            assertEquals("success" to null, accepted.await())
            assertEquals("2", readTransactionalMoviActiveAudio(player))

            val rejected = CompletableDeferred<Pair<String, String?>>()
            selectMoviAudioTrack(
                player = player,
                trackId = 99,
                automatic = false,
            ) { status, message -> rejected.complete(status to message) }

            val failure = rejected.await()
            assertEquals("failed", failure.first)
            assertContains(failure.second.orEmpty(), "rejected")
            assertEquals("2", readTransactionalMoviActiveAudio(player))
        }

    @Test
    @OptIn(ExperimentalWasmJsInterop::class)
    fun embeddedSubtitleSelectionUsesMoviContractWithoutExportingABlob() =
        runTest {
            val player = createTransactionalTrackPlayer()
            val selected = CompletableDeferred<Pair<String, String?>>()
            selectMoviSubtitleTrack(
                player = player,
                trackId = 3,
            ) { status, message -> selected.complete(status to message) }

            assertEquals("success" to null, selected.await())
            assertEquals("3", readTransactionalMoviActiveSubtitle(player))
        }

    @Test
    @OptIn(ExperimentalWasmJsInterop::class)
    fun pluggableSubtitleRendererIsMountedOnTheMoviOverlayWithInitialDelay() {
        val canvas = createMountedMoviCanvas()
        val player = createSubtitleSurfacePlayer()
        val renderer = createSubtitleSurfaceRenderer()
        try {
            attachMoviSubtitleSurface(
                player = player,
                canvas = canvas,
                renderer = renderer,
                subtitleDelaySeconds = 1.25,
            )

            assertEquals(
                "true|true|1.25|1.25",
                readSubtitleSurfaceState(player, renderer),
            )
        } finally {
            releaseSubtitleSurface(player)
            canvas.parentElement?.remove()
        }
    }

    @Test
    @OptIn(ExperimentalWasmJsInterop::class)
    fun selectingExternalAssDisablesMoviEmbeddedSubtitlesForTheJassubOverlay() =
        runTest {
            val module = createFakeMoviModule()
            val state = createVideoPlayerState() as DefaultVideoPlayerState
            val canvas = createMountedMoviCanvas()
            var session: MoviPlaybackSession? = null
            try {
                state.openUri("https://media.example.test/movie.mkv", InitialPlayerState.PAUSE)
                val createdSession =
                    MoviPlaybackSession(
                        playerState = state,
                        mediaSessionId = state.mediaSessionId,
                        canvas = canvas,
                        onNativeVideoElement = {},
                        onVideoRatio = {},
                        moduleLoader = { module },
                    )
                session = createdSession
                createdSession.load(
                    sourceUri = requireNotNull(state.sourceUri),
                    sourceFile = null,
                    mediaHeaders = emptyMap(),
                    drmConfiguration = null,
                )
                val externalAss =
                    SubtitleTrack(
                        label = "External ASS",
                        language = "en",
                        src = "https://media.example.test/subtitles.ass",
                    )
                state.addSubtitleTrack(externalAss)

                assertEquals(TrackSelectionResult.Selected(externalAss.id), state.selectSubtitleTrack(externalAss))
                assertEquals("null", readFakeMoviSubtitleSelections(fakeMoviPlayerAt(module, 0)))
                assertEquals(externalAss, state.currentSubtitleTrack)
            } finally {
                session?.destroy()
                state.dispose()
                canvas.parentElement?.remove()
            }
        }

    @Test
    @OptIn(ExperimentalWasmJsInterop::class)
    fun fakeMoviForwardsEverySubscribedEvent() {
        val player =
            createMoviPlayer(
                module = createFakeMoviModule(),
                canvas = document.createElement("canvas") as HTMLCanvasElement,
                sourceUri = "https://media.example.test/movie.mkv",
                browserFile = null,
                mediaHeadersJson = "{}",
                drmEnabled = false,
                licenseUrl = null,
                licenseHeadersJson = "",
            )
        var playbackState: String? = null
        var currentTime: Double? = null
        var duration: Double? = null
        var tracksChanged = false
        var error: String? = null
        var seeking = false
        var seeked = false
        var bufferedRows: String? = null
        var ended = false

        bindMoviPlayerEvents(
            player = player,
            onStateChanged = { playbackState = it },
            onTimeChanged = { currentTime = it },
            onDurationChanged = { duration = it },
            onTracksChanged = { tracksChanged = true },
            onError = { error = it },
            onSeeking = { seeking = true },
            onSeeked = { seeked = true },
            onBufferChanged = { bufferedRows = it },
            onEnded = { ended = true },
        )

        emitFakeMoviStringEvent(player, "stateChange", "buffering")
        emitFakeMoviNumberEvent(player, "timeUpdate", 2.5)
        emitFakeMoviNumberEvent(player, "durationChange", 12.0)
        emitFakeMoviEvent(player, "tracksChange")
        emitFakeMoviError(player, "decoder failed")
        emitFakeMoviEvent(player, "seeking")
        emitFakeMoviEvent(player, "seeked")
        emitFakeMoviBufferUpdate(player)
        emitFakeMoviEvent(player, "ended")

        assertEquals("buffering", playbackState)
        assertEquals(2.5, currentTime)
        assertEquals(12.0, duration)
        assertTrue(tracksChanged)
        assertEquals("decoder failed", error)
        assertTrue(seeking)
        assertTrue(seeked)
        assertEquals("1|2\n3|4.5", bufferedRows)
        assertTrue(ended)
    }

    @Test
    @OptIn(ExperimentalWasmJsInterop::class)
    fun staleSessionEventsAreIgnoredAndReplacementDestroysExactlyOnce() =
        runTest {
            val module = createFakeMoviModule()
            val state = createVideoPlayerState() as DefaultVideoPlayerState
            val canvas = createMountedMoviCanvas()
            val firstSessionId: Long
            val firstSession: MoviPlaybackSession
            try {
                state.openUri("https://media.example.test/first.mkv", InitialPlayerState.PAUSE)
                firstSessionId = state.mediaSessionId
                firstSession =
                    MoviPlaybackSession(
                        playerState = state,
                        mediaSessionId = firstSessionId,
                        canvas = canvas,
                        onNativeVideoElement = {},
                        onVideoRatio = {},
                        moduleLoader = { module },
                    )
                firstSession.load(
                    sourceUri = requireNotNull(state.sourceUri),
                    sourceFile = null,
                    mediaHeaders = emptyMap(),
                    drmConfiguration = null,
                )
                val firstPlayer = fakeMoviPlayerAt(module, 0)

                emitFakeMoviStringEvent(firstPlayer, "stateChange", "loading")
                assertTrue(state.isLoading)
                emitFakeMoviStringEvent(firstPlayer, "stateChange", "buffering")
                assertTrue(state.isLoading)
                emitFakeMoviStringEvent(firstPlayer, "stateChange", "ready")
                assertFalse(state.isLoading)
                emitFakeMoviStringEvent(firstPlayer, "stateChange", "playing")
                assertTrue(state.isPlaying)
                emitFakeMoviStringEvent(firstPlayer, "stateChange", "paused")
                assertFalse(state.isPlaying)
                emitFakeMoviStringEvent(firstPlayer, "stateChange", "seeking")
                assertTrue(state.isSeeking)
                emitFakeMoviEvent(firstPlayer, "seeked")
                assertFalse(state.isSeeking)
                emitFakeMoviStringEvent(firstPlayer, "stateChange", "ended")
                assertFalse(state.isPlaying)
                emitFakeMoviStringEvent(firstPlayer, "stateChange", "error")
                assertFalse(state.isLoading)

                state.openUri("https://media.example.test/replacement.mkv", InitialPlayerState.PAUSE)
                emitFakeMoviStringEvent(firstPlayer, "stateChange", "playing")
                emitFakeMoviNumberEvent(firstPlayer, "timeUpdate", 9.0)
                assertFalse(state.isPlaying)
                assertEquals(0.seconds, state.preciseCurrentTime)

                firstSession.destroy()
                firstSession.destroy()
                assertEquals(1, fakeMoviDestroyCount(firstPlayer))

                val replacementSession =
                    MoviPlaybackSession(
                        playerState = state,
                        mediaSessionId = state.mediaSessionId,
                        canvas = canvas,
                        onNativeVideoElement = {},
                        onVideoRatio = {},
                        moduleLoader = { module },
                    )
                try {
                    replacementSession.load(
                        sourceUri = requireNotNull(state.sourceUri),
                        sourceFile = null,
                        mediaHeaders = emptyMap(),
                        drmConfiguration = null,
                    )
                    val replacementPlayer = fakeMoviPlayerAt(module, 1)
                    emitFakeMoviStringEvent(replacementPlayer, "stateChange", "playing")
                    assertTrue(state.isPlaying)

                    state.seekTo(3.seconds)
                    replacementSession.seekPending()
                    assertFalse(state.isSeeking)
                    assertEquals(3.seconds, state.preciseCurrentTime)
                } finally {
                    replacementSession.destroy()
                }
            } finally {
                state.dispose()
                canvas.parentElement?.remove()
            }
        }

    private fun twoAudioTrackSnapshot(): MoviMediaSnapshot =
        MoviMediaSnapshot(
            formatName = "matroska",
            durationSeconds = 10.0,
            bitrate = 256_000,
            title = null,
            audioTracks =
                listOf(
                    AudioTrack(
                        id = "${MOVI_AUDIO_TRACK_ID_PREFIX}1",
                        label = "English",
                        language = "en",
                        isDefault = true,
                    ),
                    AudioTrack(
                        id = "${MOVI_AUDIO_TRACK_ID_PREFIX}2",
                        label = "Polski",
                        language = "pl",
                    ),
                ),
            activeAudioTrackId = "${MOVI_AUDIO_TRACK_ID_PREFIX}1",
            subtitleTracks = emptyList(),
            activeSubtitleTrackId = null,
            videoTracks = emptyList(),
            chapters = emptyList(),
        )
}

@OptIn(ExperimentalWasmJsInterop::class)
@Suppress("LongMethod")
private fun createFakeMoviModule(): JsAny =
    js(
        """
        (function() {
            const module = { players: [], logLevel: null };
            module.MoviPlayer = class {
                static setLogLevel(level) {
                    module.logLevel = Number(level);
                }

                constructor(config) {
                    this.config = config;
                    this.listeners = Object.create(null);
                    this.destroyCount = 0;
                    this.currentTime = 0;
                    this.duration = 12;
                    this.activeAudioId = 1;
                    this.activeVideoId = -1;
                    this.selectedSubtitleIds = [];
                    this.audioTracks = [
                        {
                            id: 1,
                            label: "English",
                            language: "en",
                            channels: 2,
                            sampleRate: 48000,
                            bitRate: 128000
                        },
                        {
                            id: 2,
                            label: "Polski",
                            language: "pl",
                            channels: 2,
                            sampleRate: 48000,
                            bitRate: 128000
                        }
                    ];
                    this.videoTracks = [
                        {
                            id: -1,
                            label: "Auto",
                            codec: "vp9",
                            width: 1920,
                            height: 1080,
                            bitRate: 2000000
                        },
                        {
                            id: 7,
                            label: "1080p",
                            codec: "vp9",
                            width: 1920,
                            height: 1080,
                            bitRate: 2000000
                        }
                    ];
                    this.trackManager = {
                        getActiveAudioTrack: () =>
                            this.audioTracks.find((track) => track.id === this.activeAudioId) || null,
                        getActiveSubtitleTrack: () => null,
                        getActiveVideoTrack: () =>
                            this.videoTracks.find((track) => track.id === this.activeVideoId) || null,
                        selectVideoTrack: (id) => {
                            if (!this.videoTracks.some((track) => track.id === id)) return false;
                            this.activeVideoId = id;
                            this.emit("tracksChange");
                            return true;
                        }
                    };
                    module.players.push(this);
                }

                on(event, callback) {
                    (this.listeners[event] ||= []).push(callback);
                    return () => {
                        this.listeners[event] =
                            (this.listeners[event] || []).filter((candidate) => candidate !== callback);
                    };
                }

                emit(event, value) {
                    (this.listeners[event] || []).slice().forEach((callback) => callback(value));
                }

                load() {
                    this.emit("stateChange", "ready");
                    this.emit("tracksChange");
                    return Promise.resolve();
                }

                play() {
                    this.emit("stateChange", "playing");
                    return Promise.resolve();
                }

                pause() {
                    this.emit("stateChange", "paused");
                }

                seek(seconds) {
                    this.emit("seeking");
                    this.currentTime = Number(seconds);
                    this.emit("timeUpdate", this.currentTime);
                    this.emit("seeked");
                    return Promise.resolve();
                }

                getCurrentTime() {
                    return this.currentTime;
                }

                getDuration() {
                    return this.duration;
                }

                getMediaInfo() {
                    return {
                        formatName: "matroska",
                        duration: this.duration,
                        bitRate: 2256000,
                        metadata: { title: "Fake Movi source" }
                    };
                }

                getAudioTracks() {
                    return this.audioTracks;
                }

                getSubtitleTracks() {
                    return [];
                }

                getVideoTracks() {
                    return this.videoTracks;
                }

                getChapters() {
                    return [{ start: 0, end: 12, title: "Main" }];
                }

                getCachedTimeRanges() {
                    return [{ start: 0, end: 4 }];
                }

                selectAudioTrack(id) {
                    if (!this.audioTracks.some((track) => track.id === id)) return false;
                    this.activeAudioId = id;
                    this.emit("tracksChange");
                    return true;
                }

                selectSubtitleTrack(id) {
                    this.selectedSubtitleIds.push(id == null ? "null" : String(id));
                    return true;
                }

                setVolume() {}
                setPlaybackRate() {}
                setFitMode() {}
                setVR360() {}
                setVRProjection() {}
                resizeCanvas() {}
                getHLSVideoElement() { return null; }
                setSubtitleOverlay(overlay) {
                    this.subtitleOverlay = overlay;
                }
                setSubtitleRenderer(renderer) {
                    this.subtitleRenderer = renderer;
                    if (
                        renderer &&
                        this.subtitleOverlay &&
                        typeof renderer.mount === "function"
                    ) {
                        renderer.mount(this.subtitleOverlay);
                    }
                }
                setSubtitleDelay(seconds) {
                    this.subtitleDelay = Number(seconds);
                    if (
                        this.subtitleRenderer &&
                        typeof this.subtitleRenderer.setDelay === "function"
                    ) {
                        this.subtitleRenderer.setDelay(this.subtitleDelay);
                    }
                }

                destroy() {
                    this.destroyCount += 1;
                }
            };
            return module;
        })()
        """,
    )

@OptIn(ExperimentalWasmJsInterop::class)
private fun createTransactionalTrackPlayer(): JsAny =
    js(
        """
        ({
            activeAudioId: 1,
            activeSubtitleId: null,
            audioTracks: [
                { id: 1, type: "audio", language: "en", label: "English" },
                { id: 2, type: "audio", language: "pl", label: "Polski" }
            ],
            subtitleTracks: [
                {
                    id: 3,
                    type: "subtitle",
                    codec: "ass",
                    subtitleType: "text",
                    language: "pl",
                    label: "Polski ASS"
                }
            ],
            getSubtitleTracks: function() {
                return this.subtitleTracks;
            },
            selectTrack: function(request) {
                if (request.kind === "audio") {
                    const target = request.trackId == null ? this.audioTracks[0] :
                        this.audioTracks.find(function(track) {
                            return track.id === request.trackId;
                        });
                    if (!target || target.id === 99) {
                        return Promise.resolve({
                            kind: "audio",
                            status: "failed",
                            activeTrack: this.audioTracks.find(
                                (track) => track.id === this.activeAudioId
                            ),
                            error: new Error("fake decoder rejected the track")
                        });
                    }
                    this.activeAudioId = target.id;
                    return Promise.resolve({
                        kind: "audio",
                        status: request.trackId == null ? "auto" : "selected",
                        activeTrack: target
                    });
                }
                if (request.kind === "subtitle") {
                    const target = request.trackId == null ? null :
                        this.subtitleTracks.find(function(track) {
                            return track.id === request.trackId;
                        });
                    this.activeSubtitleId = target ? target.id : null;
                    return Promise.resolve({
                        kind: "subtitle",
                        status: target ? "selected" : "disabled",
                        activeTrack: target
                    });
                }
                return Promise.resolve({ kind: request.kind, status: "not-supported" });
            }
        })
        """,
    )

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun readTransactionalMoviActiveAudio(player: JsAny): String =
    js("String(player.__composeMediaPlayerConfirmedAudioTrackId)")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun readTransactionalMoviActiveSubtitle(player: JsAny): String = js("String(player.activeSubtitleId)")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun readFakeMoviConfiguration(player: JsAny): String =
    js(
        """
        [
            player.config.source.type || "",
            player.config.source.url || "",
            player.config.source.headers["X-Media"] || "",
            player.config.source.headers.Authorization || "",
            player.config.licenseUrl || "",
            player.config.licenseHeaders["X-Media"] || "",
            player.config.licenseHeaders.Authorization || "",
            player.config.drmConfig && player.config.drmConfig.licenseUrl || "",
            player.config.drmConfig && player.config.drmConfig.licenseHeaders.Authorization || "",
            player.config.embeddedTextSubtitleRenderer || "",
            String(player.config.logger && player.config.logger.level)
        ].join("|")
        """,
    )

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun readFakeMoviLogLevel(module: JsAny): Int = js("Number(module.logLevel)")

@OptIn(ExperimentalWasmJsInterop::class)
private fun createFakeBrowserFile(): JsAny =
    js("new File([new Uint8Array([1, 2, 3])], 'movie.mkv', { type: 'video/x-matroska' })")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun readFakeMoviFileConfiguration(
    player: JsAny,
    browserFile: JsAny,
): String =
    js(
        """
        [
            player.config.source.type || "",
            String(player.config.source.file === browserFile),
            player.config.source.url || ""
        ].join("|")
        """,
    )

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun fakeMoviPlayerAt(
    module: JsAny,
    index: Int,
): JsAny = js("module.players[index]")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun emitFakeMoviEvent(
    player: JsAny,
    event: String,
): Unit = js("player.emit(event)")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun emitFakeMoviStringEvent(
    player: JsAny,
    event: String,
    value: String,
): Unit = js("player.emit(event, value)")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun emitFakeMoviNumberEvent(
    player: JsAny,
    event: String,
    value: Double,
): Unit = js("player.emit(event, value)")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun emitFakeMoviError(
    player: JsAny,
    message: String,
): Unit = js("player.emit('error', new Error(message))")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun emitFakeMoviBufferUpdate(player: JsAny): Unit =
    js("player.emit('bufferUpdate', [{ start: 1, end: 2 }, { start: 3, end: 4.5 }])")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun fakeMoviDestroyCount(player: JsAny): Int = js("player.destroyCount")

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun readFakeMoviSubtitleSelections(player: JsAny): String = js("player.selectedSubtitleIds.join('|')")

private fun createMountedMoviCanvas(): HTMLCanvasElement {
    val container = document.createElement("div")
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    container.appendChild(canvas)
    document.body?.appendChild(container)
    return canvas
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun createSubtitleSurfacePlayer(): JsAny =
    js(
        """
        ({
            overlay: null,
            renderer: null,
            delay: 0,
            setSubtitleOverlay: function(overlay) {
                this.overlay = overlay;
            },
            setSubtitleRenderer: function(renderer) {
                this.renderer = renderer;
                if (renderer && typeof renderer.mount === "function") {
                    renderer.mount(this.overlay);
                }
            },
            setSubtitleDelay: function(seconds) {
                this.delay = Number(seconds);
                if (this.renderer && typeof this.renderer.setDelay === "function") {
                    this.renderer.setDelay(this.delay);
                }
            }
        })
        """,
    )

@OptIn(ExperimentalWasmJsInterop::class)
private fun createSubtitleSurfaceRenderer(): JsAny =
    js(
        """
        ({
            mounted: null,
            delay: 0,
            mount: function(container) {
                this.mounted = container;
            },
            setDelay: function(seconds) {
                this.delay = Number(seconds);
            }
        })
        """,
    )

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun readSubtitleSurfaceState(
    player: JsAny,
    renderer: JsAny,
): String =
    js(
        """
        [
            String(Boolean(player.overlay && player.overlay.parentElement)),
            String(renderer.mounted === player.overlay),
            String(player.delay),
            String(renderer.delay)
        ].join("|")
        """,
    )

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalWasmJsInterop::class)
private fun releaseSubtitleSurface(player: JsAny): Unit =
    js(
        """
        {
            if (player.overlay && player.overlay.parentElement) {
                player.overlay.remove();
            }
            player.overlay = null;
            player.renderer = null;
        }
        """,
    )
