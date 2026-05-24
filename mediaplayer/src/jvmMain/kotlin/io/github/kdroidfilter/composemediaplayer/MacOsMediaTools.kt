package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.mac.MacLibAssLocator

/**
 * Status for an optional user-installed media tool used by JVM fallbacks.
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
 * Snapshot of optional JVM media helper availability.
 */
data class JvmMediaToolAvailability(
    val vlc: ExternalMediaToolStatus,
    val libVlc: ExternalMediaToolStatus,
    val ffmpeg: ExternalMediaToolStatus,
    val ffmpegWithSubtitlesFilter: ExternalMediaToolStatus,
    val ffprobe: ExternalMediaToolStatus,
    val libass: ExternalMediaToolStatus,
)

/**
 * Snapshot of optional media helper availability.
 *
 * Kept for source and binary compatibility with the original macOS-only API.
 * Prefer [JvmMediaToolAvailability] for new code.
 */
@Deprecated(
    "Use JvmMediaToolAvailability; JVM fallback tool detection is no longer macOS-only.",
    ReplaceWith("JvmMediaToolAvailability"),
)
data class MacOsMediaToolAvailability(
    val vlc: ExternalMediaToolStatus,
    val libVlc: ExternalMediaToolStatus,
    val ffmpeg: ExternalMediaToolStatus,
    val ffmpegWithSubtitlesFilter: ExternalMediaToolStatus,
    val ffprobe: ExternalMediaToolStatus,
    val libass: ExternalMediaToolStatus,
)

object JvmMediaTools {
    /**
     * Detects optional user-installed tools used by JVM external media fallbacks.
     */
    @JvmStatic
    fun query(): JvmMediaToolAvailability {
        val vlcPath = ExternalVlcLocator.findVlc()
        val libVlc = ExternalVlcLocator.findLibVlc()
        val ffmpegPath = ExternalFfmpegLocator.findFfmpeg()
        val ffmpegWithSubtitlesPath = ExternalFfmpegLocator.findFfmpegWithSubtitles()
        val ffprobePath = ffmpegPath?.let(ExternalFfmpegLocator::findFfprobe) ?: ExternalFfmpegLocator.findFfprobe()
        val libassPath = MacLibAssLocator.findLibAss()

        return JvmMediaToolAvailability(
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
                        detail = "Compatible libVLC was not found in configured or default VLC paths.",
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
                    missingDetail = "libass library was not found.",
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

@Deprecated(
    "Use JvmMediaTools; JVM fallback tool detection is no longer macOS-only.",
    ReplaceWith("JvmMediaTools"),
)
@Suppress("DEPRECATION")
object MacOsMediaTools {
    @JvmStatic
    fun query(): MacOsMediaToolAvailability =
        JvmMediaTools
            .query()
            .let { tools ->
                MacOsMediaToolAvailability(
                    vlc = tools.vlc,
                    libVlc = tools.libVlc,
                    ffmpeg = tools.ffmpeg,
                    ffmpegWithSubtitlesFilter = tools.ffmpegWithSubtitlesFilter,
                    ffprobe = tools.ffprobe,
                    libass = tools.libass,
                )
            }
}
