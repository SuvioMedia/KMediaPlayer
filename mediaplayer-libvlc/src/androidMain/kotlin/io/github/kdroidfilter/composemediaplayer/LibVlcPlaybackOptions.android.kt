@file:OptIn(ExperimentalComposeMediaPlayerBackendApi::class)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import com.kdroid.androidcontextprovider.ContextProvider
import io.github.kdroidfilter.composemediaplayer.libvlc.AndroidLibVlcVideoPlayerState
import io.github.shusek.kmediavlc.runtime.android.VlcAndroidRuntime

@Stable
actual sealed interface LibVlcRuntimeSource {
    actual data object Bundled : LibVlcRuntimeSource
}

actual fun inspectLibVlcBackend(options: LibVlcPlaybackOptions): LibVlcBackendAvailability {
    validateAndroidLibVlcOptions(options)?.let { return it }
    return try {
        if (!VlcAndroidRuntime.isSupportedDevice()) {
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.UNSUPPORTED_DEVICE,
                "KMediaVlc requires Android API 28+ on arm64-v8a or armeabi-v7a.",
            )
        } else {
            val report = VlcAndroidRuntime.inspectNativeRuntime()
            LibVlcBackendAvailability.Available(
                backend = "KMediaVlc ${report.vlcVersion}",
                deliveryMode = LibVlcFrameDeliveryMode.ANDROID_SURFACE,
            )
        }
    } catch (_: NoClassDefFoundError) {
        unavailableLibVlcBackend(
            LibVlcBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING,
            "Add the audited kmedia-vlc-runtime-android dependency.",
        )
    } catch (_: UnsatisfiedLinkError) {
        unavailableLibVlcBackend(
            LibVlcBackendUnavailableReason.INVALID_RUNTIME,
            "The bundled KMediaVlc Android native payload is missing or incompatible.",
        )
    } catch (_: RuntimeException) {
        unavailableLibVlcBackend(
            LibVlcBackendUnavailableReason.INVALID_RUNTIME,
            "The bundled KMediaVlc Android runtime failed its native identity check.",
        )
    }
}

actual fun createLibVlcVideoPlayerState(options: LibVlcPlaybackOptions): VideoPlayerState {
    val availability = inspectLibVlcBackend(options)
    if (availability is LibVlcBackendAvailability.Unavailable) {
        throw LibVlcBackendUnavailableException(availability)
    }
    val context =
        try {
            ContextProvider.getContext().applicationContext
        } catch (failure: IllegalStateException) {
            throw LibVlcBackendUnavailableException(
                unavailableLibVlcBackend(
                    LibVlcBackendUnavailableReason.INITIALIZATION_FAILED,
                    "Android context is unavailable for the KMediaVlc backend.",
                ),
                failure,
            )
        }
    return try {
        AndroidLibVlcVideoPlayerState(context, options)
    } catch (failure: UnsatisfiedLinkError) {
        throw LibVlcBackendUnavailableException(
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.INVALID_RUNTIME,
                "The bundled KMediaVlc Android native payload is missing or incompatible.",
            ),
            failure,
        )
    } catch (failure: RuntimeException) {
        throw LibVlcBackendUnavailableException(
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.INITIALIZATION_FAILED,
                "KMediaVlc could not create the Android Surface player.",
            ),
            failure,
        )
    }
}

internal actual fun libVlcBackendInfo(): VideoPlayerBackendInfo =
    VideoPlayerBackendInfo(
        id = "libvlc4",
        displayName = "libVLC 4 (Android)",
        capabilities =
            PlayerCapabilities(
                supportsMkv = true,
                supportsHls = true,
                supportedUriSchemes = setOf("file", "content", "http", "https"),
            ),
    )

internal fun validateAndroidLibVlcOptions(options: LibVlcPlaybackOptions): LibVlcBackendAvailability.Unavailable? =
    when {
        options.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.NATIVE_DOLBY_VISION_UNSUPPORTED,
                "The Android Surface bridge cannot verify native Dolby Vision presentation.",
            )
        options.dolbyVisionPolicy != DolbyVisionPolicy.AUTO ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE,
                "The Android Surface bridge cannot verify a Dolby Vision base-layer or profile-conversion policy.",
            )
        options.frameDeliveryPolicy != LibVlcFrameDeliveryPolicy.AUTO ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.INVALID_RUNTIME,
                "Android uses KMediaVlc's direct Surface transport; select frameDeliveryPolicy=AUTO.",
            )
        options.projection.requiresProjectionRenderer || !options.projectionTextureCrop.isDefaultTextureCrop ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE,
                "The Android KMediaVlc Surface transport does not expose a projection pass yet.",
            )
        options.dynamicRangePolicy != DynamicRangePolicy.AUTO ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE,
                "The Android KMediaVlc Surface transport cannot yet verify FORCE_SDR or REQUIRE_HDR.",
            )
        else -> null
    }
