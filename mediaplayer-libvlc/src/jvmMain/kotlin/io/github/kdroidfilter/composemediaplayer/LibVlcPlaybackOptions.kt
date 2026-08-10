package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
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

/**
 * Options for the completely optional desktop libVLC 4 backend.
 *
 * [projection], [projectionView], [projectionViewControlMode], and [projectionTextureCrop]
 * initialize the corresponding mutable [VideoPlayerState] properties.
 * [LibVlcFrameDeliveryPolicy.AUTO] selects the SDR CPU-pull route when the initial settings require
 * a projection shader. Applications that change projection at runtime must select
 * [LibVlcFrameDeliveryPolicy.CPU_PULL] up front; an already-created GPU player fails closed instead
 * of presenting an unprojected frame.
 */
@Stable
data class LibVlcPlaybackOptions(
    val runtimeSource: LibVlcRuntimeSource = LibVlcRuntimeSource.Bundled,
    val desktopRuntimeDirectory: String? = null,
    val frameDeliveryPolicy: LibVlcFrameDeliveryPolicy = LibVlcFrameDeliveryPolicy.AUTO,
    val dynamicRangePolicy: DynamicRangePolicy = DynamicRangePolicy.AUTO,
    val dolbyVisionPolicy: DolbyVisionPolicy = DolbyVisionPolicy.AUTO,
    val projection: VideoProjectionSettings = VideoProjectionSettings(),
    val projectionView: VideoProjectionViewSettings = VideoProjectionViewSettings(),
    val projectionViewControlMode: VideoProjectionViewControlMode = VideoProjectionViewControlMode.AUTO,
    val projectionTextureCrop: VideoTextureCrop = VideoTextureCrop(),
    val desktopVideoSurfaceMode: DesktopVideoSurfaceMode =
        DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
) {
    init {
        require(desktopRuntimeDirectory == null || desktopRuntimeDirectory.isNotBlank()) {
            "desktopRuntimeDirectory must be null or a non-blank absolute path."
        }
        require(desktopRuntimeDirectory?.contains('\u0000') != true) {
            "desktopRuntimeDirectory must not contain NUL."
        }
        require(
            dynamicRangePolicy != DynamicRangePolicy.REQUIRE_HDR ||
                desktopVideoSurfaceMode != DesktopVideoSurfaceMode.COMPOSE,
        ) {
            "REQUIRE_HDR cannot use the explicit CPU/SDR COMPOSE surface."
        }
    }
}

/** Selects the bounded producer/consumer transport. */
enum class LibVlcFrameDeliveryPolicy {
    /** GPU push for flat TextureView playback; CPU pull for COMPOSE or projected/cropped video. */
    AUTO,

    /** libVLC 4 renders GPU textures and only pushes non-owning notifications. */
    GPU_PUSH,

    /** The adapter pulls copied RGBA8 frames. This route is always SDR. */
    CPU_PULL,
}

/** Chooses the already-audited native runtime; the adapter never downloads one. */
@Stable
sealed interface LibVlcRuntimeSource {
    /** Extracts the pinned KMediaVlc payload from the optional runtime artifact. */
    data object Bundled : LibVlcRuntimeSource

    /** Uses a resolution already verified or provisioned by the application. */
    data class Resolved(
        val runtime: VlcDesktopRuntimeResolution,
    ) : LibVlcRuntimeSource
}

enum class LibVlcBackendUnavailableReason {
    RUNTIME_DEPENDENCY_MISSING,
    UNSUPPORTED_PLATFORM,
    INVALID_RUNTIME,
    GPU_OUTPUT_UNAVAILABLE,
    GPU_PROJECTION_UNAVAILABLE,
    NATIVE_DOLBY_VISION_UNSUPPORTED,
    INITIALIZATION_FAILED,
}

sealed interface LibVlcBackendAvailability {
    data class Available(
        val backend: String,
        val deliveryMode: VlcFrameDeliveryMode,
    ) : LibVlcBackendAvailability

    data class Unavailable(
        val reason: LibVlcBackendUnavailableReason,
        val guidance: String,
    ) : LibVlcBackendAvailability
}

class LibVlcBackendUnavailableException(
    val availability: LibVlcBackendAvailability.Unavailable,
    cause: Throwable? = null,
) : IllegalStateException(availability.guidance, cause)

