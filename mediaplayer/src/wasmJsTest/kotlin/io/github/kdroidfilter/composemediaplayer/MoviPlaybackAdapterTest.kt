@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
                    "D|webcodecs|webcodecs|canvas|matroska",
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
            assertEquals(listOf("${MOVI_VIDEO_TRACK_ID_PREFIX}7"), state.availableHlsQualities.map { it.id })
            assertEquals(HlsQualityMode.AUTO, state.hlsQualityMode)
            assertNull(state.currentHlsQuality)
            assertEquals(1920, state.metadata.width)
            assertEquals(1080, state.metadata.height)
            assertEquals(MOVI_RENDERING_BACKEND, state.renderingInfo.backend)
            assertContains(state.renderingInfo.videoDecoder.orEmpty(), "webcodecs")
            assertEquals(VideoDynamicRange.HDR10, state.colorPipelineStatus.value.source.dynamicRange)
        } finally {
            state.dispose()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun audioStateAndEventChangeOnlyAfterAdapterAcceptsSelection() =
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
                TrackSelectionResult.Failed("fake adapter rejected the switch")
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
