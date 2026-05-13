package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.mac.MacFfmpegLocator
import io.github.kdroidfilter.composemediaplayer.mac.MacLibAssLocator
import io.github.kdroidfilter.composemediaplayer.mac.MacVlcLocator
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform

/**
 * Status for an optional user-installed media tool used by macOS JVM fallbacks.
 *
 * ComposeMediaPlayer does not bundle or redistribute these tools. The path is
 * reported only when a compatible user/system installation is detected.
 */
data class ExternalMediaToolStatus(
    val available: Boolean,
    val path: String? = null,
    val detail: String? = null,
)

/**
 * Snapshot of optional macOS JVM media helper availability.
 */
data class MacOsMediaToolAvailability(
    val vlc: ExternalMediaToolStatus,
    val libVlc: ExternalMediaToolStatus,
    val ffmpeg: ExternalMediaToolStatus,
    val ffmpegWithSubtitlesFilter: ExternalMediaToolStatus,
    val ffprobe: ExternalMediaToolStatus,
    val libass: ExternalMediaToolStatus,
)

object MacOsMediaTools {
    /**
     * Detects optional user-installed tools used by the macOS JVM MKV fallbacks.
     */
    @JvmStatic
    fun query(): MacOsMediaToolAvailability {
        if (CurrentPlatform.os != CurrentPlatform.OS.MAC) {
            val unavailable = ExternalMediaToolStatus(false, detail = "macOS JVM fallback only")
            return MacOsMediaToolAvailability(
                vlc = unavailable,
                libVlc = unavailable,
                ffmpeg = unavailable,
                ffmpegWithSubtitlesFilter = unavailable,
                ffprobe = unavailable,
                libass = unavailable,
            )
        }

        val vlcPath = MacVlcLocator.findVlc()
        val libVlc = MacVlcLocator.findLibVlc()
        val ffmpegPath = MacFfmpegLocator.findFfmpeg()
        val ffmpegWithSubtitlesPath = MacFfmpegLocator.findFfmpegWithSubtitles()
        val ffprobePath = ffmpegPath?.let(MacFfmpegLocator::findFfprobe) ?: MacFfmpegLocator.findFfprobe()
        val libassPath = MacLibAssLocator.findLibAss()

        return MacOsMediaToolAvailability(
            vlc =
                toolStatus(
                    path = vlcPath,
                    missingDetail = "VLC executable was not found.",
                ),
            libVlc =
                if (libVlc != null) {
                    ExternalMediaToolStatus(
                        available = true,
                        path = libVlc.libVlcPath,
                        detail = "Plugins: ${libVlc.pluginPath}",
                    )
                } else {
                    ExternalMediaToolStatus(
                        available = false,
                        detail = "Compatible libVLC was not found in VLC.app or configured paths.",
                    )
                },
            ffmpeg =
                toolStatus(
                    path = ffmpegPath,
                    missingDetail = "ffmpeg executable was not found.",
                ),
            ffmpegWithSubtitlesFilter =
                when {
                    ffmpegWithSubtitlesPath != null ->
                        ExternalMediaToolStatus(
                            available = true,
                            path = ffmpegWithSubtitlesPath,
                        )
                    ffmpegPath != null ->
                        ExternalMediaToolStatus(
                            available = false,
                            path = ffmpegPath,
                            detail = "ffmpeg was found, but the subtitles filter is not available.",
                        )
                    else ->
                        ExternalMediaToolStatus(
                            available = false,
                            detail = "ffmpeg with the subtitles filter was not found.",
                        )
                },
            ffprobe =
                toolStatus(
                    path = ffprobePath,
                    missingDetail = "ffprobe executable was not found.",
                ),
            libass =
                toolStatus(
                    path = libassPath,
                    missingDetail = "libass dylib was not found.",
                ),
        )
    }

    private fun toolStatus(
        path: String?,
        missingDetail: String,
    ): ExternalMediaToolStatus =
        if (path != null) {
            ExternalMediaToolStatus(available = true, path = path)
        } else {
            ExternalMediaToolStatus(available = false, detail = missingDetail)
        }
}