/** Probes manifests and platform capabilities without extracting or loading native code. */
fun inspectLibVlcBackend(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): LibVlcBackendAvailability {
    if (options.dolbyVisionPolicy == DolbyVisionPolicy.REQUIRE_NATIVE) {
        return unavailable(
            LibVlcBackendUnavailableReason.NATIVE_DOLBY_VISION_UNSUPPORTED,
            "Native Dolby Vision presentation is unsupported by the desktop libVLC TextureView backend.",
        )
    }
    val delivery = options.effectiveDeliveryMode()
    if (delivery == VlcFrameDeliveryMode.GPU_PUSH && options.requiresDesktopProjectionRenderer()) {
        return unavailable(
            LibVlcBackendUnavailableReason.GPU_PROJECTION_UNAVAILABLE,
            "The libVLC 4 GPU transport does not expose a projection pass yet. " +
                "Use AUTO or CPU_PULL for projected, stereo, rotated, or cropped video.",
        )
    }
    if (delivery == VlcFrameDeliveryMode.CPU_PULL && options.dynamicRangePolicy == DynamicRangePolicy.REQUIRE_HDR) {
        return unavailable(
            LibVlcBackendUnavailableReason.GPU_OUTPUT_UNAVAILABLE,
            "The CPU pull route is controlled SDR and cannot satisfy REQUIRE_HDR.",
        )
    }
    val capabilities =
        when (val source = options.runtimeSource) {
            LibVlcRuntimeSource.Bundled -> {
                val inspection = VlcDesktopRuntime.inspectBundled()
                inspection.capabilities().orElse(null)
                    ?: return unavailable(
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
        return unavailable(
            LibVlcBackendUnavailableReason.INVALID_RUNTIME,
            "The selected KMediaVlc payload does not implement the requested frame delivery mode.",
        )
    }
    if (delivery == VlcFrameDeliveryMode.GPU_PUSH) {
        val requiredEngine =
            currentPlatformRenderEngine()
                ?: return unavailable(
                    LibVlcBackendUnavailableReason.UNSUPPORTED_PLATFORM,
                    "The libVLC 4 GPU TextureView backend supports Windows, macOS, and Linux only.",
                )
        if (requiredEngine !in capabilities.renderEngines()) {
            return unavailable(
                LibVlcBackendUnavailableReason.GPU_OUTPUT_UNAVAILABLE,
                "The selected KMediaVlc payload lacks the GPU engine required on this platform.",
            )
        }
    }
    return LibVlcBackendAvailability.Available(
        backend = "KMediaVlc ${capabilities.libVlcVersion()}",
        deliveryMode = delivery,
    )
}

/** Creates a libVLC 4 state and loads native code only after the availability probe succeeds. */
fun createLibVlcVideoPlayerState(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): VideoPlayerState {
    val availability = inspectLibVlcBackend(options)
    if (availability is LibVlcBackendAvailability.Unavailable) {
        throw LibVlcBackendUnavailableException(availability)
    }
    val runtime =
        try {
            options.resolveRuntime()
        } catch (failure: RuntimeException) {
            throw LibVlcBackendUnavailableException(
                unavailable(
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
            unavailable(
                LibVlcBackendUnavailableReason.INITIALIZATION_FAILED,
                "The audited libVLC 4 runtime could not initialize the TextureView backend.",
            ),
            failure,
        )
    }
}

@Stable
data class LibVlcVideoPlayerBackend(
    val options: LibVlcPlaybackOptions = LibVlcPlaybackOptions(),
) : VideoPlayerBackend {
    override val info: VideoPlayerBackendInfo = libVlcBackendInfo()

    override fun createPlayerState(): VideoPlayerState = createLibVlcVideoPlayerState(options)
}

fun libVlcVideoPlayerBackend(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): VideoPlayerBackend =
    LibVlcVideoPlayerBackend(options)

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

@Composable
fun rememberLibVlcVideoPlayerState(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): VideoPlayerState {
    val backend = remember(options) { LibVlcVideoPlayerBackend(options) }
    return rememberVideoPlayerState(backend)
}

private fun libVlcBackendInfo(): VideoPlayerBackendInfo =
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

private fun unavailable(
    reason: LibVlcBackendUnavailableReason,
    guidance: String,
): LibVlcBackendAvailability.Unavailable = LibVlcBackendAvailability.Unavailable(reason, guidance)
