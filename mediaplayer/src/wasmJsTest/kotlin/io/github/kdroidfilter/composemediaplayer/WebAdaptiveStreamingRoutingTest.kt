package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAdaptiveStreamingRoutingTest {
    @Test
    fun adaptiveStreamingFormatsAreRecognizedFromUris() {
        assertEquals(
            WebAdaptiveStreamingFormat.HLS,
            "https://media.example.test/master.m3u8?token=redacted".webAdaptiveStreamingFormatOrNull(),
        )
        assertEquals(
            WebAdaptiveStreamingFormat.DASH,
            "https://media.example.test/manifest.mpd#period".webAdaptiveStreamingFormatOrNull(),
        )
        assertEquals(
            WebAdaptiveStreamingFormat.MSS,
            "https://media.example.test/channel.ism/Manifest(format=mpd-time-csf)"
                .webAdaptiveStreamingFormatOrNull(),
        )
        assertEquals(null, "https://media.example.test/video.mp4".webAdaptiveStreamingFormatOrNull())
    }

    @Test
    fun adaptiveStreamingFormatsAreRecognizedFromMimeTypes() {
        assertEquals(
            WebAdaptiveStreamingFormat.HLS,
            "application/vnd.apple.mpegurl".webAdaptiveStreamingMimeFormatOrNull(),
        )
        assertEquals(
            WebAdaptiveStreamingFormat.DASH,
            "application/dash+xml; charset=utf-8".webAdaptiveStreamingMimeFormatOrNull(),
        )
        assertEquals(
            WebAdaptiveStreamingFormat.MSS,
            "application/vnd.ms-sstr+xml".webAdaptiveStreamingMimeFormatOrNull(),
        )
    }

    @Test
    fun onlyMoviCapabilitiesAdvertiseHlsOnWebAssembly() {
        assertTrue(platformPlayerCapabilities(VideoPlaybackOptions()).supportsHls)
        assertFalse(
            platformPlayerCapabilities(
                VideoPlaybackOptions(webPlaybackEngine = WebPlaybackEngine.LEGACY),
            ).supportsHls,
        )
    }

    @Test
    fun strictColorPreflightDoesNotAdvertiseAdaptiveStreaming() {
        val state =
            DefaultVideoPlayerState(
                VideoPlaybackOptions(dynamicRangePolicy = DynamicRangePolicy.FORCE_SDR),
            )
        try {
            assertFalse(state.canPlaySource("https://media.example.test/master.m3u8"))
            assertFalse(state.canPlaySource("https://media.example.test/manifest.mpd"))
            assertTrue(state.canPlaySource("https://media.example.test/video.mp4"))
        } finally {
            state.dispose()
        }
    }
}
