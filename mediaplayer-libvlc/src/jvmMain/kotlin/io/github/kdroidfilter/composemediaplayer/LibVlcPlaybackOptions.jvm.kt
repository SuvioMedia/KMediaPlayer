@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("LibVlcPlaybackOptionsKt")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendAvailability
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendRoutingTier
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.asDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.tao.usesDesktopCanvasProjectionRenderer
import io.github.kdroidfilter.composemediaplayer.libvlc.LibVlcVideoPlayerState
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopRuntime
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopRuntimeResolution
import io.github.shusek.kmediavlc.runtime.desktop.VlcFrameDeliveryMode
import io.github.shusek.kmediavlc.runtime.desktop.VlcRenderEngine
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Stable
actual sealed interface LibVlcRuntimeSource {
    actual data object Bundled : LibVlcRuntimeSource

    /** Uses a desktop resolution already verified or provisioned by the application. */
    data class Resolved(
        val runtime: VlcDesktopRuntimeResolution,
    ) : LibVlcRuntimeSource
}

/** Probes manifests and platform capabilities without extracting or loading native code. */
actual fun inspectLibVlcBackend(options: LibVlcPlaybackOptions): LibVlcBackendAvailability {
    if (options.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE) {
        return unavailableLibVlcBackend(
            LibVlcBackendUnavailableReason.NATIVE_DOLBY_VISION_UNSUPPORTED,
            "Native Dolby Vision presentation is unsupported by the desktop libVLC TextureView backend.",
        )
    }
    val delivery = options.effectiveDeliveryMode()
    if (delivery == VlcFrameDeliveryMode.GPU_PUSH && options.requiresDesktopProjectionRenderer()) {
        return unavailableLibVlcBackend(
            LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE,
            "The libVLC 4 GPU transport does not expose a projection pass yet. " +
                "Use AUTO or CPU_PULL for projected, stereo, rotated, or cropped video.",
        )
    }
    if (delivery == VlcFrameDeliveryMode.CPU_PULL && options.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR) {
        return unavailableLibVlcBackend(
            LibVlcBackendUnavailableReason.GPU_OUTPUT_UNAVAILABLE,
            "The CPU pull route is controlled SDR and cannot satisfy REQUIRE_HDR.",
        )
    }
    val capabilities =
        when (val source = options.runtimeSource) {
            LibVlcRuntimeSource.Bundled -> {
                val inspection = VlcDesktopRuntime.inspectBundled()
                inspection.capabilities().orElse(null)
                    ?: return unavailableLibVlcBackend(
                        if (inspection.unavailableReason().orElse(null)?.name == "UNSUPPORTED_PLATFORM") {
                            LibVlcBackendUnavailableReason.UNSUPPORTED_PLATFORM
                        } else {
                            LibVlcBackendUnavailableReason.RUNTIME_DEPENDENCY_MISSING
                        },
                        "Add the audited kmedia-vlc-runtime-desktop artifact for this platform.",
                    )
            }
            is LibVlcRuntimeSource.Resolved -> source.runtime.capabilities()
        }
    if (delivery !in capabilities.frameDeliveryModes()) {
        return unavailableLibVlcBackend(
            LibVlcBackendUnavailableReason.INVALID_RUNTIME,
            "The selected KMediaVlc payload does not implement the requested frame delivery mode.",
        )
    }
    if (delivery == VlcFrameDeliveryMode.GPU_PUSH) {
        val requiredEngine =
            currentPlatformRenderEngine()
                ?: return unavailableLibVlcBackend(
                    LibVlcBackendUnavailableReason.UNSUPPORTED_PLATFORM,
                    "The libVLC 4 GPU TextureView backend supports Windows, macOS, and Linux only.",
                )
        if (requiredEngine !in capabilities.renderEngines()) {
            return unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.GPU_OUTPUT_UNAVAILABLE,
                "The selected KMediaVlc payload lacks the GPU engine required on this platform.",
            )
        }
    }
    return LibVlcBackendAvailability.Available(
        backend = "KMediaVlc ${capabilities.libVlcVersion()}",
        deliveryMode = delivery.toPublicDeliveryMode(),
    )
}

/** Creates a libVLC 4 state and loads native code only after the availability probe succeeds. */
actual fun createLibVlcVideoPlayerState(options: LibVlcPlaybackOptions): VideoPlayerState {
    val availability = inspectLibVlcBackend(options)
    if (availability is LibVlcBackendAvailability.Unavailable) {
        throw LibVlcBackendUnavailableException(availability)
    }
    val runtime =
        try {
            options.resolveRuntime()
        } catch (failure: RuntimeException) {
            throw LibVlcBackendUnavailableException(
                unavailableLibVlcBackend(
                    LibVlcBackendUnavailableReason.INVALID_RUNTIME,
                    "The audited libVLC 4 runtime could not be resolved.",
                ),
                failure,
            )
        }
    return try {
        LibVlcVideoPlayerState(runtime, options)
    } catch (failure: RuntimeException) {
        throw LibVlcBackendUnavailableException(
            unavailableLibVlcBackend(
                LibVlcBackendUnavailableReason.INITIALIZATION_FAILED,
                "The audited libVLC 4 runtime could not initialize the TextureView backend.",
            ),
            failure,
        )
    }
}

