package io.github.kdroidfilter.composemediaplayer

/**
 * Status for an optional media tool used by JVM fallbacks.
 *
 * A path is reported only for a compatible user/system installation. In-process
 * media bridge components deliberately do not expose an executable path.
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
    val kMediaBridge: ExternalMediaToolStatus,
    val kMediaBridgeProbe: ExternalMediaToolStatus,
    val kMediaBridgeSubtitleBurnIn: ExternalMediaToolStatus,
    val kMediaBridgeHdrToSdrToneMapping: ExternalMediaToolStatus,
    val libass: ExternalMediaToolStatus,
) {
    @Deprecated("Use kMediaBridge; no FFmpeg executable is used.", ReplaceWith("kMediaBridge"))
    val ffmpeg: ExternalMediaToolStatus
        get() = kMediaBridge

    @Deprecated(
        "Use kMediaBridgeSubtitleBurnIn; no FFmpeg executable or filter query is used.",
        ReplaceWith("kMediaBridgeSubtitleBurnIn"),
    )
    val ffmpegWithSubtitlesFilter: ExternalMediaToolStatus
        get() = kMediaBridgeSubtitleBurnIn

    @Deprecated("Use kMediaBridgeProbe; no ffprobe executable is used.", ReplaceWith("kMediaBridgeProbe"))
    val ffprobe: ExternalMediaToolStatus
        get() = kMediaBridgeProbe
}

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
     * Detects optional user-installed JVM media helpers without loading an unconfigured bridge.
     */
    @JvmStatic
    fun query(): JvmMediaToolAvailability = query(emptyList())

    /**
     * Detects user-installed helpers and the explicitly configured desktop bridge extension.
     */
    @JvmStatic
    fun query(extensions: List<VideoPipelineExtension>): JvmMediaToolAvailability {
        val vlcPath = ExternalVlcLocator.findVlc()
        val libVlc = ExternalVlcLocator.findLibVlc()
        val mediaBridges =
            extensions
                .filterIsInstance<DesktopPlaybackBridgeExtension>()
        val mediaBridge =
            mediaBridges.firstOrNull { extension -> extension.availability.canContribute }
                ?: mediaBridges.firstOrNull()
        val mediaBridgeAvailability = mediaBridge?.availability
        val mediaBridgeCapabilities = mediaBridge?.desktopCapabilities
        val mediaBridgeAvailable = mediaBridgeAvailability?.canContribute == true
        val subtitleExtension =
            extensions
                .filterIsInstance<DesktopSubtitlePipelineExtension>()
                .firstOrNull { extension -> extension.availability.canContribute }

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
            kMediaBridge =
                ExternalMediaToolStatus(
                    available = mediaBridgeAvailable,
                    detail =
                        mediaBridgeAvailability?.detail
                            ?: mediaBridge?.let { "Configured desktop media bridge: ${it.id}." }
                            ?: "No DesktopPlaybackBridgeExtension was configured.",
                ),
            kMediaBridgeProbe =
                ExternalMediaToolStatus(
                    available = mediaBridgeAvailable && mediaBridgeCapabilities?.canProbe == true,
                    detail =
                        if (mediaBridgeAvailable && mediaBridgeCapabilities?.canProbe == true) {
                            "Typed probe API is provided in-process by KMediaBridge; no ffprobe executable is used."
                        } else {
                            "The configured desktop bridge does not advertise typed probing."
                        },
                ),
            kMediaBridgeSubtitleBurnIn =
                ExternalMediaToolStatus(
                    available = mediaBridgeAvailable && mediaBridgeCapabilities?.canBurnSubtitles == true,
                    detail =
                        if (mediaBridgeAvailable && mediaBridgeCapabilities?.canBurnSubtitles == true) {
                            "The selected KMediaBridge runtime advertises SDR subtitle burn-in through libass."
                        } else {
                            "The selected KMediaBridge runtime does not advertise safe subtitle burn-in."
                        },
                ),
            kMediaBridgeHdrToSdrToneMapping =
                ExternalMediaToolStatus(
                    available = mediaBridgeAvailable && mediaBridgeCapabilities?.canToneMapToSdr == true,
                    detail =
                        if (mediaBridgeAvailable && mediaBridgeCapabilities?.canToneMapToSdr == true) {
                            "The selected KMediaBridge runtime advertises controlled HDR10/HDR10+/HLG to BT.709 conversion."
                        } else {
                            "The selected KMediaBridge runtime does not advertise controlled HDR-to-SDR conversion."
                        },
                ),
            libass =
                subtitleExtension?.let {
                    ExternalMediaToolStatus(
                        available = true,
                        detail = "Bundled and configured by a DesktopSubtitlePipelineExtension.",
                    )
                } ?: ExternalMediaToolStatus(
                    available = false,
                    detail = "No available desktop subtitle extension configured a renderer.",
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
                    ffmpeg = tools.kMediaBridge,
                    ffmpegWithSubtitlesFilter = tools.kMediaBridgeSubtitleBurnIn,
                    ffprobe = tools.kMediaBridgeProbe,
                    libass = tools.libass,
                )
            }
}
