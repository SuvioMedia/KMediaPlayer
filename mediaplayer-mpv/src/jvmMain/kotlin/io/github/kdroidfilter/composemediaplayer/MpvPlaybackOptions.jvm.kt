package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendAvailability
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendRoutingTier
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.asDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.mpv.MpvLibrarySource
import io.github.kdroidfilter.composemediaplayer.mpv.MpvRuntime
import io.github.kdroidfilter.composemediaplayer.mpv.MpvRuntimeAvailability
import io.github.kdroidfilter.composemediaplayer.mpv.MpvRuntimeConfig
import io.github.kdroidfilter.composemediaplayer.mpv.MpvUnavailableReason
import io.github.kdroidfilter.composemediaplayer.mpv.createDesktopMpvVideoPlayerState
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal actual fun mpvBackendInfo(): VideoPlayerBackendInfo =
    VideoPlayerBackendInfo(
        id = "mpv",
        displayName = "MPV (desktop)",
        capabilities =
            PlayerCapabilities(
                supportsMkv = true,
                supportedUriSchemes = setOf("file"),
            ),
    )

actual fun inspectMpvBackend(options: MpvPlaybackOptions): MpvBackendAvailability {
    val config =
        try {
            options.toDesktopRuntimeConfig()
        } catch (_: IllegalArgumentException) {
            return MpvBackendAvailability.Unavailable(
                reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
                guidance =
                    "MPV runtime paths must be valid absolute desktop paths; " +
                        "the subtitle-font directory must also exist before player creation.",
            )
        }

    return when (val availability = MpvRuntime.inspect(config)) {
        is MpvRuntimeAvailability.Available ->
            MpvBackendAvailability.Available(
                backend =
                    if (options.runtimeSource == MpvRuntimeSource.Bundled) {
                        "KMediaMpv desktop"
                    } else {
                        "libmpv desktop"
                    },
            )
        is MpvRuntimeAvailability.Unavailable ->
            MpvBackendAvailability.Unavailable(
                reason = availability.reason.toPublicReason(),
                guidance = availability.guidance,
            )
    }
}

actual fun createMpvVideoPlayerState(options: MpvPlaybackOptions): VideoPlayerState {
    val config =
        try {
            options.toDesktopRuntimeConfig()
        } catch (failure: IllegalArgumentException) {
            throw MpvBackendUnavailableException(
                availability =
                    MpvBackendAvailability.Unavailable(
                        reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
                        guidance =
                            "MPV runtime paths must be valid absolute desktop paths; " +
                                "the subtitle-font directory must also exist before player creation.",
                    ),
                cause = failure,
            )
        }
    return createDesktopMpvVideoPlayerState(config)
}

/** Verified native MPV route for the explicit desktop playback-session API. */
fun mpvDesktopPlaybackBackend(options: MpvPlaybackOptions = MpvPlaybackOptions()): DesktopPlaybackBackend =
    MpvVideoPlayerBackend(options).asDesktopPlaybackBackend(
        routingTier = DesktopBackendRoutingTier.MPV_NATIVE,
        availabilityProbe = {
            when (val availability = inspectMpvBackend(options)) {
                is MpvBackendAvailability.Available ->
                    DesktopBackendAvailability.Available(availability.backend)
                is MpvBackendAvailability.Unavailable ->
                    DesktopBackendAvailability.Unavailable(
                        reason = availability.reason.name,
                        guidance = availability.guidance,
                    )
            }
        },
    )

private fun MpvPlaybackOptions.toDesktopRuntimeConfig(): MpvRuntimeConfig {
    val fontsDirectory =
        subtitleFontsDirectory?.let { configuredPath ->
            try {
                Path.of(configuredPath)
            } catch (failure: InvalidPathException) {
                throw IllegalArgumentException("Invalid subtitle-font directory.", failure)
            }.also { path ->
                require(path.isAbsolute) { "The subtitle-font directory must be absolute." }
            }
        }
    return MpvRuntimeConfig(
        librarySource =
            when (val source = runtimeSource) {
                MpvRuntimeSource.Bundled -> MpvLibrarySource.Bundled
                MpvRuntimeSource.System -> MpvLibrarySource.Automatic
                is MpvRuntimeSource.ExplicitPath -> {
                    val path =
                        try {
                            Path.of(source.path)
                        } catch (failure: InvalidPathException) {
                            throw IllegalArgumentException("Invalid libmpv path.", failure)
                        }
                    require(path.isAbsolute) { "The libmpv path must be absolute." }
                    MpvLibrarySource.ExplicitPath(path)
                }
            },
        preserveAssStyles = preserveAssStyles,
        useEmbeddedFonts = useEmbeddedFonts,
        subtitleFontsDirectory = fontsDirectory,
        maxRenderPixels = maxDesktopRenderPixels,
    )
}

private fun MpvUnavailableReason.toPublicReason(): MpvBackendUnavailableReason =
    when (this) {
        MpvUnavailableReason.RUNTIME_DEPENDENCY_MISSING ->
            MpvBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING
        MpvUnavailableReason.UNSUPPORTED_PLATFORM ->
            MpvBackendUnavailableReason.UNSUPPORTED_PLATFORM
        MpvUnavailableReason.BUNDLED_RUNTIME_REJECTED,
        MpvUnavailableReason.NATIVE_ACCESS_DISABLED,
        MpvUnavailableReason.LIBRARY_NOT_FOUND,
        MpvUnavailableReason.REQUIRED_SYMBOL_MISSING,
        MpvUnavailableReason.INCOMPATIBLE_CLIENT_API,
        MpvUnavailableReason.LOAD_FAILED,
        -> MpvBackendUnavailableReason.INVALID_RUNTIME
    }