/** Explicit desktop-session backend using GPU TextureView rather than a child window. */
fun libVlcDesktopPlaybackBackend(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): DesktopPlaybackBackend =
    LibVlcVideoPlayerBackend(options).asDesktopPlaybackBackend(
        routingTier = DesktopBackendRoutingTier.LIBVLC_TEXTURE,
        id = "libvlc4-texture",
        displayName = "libVLC 4 TextureView",
        availabilityProbe = {
            when (val availability = inspectLibVlcBackend(options)) {
                is LibVlcBackendAvailability.Available ->
                    DesktopBackendAvailability.Available(
                        "${availability.backend}; ${availability.deliveryMode.name}",
                    )
                is LibVlcBackendAvailability.Unavailable ->
                    DesktopBackendAvailability.Unavailable(
                        reason = availability.reason.name,
                        guidance = availability.guidance,
                    )
            }
        },
    )

internal actual fun libVlcBackendInfo(): VideoPlayerBackendInfo =
    VideoPlayerBackendInfo(
        id = "libvlc4",
        displayName = "libVLC 4 (desktop)",
        capabilities =
            PlayerCapabilities(
                supportsMkv = true,
                supportsHls = true,
                supportedUriSchemes = setOf("file", "http", "https", "rtsp", "rtmp", "smb"),
            ),
    )

internal fun LibVlcPlaybackOptions.effectiveDeliveryMode(): VlcFrameDeliveryMode =
    when (frameDeliveryPolicy) {
        LibVlcFrameDeliveryPolicy.GPU_PUSH -> VlcFrameDeliveryMode.GPU_PUSH
        LibVlcFrameDeliveryPolicy.CPU_PULL -> VlcFrameDeliveryMode.CPU_PULL
        LibVlcFrameDeliveryPolicy.AUTO ->
            if (desktopVideoSurfaceMode == DesktopVideoSurfaceMode.COMPOSE || requiresDesktopProjectionRenderer()) {
                VlcFrameDeliveryMode.CPU_PULL
            } else {
                VlcFrameDeliveryMode.GPU_PUSH
            }
    }

internal fun LibVlcPlaybackOptions.requiresDesktopProjectionRenderer(): Boolean =
    projection.usesDesktopCanvasProjectionRenderer(projectionTextureCrop)

private fun LibVlcPlaybackOptions.resolveRuntime(): VlcDesktopRuntimeResolution =
    when (val source = runtimeSource) {
        LibVlcRuntimeSource.Bundled -> VlcDesktopRuntime.resolveBundled(runtimeExtractionRoot())
        is LibVlcRuntimeSource.Resolved -> source.runtime
    }

private fun LibVlcPlaybackOptions.runtimeExtractionRoot(): Path {
    desktopRuntimeDirectory?.let { configured ->
        val path =
            try {
                Path.of(configured)
            } catch (failure: InvalidPathException) {
                throw IllegalArgumentException("Invalid desktop runtime directory.", failure)
            }
        require(path.isAbsolute) { "The desktop runtime directory must be absolute." }
        return path.normalize()
    }
    val userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
    val os = System.getProperty("os.name", "").lowercase()
    return when {
        os.contains("windows") ->
            System.getenv("LOCALAPPDATA")?.let { Path.of(it) }?.resolve("KMediaVlc/runtime")
                ?: userHome.resolve("AppData/Local/KMediaVlc/runtime")
        os.contains("mac") || os.contains("darwin") -> userHome.resolve("Library/Caches/KMediaVlc/runtime")
        else ->
            System.getenv("XDG_CACHE_HOME")?.let { Path.of(it) }?.resolve("kmediavlc/runtime")
                ?: userHome.resolve(".cache/kmediavlc/runtime")
    }.toAbsolutePath().normalize()
}

private fun currentPlatformRenderEngine(): VlcRenderEngine? = renderEngineForOsName(System.getProperty("os.name", ""))

internal fun renderEngineForOsName(osName: String): VlcRenderEngine? {
    val os = osName.lowercase()
    return when {
        os.contains("windows") -> VlcRenderEngine.D3D11
        os.contains("mac") || os.contains("darwin") -> VlcRenderEngine.OPENGL
        os.contains("linux") -> VlcRenderEngine.GLES2
        else -> null
    }
}

private fun VlcFrameDeliveryMode.toPublicDeliveryMode(): LibVlcFrameDeliveryMode =
    when (this) {
        VlcFrameDeliveryMode.GPU_PUSH -> LibVlcFrameDeliveryMode.GPU_PUSH
        VlcFrameDeliveryMode.CPU_PULL -> LibVlcFrameDeliveryMode.CPU_PULL
    }
