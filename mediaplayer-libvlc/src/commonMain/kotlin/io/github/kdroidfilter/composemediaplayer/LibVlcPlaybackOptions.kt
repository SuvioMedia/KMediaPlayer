@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("LibVlcPlaybackOptionsKt")

package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * Options for the opt-in KMediaVlc backend.
 *
 * The backend never downloads native code at playback time. [runtimeSource] selects an already
 * bundled or explicitly provisioned runtime. Projection fields initialize their corresponding
 * mutable video-player-state properties.
 *
 * Desktop [LibVlcFrameDeliveryPolicy.AUTO] selects CPU pull when the initial projection needs the
 * shared Skia shader. Android currently exposes libVLC's direct Surface transport and rejects
 * projection, explicit frame-delivery modes, and color policies it cannot verify.
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
    val androidDecodeMode: LibVlcAndroidDecodeMode = LibVlcAndroidDecodeMode.AUTOMATIC,
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

/** Desktop/iOS frame transport policy. Android supports [AUTO] only. */
enum class LibVlcFrameDeliveryPolicy {
    /** Uses the best bounded transport that can satisfy the initial platform settings. */
    AUTO,

    /** libVLC 4 renders GPU textures and pushes ownership notifications. */
    GPU_PUSH,

    /** The adapter pulls copied RGBA8 frames. This route is controlled SDR. */
    CPU_PULL,
}

/** Android decoder policy. Other targets ignore this option. */
enum class LibVlcAndroidDecodeMode {
    /** Uses VLC 4 defaults, including MediaCodec where compatible. */
    AUTOMATIC,

    /** Disables hardware decoding for every media opened by the player. */
    SOFTWARE_ONLY,
}

/** Effective transport selected by a successful backend probe. */
enum class LibVlcFrameDeliveryMode {
    GPU_PUSH,
    CPU_PULL,
    ANDROID_SURFACE,
}

/** Chooses an already-audited native runtime; no implementation downloads one. */
@Stable
expect sealed interface LibVlcRuntimeSource {
    /** Uses the KMediaVlc runtime supplied by this target's optional dependency. */
    data object Bundled : LibVlcRuntimeSource
}

enum class LibVlcBackendUnavailableReason {
    RUNTIME_DEPENDENCY_MISSING,
    UNSUPPORTED_PLATFORM,
    UNSUPPORTED_DEVICE,
    INVALID_RUNTIME,
    GPU_OUTPUT_UNAVAILABLE,
    GPU_PROJECTION_UNAVAILABLE,
    COLOR_POLICY_UNAVAILABLE,
    NATIVE_DOLBY_VISION_UNSUPPORTED,
    INITIALIZATION_FAILED,
}

sealed interface LibVlcBackendAvailability {
    data class Available(
        val backend: String,
        val deliveryMode: LibVlcFrameDeliveryMode,
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

/** Checks target support and the bundled runtime identity without downloading code. */
expect fun inspectLibVlcBackend(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): LibVlcBackendAvailability

/** Creates a KMediaVlc-backed state after the platform probe succeeds. */
expect fun createLibVlcVideoPlayerState(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): VideoPlayerState

@Stable
data class LibVlcVideoPlayerBackend(
    val options: LibVlcPlaybackOptions = LibVlcPlaybackOptions(),
) : VideoPlayerBackend {
    override val info: VideoPlayerBackendInfo = libVlcBackendInfo()

    override fun createPlayerState(): VideoPlayerState = createLibVlcVideoPlayerState(options)
}

fun libVlcVideoPlayerBackend(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): VideoPlayerBackend =
    LibVlcVideoPlayerBackend(options)

@Composable
fun rememberLibVlcVideoPlayerState(options: LibVlcPlaybackOptions = LibVlcPlaybackOptions()): VideoPlayerState {
    val backend = remember(options) { LibVlcVideoPlayerBackend(options) }
    return rememberVideoPlayerState(backend)
}

internal expect fun libVlcBackendInfo(): VideoPlayerBackendInfo

internal fun unavailableLibVlcBackend(
    reason: LibVlcBackendUnavailableReason,
    guidance: String,
): LibVlcBackendAvailability.Unavailable = LibVlcBackendAvailability.Unavailable(reason, guidance)
