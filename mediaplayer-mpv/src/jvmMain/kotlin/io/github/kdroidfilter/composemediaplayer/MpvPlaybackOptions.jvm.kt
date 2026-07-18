package io.github.kdroidfilter.composemediaplayer

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
                guidance = "The MPV subtitle-font directory must be an absolute desktop path.",
            )
        }

    return when (val availability = MpvRuntime.inspect(config)) {
        is MpvRuntimeAvailability.Available ->
            MpvBackendAvailability.Available(backend = "KMediaMpv desktop")
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
                        guidance = "The MPV subtitle-font directory must be an absolute desktop path.",
                    ),
                cause = failure,
            )
        }
    return createDesktopMpvVideoPlayerState(config)
}

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
