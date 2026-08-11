@file:OptIn(
    ExperimentalComposeMediaPlayerBackendApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.libvlc.IosLibVlcEngine
import io.github.kdroidfilter.composemediaplayer.libvlc.IosLibVlcVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.libvlc.inspectIosLibVlcRuntime
import io.github.kdroidfilter.composemediaplayer.libvlc.requireIosLibVlcRuntime

@Stable
actual sealed interface LibVlcRuntimeSource {
    actual data object Bundled : LibVlcRuntimeSource
}

actual fun inspectLibVlcBackend(options: LibVlcPlaybackOptions): LibVlcBackendAvailability {
    validateIosLibVlcOptions(options)?.let { return it }
    return inspectIosLibVlcRuntime()
}

actual fun createLibVlcVideoPlayerState(options: LibVlcPlaybackOptions): VideoPlayerState {
    validateIosLibVlcOptions(options)?.let { unavailable ->
        throw LibVlcBackendUnavailableException(unavailable)
    }
    val runtime = requireIosLibVlcRuntime()
    val engine = IosLibVlcEngine.create(runtime)
    return try {
        IosLibVlcVideoPlayerState(options, engine)
    } catch (failure: Throwable) {
        engine.close()
        throw failure
    }
}

internal actual fun libVlcBackendInfo(): VideoPlayerBackendInfo =
    VideoPlayerBackendInfo(
        id = "libvlc4",
        displayName = "libVLC 4 (iOS)",
        capabilities =
            PlayerCapabilities(
                supportsMkv = true,
                supportsHls = true,
                supportedUriSchemes = setOf("file", "http", "https"),
            ),
    )

internal fun validateIosLibVlcOptions(options: LibVlcPlaybackOptions): LibVlcBackendAvailability.Unavailable? =
    when {
        options.desktopRuntimeDirectory != null ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.INVALID_RUNTIME,
                "desktopRuntimeDirectory is desktop-only; iOS always uses the application-bundled KMediaVlc pod.",
            )
        options.frameDeliveryPolicy == LibVlcFrameDeliveryPolicy.GPU_PUSH ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.GPU_OUTPUT_UNAVAILABLE,
                "The audited iOS KMediaVlc candidate exposes CPU_PULL only.",
            )
        options.projection.requiresProjectionRenderer || !options.projectionTextureCrop.isDefaultTextureCrop ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE,
                "The iOS KMediaVlc CPU-pull surface does not expose a verified projection pass yet.",
            )
        options.dynamicRangePolicy != DynamicRangePolicy.AUTO ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE,
                "The iOS CPU-pull path is RGBA8/sRGB but cannot yet verify FORCE_SDR tone mapping or REQUIRE_HDR.",
            )
        options.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.NATIVE_DOLBY_VISION_UNSUPPORTED,
                "The iOS CPU-pull path cannot verify native Dolby Vision presentation.",
            )
        options.dolbyVisionPolicy != DolbyVisionPolicy.AUTO ->
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.COLOR_POLICY_UNAVAILABLE,
                "The iOS KMediaVlc bridge cannot verify a Dolby Vision base-layer or profile-conversion policy.",
            )
        else -> null
    }
