package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ExternalAudioTrackTest {
    @Test
    fun metadataIsExposedAsAnExternalAudioTrack() {
        val external =
            ExternalAudioTrack(
                id = "narration-pl",
                label = "Polish narration",
                source = MediaSourceSpec("https://media.invalid/narration.m4a", "audio/mp4"),
                language = "pl",
                channels = 6,
                sampleRate = 48_000,
                bitrate = 640_000,
                isDefault = true,
            )

        val audio = external.asAudioTrack()

        assertEquals(external.id, audio.id)
        assertEquals(external.label, audio.label)
        assertEquals("pl", audio.language)
        assertEquals(6, audio.channels)
        assertEquals(48_000, audio.sampleRate)
        assertEquals(640_000, audio.bitrate)
        assertTrue(audio.isDefault)
        assertTrue(audio.isExternal)
        assertFalse(audio.isEmbedded)
        assertEquals("audio/mp4", audio.mimeType)
    }

    @Test
    fun diagnosticRenderingRedactsSourceAndHeaderValues() {
        val source = "https://media.invalid/narration.m4a?temporary=private-value"
        val headerValue = "Bearer private-value"
        val track =
            ExternalAudioTrack(
                id = "narration-pl",
                label = "Polish narration",
                source = MediaSourceSpec(source),
                requestHeaders = mapOf("Authorization" to headerValue),
            )

        val rendered = track.toString()

        assertFalse(rendered.contains(source))
        assertFalse(rendered.contains(headerValue))
        assertTrue(rendered.contains("source=<redacted>"))
        assertTrue(rendered.contains("requestHeaderCount=1"))
    }

    @Test
    fun invalidMetadataIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ExternalAudioTrack(
                id = "",
                label = "Polish narration",
                source = MediaSourceSpec("https://media.invalid/narration.m4a"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExternalAudioTrack(
                id = "narration-pl",
                label = "Polish narration",
                source = MediaSourceSpec("https://media.invalid/narration.m4a"),
                channels = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ExternalAudioTrack(
                id = "narration-pl",
                label = "Polish narration",
                source = MediaSourceSpec("https://media.invalid/narration.m4a"),
                playbackMode = ExternalAudioPlaybackMode.OVERLAY,
                duckingIntervals =
                    listOf(
                        ExternalAudioDuckingInterval(2.seconds, 4.seconds),
                        ExternalAudioDuckingInterval(3.seconds, 5.seconds),
                    ),
            )
        }
    }
}
