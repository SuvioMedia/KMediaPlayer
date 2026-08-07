@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.kdroidfilter.composemediaplayer

import kotlinx.browser.document
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.w3c.dom.HTMLVideoElement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WasmEnginePlaybackAdapterTest {
    @Test
    fun wasmEngineBufferedRangesAreNormalizedBeforeEnteringTheSharedModel() {
        val ranges =
            listOf(
                io.github.shusek.kmedia.engine.wasm
                    .BufferedRange(start = 2.seconds, end = 1.seconds),
                io.github.shusek.kmedia.engine.wasm
                    .BufferedRange(start = (-1).seconds, end = 1.seconds),
                io.github.shusek.kmedia.engine.wasm
                    .BufferedRange(start = 4.seconds, end = 6.seconds),
            )

        assertEquals(
            listOf(
                BufferedRange(start = 2.seconds, end = 2.seconds),
                BufferedRange(start = 0.seconds, end = 1.seconds),
                BufferedRange(start = 4.seconds, end = 6.seconds),
            ),
            ranges.toKMediaRanges(),
        )
    }

    @Test
    fun decoderPreferenceMapsToTheTypedWasmEnginePolicy() {
        assertEquals(
            io.github.shusek.kmedia.engine.wasm.DecoderPreference.AUTO,
            WebDecoderPreference.AUTO.toWasmEngineDecoderPreference(),
        )
        assertEquals(
            io.github.shusek.kmedia.engine.wasm.DecoderPreference.SOFTWARE,
            WebDecoderPreference.SOFTWARE.toWasmEngineDecoderPreference(),
        )
    }

    @Test
    fun dynamicRangePoliciesMapWithoutLosingFailClosedSemantics() {
        assertEquals(
            io.github.shusek.kmedia.engine.wasm.OutputDynamicRangePolicy.AUTO,
            DynamicRangePolicy.AUTO.toWasmEngineOutputDynamicRangePolicy(),
        )
        assertEquals(
            io.github.shusek.kmedia.engine.wasm.OutputDynamicRangePolicy.PREFER_HDR,
            DynamicRangePolicy.PREFER_HDR.toWasmEngineOutputDynamicRangePolicy(),
        )
        assertEquals(
            io.github.shusek.kmedia.engine.wasm.OutputDynamicRangePolicy.REQUIRE_HDR,
            DynamicRangePolicy.REQUIRE_HDR.toWasmEngineOutputDynamicRangePolicy(),
        )
        assertEquals(
            io.github.shusek.kmedia.engine.wasm.OutputDynamicRangePolicy.FORCE_SDR,
            DynamicRangePolicy.FORCE_SDR.toWasmEngineOutputDynamicRangePolicy(),
        )
    }

    @Test
    fun preparedBrowserSourceAdapterPreservesTransportAndOwnershipHooks() {
        val prepared = RecordingPreparedBrowserSource()
        val adapter = KMediaBrowserSourceAdapter(prepared, fallbackMimeType = "video/webm")
        val video = document.createElement("video") as HTMLVideoElement
        var failure: String? = null

        adapter.attach(video) { failure = it }
        adapter.detach(video)
        adapter.close()

        assertEquals("blob:https://example.test/prepared", adapter.url)
        assertEquals("video/mp4", adapter.mimeType)
        assertSame(video, prepared.attachedVideo)
        assertSame(video, prepared.detachedVideo)
        assertEquals(1, prepared.closeCalls)
        assertNull(failure)
    }

    @Test
    fun projectionRotationAndCropMapToTheWasmEnginePipeline() {
        val projection =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Equirect180,
                stereoLayout = VideoStereoLayout.SideBySide,
                fovDegrees = 110f,
                rotation = VideoProjectionRotation.Rotate270,
            )
        val mapped =
            projection.toWasmEngineProjection(
                VideoTextureCrop(
                    left = 0.1f,
                    top = 0.2f,
                    right = 0.15f,
                    bottom = 0.25f,
                ),
                VideoProjectionViewSettings(
                    yawDegrees = 35f,
                    pitchDegrees = -12f,
                    zoom = 2f,
                ),
            )

        assertEquals(io.github.shusek.kmedia.engine.wasm.ProjectionMode.VR180, mapped.mode)
        assertEquals(io.github.shusek.kmedia.engine.wasm.ProjectionStereoLayout.SIDE_BY_SIDE, mapped.stereoLayout)
        assertEquals(io.github.shusek.kmedia.engine.wasm.ProjectionEyeOrder.LEFT_FIRST, mapped.eyeOrder)
        assertEquals(35f, mapped.yawDegrees)
        assertEquals(-12f, mapped.pitchDegrees)
        assertEquals(55f, mapped.fieldOfViewDegrees)
        assertEquals(0.1f, mapped.cropLeft)
        assertEquals(0.2f, mapped.cropTop)
        assertEquals(0.15f, mapped.cropRight)
        assertEquals(0.25f, mapped.cropBottom)
        assertEquals(270, projection.toWasmEngineRotationDegrees())
    }

    @Test
    fun monoscopicVrPreviewMapsOneLogicalEyeAcrossTheWasmViewport() {
        val mapped =
            VideoProjectionSettings(
                projectionType = VideoProjectionType.Fisheye190,
                stereoLayout = VideoStereoLayout.SideBySide,
                displayMode = VideoProjectionDisplayMode.MonoscopicLeft,
            ).toWasmEngineProjection(
                VideoTextureCrop(left = 0.1f, right = 0.1f),
            )

        assertEquals(io.github.shusek.kmedia.engine.wasm.ProjectionStereoLayout.MONO, mapped.stereoLayout)
        assertEquals(0.05f, mapped.cropLeft)
        assertEquals(0f, mapped.cropTop)
        assertEquals(0.55f, mapped.cropRight)
        assertEquals(0f, mapped.cropBottom)
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
            redactWasmMediaError(
                "License https://license.example.test/private rejected Authorization: Bearer private-token",
                configuration,
            )
        assertFalse("license.example.test" in redacted)
        assertFalse("Authorization" in redacted)
        assertFalse("private-token" in redacted)
        assertContains(redacted, "license details were redacted")

        val mediaRedacted =
            redactWasmMediaError(
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
            WasmEngineMediaSnapshot(
                formatName = "matroska,webm",
                durationSeconds = 12.5,
                bitrate = 2_400_000,
                title = "Two languages",
                audioTracks =
                    listOf(
                        AudioTrack(
                            id = "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}1",
                            label = "English",
                            language = "en",
                            channels = 2,
                            sampleRate = 48_000,
                            bitrate = 128_000,
                            isDefault = true,
                        ),
                        AudioTrack(
                            id = "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2",
                            label = "Polski",
                            language = "pl",
                            channels = 2,
                            sampleRate = 48_000,
                            bitrate = 128_000,
                        ),
                    ),
                activeAudioTrackId = "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}1",
                subtitleTracks =
                    listOf(
                        SubtitleTrack(
                            id = "${WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX}3",
                            label = "English CC",
                            language = "en",
                            src = "${WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX}3",
                            format = SubtitleFormat.ASS,
                            isEmbedded = true,
                        ),
                    ),
                activeSubtitleTrackId = "${WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX}3",
                videoTracks =
                    listOf(
                        hdrAv1Track(id = -1, isActive = true),
                        hdrAv1Track(id = 7, isActive = false),
                    ),
                chapters =
                    listOf(
                        MediaChapter(
                            start = 0.seconds,
                            end = 5.seconds,
                            title = "Intro",
                            language = "pl",
                            isHidden = true,
                        ),
                        MediaChapter(
                            start = 5.seconds,
                            end = 12.5.seconds,
                            title = "Main",
                            language = "en",
                        ),
                    ),
                diagnostics =
                    WasmEngineRenderingDiagnosticsSnapshot(
                        backend = "webcodecs",
                        decoder = "webcodecs",
                        renderer = "canvas",
                        container = "matroska",
                    ),
            )
        val state = createVideoPlayerState() as DefaultVideoPlayerState
        try {
            state.openUri("https://example.test/movie.mkv")
            state.applyWasmEngineSnapshot(snapshot)

            assertEquals(listOf("en", "pl"), state.availableAudioTracks.map(AudioTrack::language))
            assertEquals("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}1", state.currentAudioTrack?.id)
            assertEquals(1, state.availableSubtitleTracks.count(SubtitleTrack::isEmbedded))
            assertEquals("${WASM_ENGINE_SUBTITLE_TRACK_ID_PREFIX}3", state.currentSubtitleTrack?.src)
            assertEquals(SubtitleFormat.ASS, state.currentSubtitleTrack?.format)
            assertEquals(2, state.chapters.size)
            assertEquals("pl", state.chapters.first().language)
            assertTrue(state.chapters.first().isHidden)
            assertEquals(listOf("${WASM_ENGINE_VIDEO_TRACK_ID_PREFIX}7"), state.availableHlsQualities.map { it.id })
            assertEquals(HlsQualityMode.AUTO, state.hlsQualityMode)
            assertNull(state.currentHlsQuality)
            assertEquals(1920, state.metadata.width)
            assertEquals(1080, state.metadata.height)
            assertEquals(WASM_ENGINE_RENDERING_BACKEND, state.renderingInfo.backend)
            assertContains(state.renderingInfo.videoDecoder.orEmpty(), "webcodecs")
            assertEquals(VideoDynamicRange.HDR10, state.colorPipelineStatus.value.source.dynamicRange)
            assertEquals(VideoDynamicRange.UNKNOWN, state.colorPipelineStatus.value.outputDynamicRange)
            assertEquals(ColorPipelineVerification.NONE, state.colorPipelineStatus.value.verification)
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
            state.applyWasmEngineSnapshot(twoAudioTrackSnapshot())
            runCurrent()
            events.clear()

            state.applyAudioTrackSelectionCallback = {
                TrackSelectionResult.Failed("fake adapter rejected the switch")
            }
            val failed = state.selectAudioTrack("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2")
            runCurrent()
            assertIs<TrackSelectionResult.Failed>(failed)
            assertEquals("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}1", state.currentAudioTrack?.id)
            assertTrue(events.none { it is PlaybackEvent.TrackChanged })

            state.applyAudioTrackSelectionCallback = { requested ->
                requested?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Auto
            }
            val selected = state.selectAudioTrack("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2")
            runCurrent()
            assertEquals(TrackSelectionResult.Selected("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2"), selected)
            assertEquals("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2", state.currentAudioTrack?.id)
            state.dispose()
        }

    @Test
    fun rapidAcceptedAudioSwitchesKeepTheLastTrack() {
        val state = createVideoPlayerState() as DefaultVideoPlayerState
        try {
            state.openUri("https://example.test/movie.mkv")
            state.applyWasmEngineSnapshot(twoAudioTrackSnapshot())
            val applied = mutableListOf<String?>()
            state.applyAudioTrackSelectionCallback = { requested ->
                applied += requested?.id
                requested?.let { TrackSelectionResult.Selected(it.id) } ?: TrackSelectionResult.Auto
            }

            state.selectAudioTrack("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2")
            state.selectAudioTrack("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}1")
            state.selectAudioTrack("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2")

            assertEquals(
                listOf<String?>(
                    "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2",
                    "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}1",
                    "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2",
                ),
                applied,
            )
            assertEquals("${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2", state.currentAudioTrack?.id)
        } finally {
            state.dispose()
        }
    }

    private fun twoAudioTrackSnapshot(): WasmEngineMediaSnapshot =
        WasmEngineMediaSnapshot(
            formatName = "matroska",
            durationSeconds = 10.0,
            bitrate = 256_000,
            title = null,
            audioTracks =
                listOf(
                    AudioTrack(
                        id = "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}1",
                        label = "English",
                        language = "en",
                        isDefault = true,
                    ),
                    AudioTrack(
                        id = "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}2",
                        label = "Polski",
                        language = "pl",
                    ),
                ),
            activeAudioTrackId = "${WASM_ENGINE_AUDIO_TRACK_ID_PREFIX}1",
            subtitleTracks = emptyList(),
            activeSubtitleTrackId = null,
            videoTracks = emptyList(),
            chapters = emptyList(),
        )

    private fun hdrAv1Track(
        id: Int,
        isActive: Boolean,
    ): WasmEngineVideoTrackSnapshot =
        WasmEngineVideoTrackSnapshot(
            id = id,
            codec = "av1",
            width = 1920,
            height = 1080,
            frameRate = 24f,
            bitrate = 2_100_000,
            pixelFormat = "yuv420p10le",
            colorPrimaries = "bt2020",
            colorTransfer = "smpte2084",
            colorMatrix = "bt2020nc",
            colorRange = "tv",
            isHdr = true,
            isActive = isActive,
        )
}

private class RecordingPreparedBrowserSource : WebPreparedVideoPipelineSource {
    override val uri: String = "blob:https://example.test/prepared"
    override val mimeType: String = "video/mp4"
    override val outputColorInfo: VideoColorInfo = VideoColorInfo(dynamicRange = VideoDynamicRange.HDR10)
    override val metadataHandling: DynamicMetadataHandling = DynamicMetadataHandling.CONVERTED
    var attachedVideo: HTMLVideoElement? = null
    var detachedVideo: HTMLVideoElement? = null
    var closeCalls: Int = 0

    override fun attach(
        videoElement: HTMLVideoElement,
        onFailure: (String) -> Unit,
    ) {
        attachedVideo = videoElement
    }

    override fun detach(videoElement: HTMLVideoElement) {
        detachedVideo = videoElement
    }

    override fun close() {
        closeCalls += 1
    }
}
