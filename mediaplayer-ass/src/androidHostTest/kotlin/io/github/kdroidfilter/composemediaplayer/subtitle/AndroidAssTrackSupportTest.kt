package io.github.kdroidfilter.composemediaplayer.subtitle

import androidx.media3.common.MimeTypes
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAssTrackSupportTest {
    @Test
    fun onlyRawMatroskaMarkedEmbeddedSsaIsALibassCandidate() {
        val baseTrack =
            SubtitleTrack(
                label = "ASS",
                language = "pl",
                src = "",
                format = SubtitleFormat.AUTO,
                id = "embedded",
                isEmbedded = true,
                kind = MimeTypes.TEXT_SSA,
            )

        assertFalse(baseTrack.isAndroidLibassCandidate)
        assertTrue(baseTrack.copy(format = SubtitleFormat.ASS).isAndroidLibassCandidate)
    }

    @Test
    fun externalAssRemainsALibassCandidateByExplicitFormatOrExtension() {
        val baseTrack =
            SubtitleTrack(
                label = "External",
                language = "pl",
                src = "subtitle.srt",
            )

        assertFalse(baseTrack.isAndroidLibassCandidate)
        assertTrue(baseTrack.copy(src = "subtitle.ass").isAndroidLibassCandidate)
        assertTrue(baseTrack.copy(format = SubtitleFormat.SSA).isAndroidLibassCandidate)
    }
}
