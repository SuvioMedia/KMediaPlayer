package io.github.kdroidfilter.composemediaplayer

import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.mpv.AndroidMpvVideoPlayerState
import io.github.shusek.kmediampv.runtime.android.MpvAndroidRuntime
import io.github.shusek.kmediampv.runtime.android.MpvAndroidDecodeMode as RuntimeMpvAndroidDecodeMode
import java.io.File

internal actual fun mpvBackendInfo(): VideoPlayerBackendInfo =
    VideoPlayerBackendInfo(
        id = "mpv",
        displayName = "MPV (Android)",
        capabilities =
            PlayerCapabilities(
                supportsMkv = true,
                supportedUriSchemes = setOf("file", "content"),
            ),
    )

actual fun inspectMpvBackend(options: MpvPlaybackOptions): MpvBackendAvailability {
    if (options.runtimeSource != MpvRuntimeSource.Bundled) {
        return MpvBackendAvailability.Unavailable(
            reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
            guidance =
                "Android accepts only MpvRuntimeSource.Bundled; its JNI runtime is supplied " +
                    "by kmedia-mpv-runtime-android.",
        )
    }
    if (!options.preserveAssStyles || !options.useEmbeddedFonts) {
        return MpvBackendAvailability.Unavailable(
            reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
            guidance =
                "The Android KMediaMpv runtime preserves ASS styles and embedded fonts; " +
                    "disabling either option is not supported.",
        )
    }
    val fontsDirectory =
        try {
            options.androidSubtitleFontsDirectory()
        } catch (_: IllegalArgumentException) {
            return MpvBackendAvailability.Unavailable(
                reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
                guidance = "The MPV subtitle-font directory must be an absolute app-private Android path.",
            )
        }

    return try {
        if (!MpvAndroidRuntime.isSupportedDevice()) {
            MpvBackendAvailability.Unavailable(
                reason = MpvBackendUnavailableReason.UNSUPPORTED_DEVICE,
                guidance = "KMediaMpv requires Android API 28+ on arm64-v8a or armeabi-v7a.",
            )
        } else {
            // This validates that the exact JNI/native graph can be loaded on the device.
            MpvAndroidRuntime.inspectNativeRuntime()
            if (fontsDirectory != null && !fontsDirectory.isDirectory) {
                MpvBackendAvailability.Unavailable(
                    reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
                    guidance = "The configured MPV subtitle-font directory does not exist.",
                )
            } else {
                MpvBackendAvailability.Available(backend = "KMediaMpv Android")
            }
        }
    } catch (failure: NoClassDefFoundError) {
        MpvBackendAvailability.Unavailable(
            reason = MpvBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
            guidance =
                "The MPV backend was selected without the optional " +
                    "io.github.shusek:kmedia-mpv-runtime-android dependency.",
        )
    } catch (_: UnsatisfiedLinkError) {
        MpvBackendAvailability.Unavailable(
            reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
            guidance = "The KMediaMpv Android native payload is missing or incompatible.",
        )
    } catch (_: RuntimeException) {
        MpvBackendAvailability.Unavailable(
            reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
            guidance = "The KMediaMpv Android runtime failed its native identity check.",
        )
    }
}

actual fun createMpvVideoPlayerState(options: MpvPlaybackOptions): VideoPlayerState {
    val availability = inspectMpvBackend(options)
    if (availability is MpvBackendAvailability.Unavailable) {
        throw MpvBackendUnavailableException(availability)
    }
    val context =
        try {
            ContextProvider.getContext().applicationContext
        } catch (failure: IllegalStateException) {
            throw MpvBackendUnavailableException(
                availability =
                    MpvBackendAvailability.Unavailable(
                        reason = MpvBackendUnavailableReason.INITIALIZATION_FAILED,
                        guidance = "Android context is unavailable for the KMediaMpv backend.",
                    ),
                cause = failure,
            )
        }
    return try {
        AndroidMpvVideoPlayerState(
            context = context,
            subtitleFontsDirectory = options.androidSubtitleFontsDirectory(),
            decodeMode = options.androidDecodeMode.toRuntimeDecodeMode(),
        )
    } catch (failure: RuntimeException) {
        throw MpvBackendUnavailableException(
            availability =
                MpvBackendAvailability.Unavailable(
                    reason = MpvBackendUnavailableReason.INITIALIZATION_FAILED,
                    guidance =
                        "KMediaMpv could not create the Android player. " +
                            "Subtitle fonts must be in an app-private directory.",
                ),
            cause = failure,
        )
    } catch (failure: UnsatisfiedLinkError) {
        throw MpvBackendUnavailableException(
            availability =
                MpvBackendAvailability.Unavailable(
                    reason = MpvBackendUnavailableReason.INVALID_RUNTIME,
                    guidance = "The KMediaMpv Android native payload is missing or incompatible.",
                ),
            cause = failure,
        )
    }
}

private fun MpvAndroidDecodeMode.toRuntimeDecodeMode(): RuntimeMpvAndroidDecodeMode =
    when (this) {
        MpvAndroidDecodeMode.MEDIA_CODEC_COPY -> RuntimeMpvAndroidDecodeMode.MEDIA_CODEC_COPY
        MpvAndroidDecodeMode.SOFTWARE_ONLY -> RuntimeMpvAndroidDecodeMode.SOFTWARE_ONLY
    }

private fun MpvPlaybackOptions.androidSubtitleFontsDirectory(): File? =
    subtitleFontsDirectory?.let { configuredPath ->
        File(configuredPath).also { directory ->
            require(directory.isAbsolute) { "The subtitle-font directory must be absolute." }
        }
    }
