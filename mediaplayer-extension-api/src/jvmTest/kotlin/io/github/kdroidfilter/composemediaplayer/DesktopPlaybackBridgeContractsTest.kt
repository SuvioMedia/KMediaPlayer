package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DesktopPlaybackBridgeContractsTest {
    @Test
    fun requestRejectsInvalidSourceCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeRequest(uri = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeRequest(uri = "file:///movie.mkv", selectedAudioStreamIndex = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeRequest(uri = "file:///movie.mkv", selectedSubtitleStreamIndex = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeRequest(uri = "file:///movie.mkv", startPositionMs = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeRequest(
                uri = "file:///movie.mkv",
                requireHdrCmafPassthrough = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeRequest(
                uri = "file:///movie.mkv",
                allowHdrCmafPassthrough = true,
                forceSdrOutput = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeRequest(
                uri = "file:///movie.avi",
                allowHdrCmafPassthrough = true,
                forceAvFoundationCompatibility = true,
            )
        }
    }

    @Test
    fun preparedSourceRejectsInvalidTimeline() {
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeSource(playlistUrl = " ", durationMs = null)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeSource(playlistUrl = "http://127.0.0.1/movie.m3u8", durationMs = -1L)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeSource(
                playlistUrl = "http://127.0.0.1/movie.m3u8",
                durationMs = null,
                playbackOffsetMs = -1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeSource(
                playlistUrl = "http://127.0.0.1/movie.m3u8",
                durationMs = null,
                toneMappedHdrToSdr = true,
                videoCopiedWithoutReencoding = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeSource(
                playlistUrl = "http://127.0.0.1/movie.m3u8",
                durationMs = null,
                hdrCmafPassthrough = true,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopPlaybackBridgeSource(
                playlistUrl = "http://127.0.0.1/movie.m3u8",
                durationMs = null,
                videoCopiedWithoutReencoding = true,
                avFoundationCompatibleTranscode = true,
            )
        }
    }
}
