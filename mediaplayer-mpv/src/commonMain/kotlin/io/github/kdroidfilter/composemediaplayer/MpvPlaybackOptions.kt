package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * Options for the opt-in KMediaMpv backend.
 *
 * The backend is not part of the default player path. Add the
 * `composemediaplayer-mpv` artifact only in applications that select it.
 *
 * @param subtitleFontsDirectory absolute application-private directory containing
 * trusted fonts for external ASS/SSA subtitles. Embedded media fonts do not require it.
 * @param preserveAssStyles preserves script positioning and styling when `true`.
 * @param useEmbeddedFonts allows fonts embedded in the media container.
 * @param maxDesktopRenderPixels upper bound for a software-rendered desktop or iOS frame.
 * @param runtimeSource native libmpv source. [MpvRuntimeSource.Bundled] keeps the
 * verified KMediaMpv runtime on currently published Android and desktop targets.
 * Windows and iOS applications can opt into an app-supplied runtime with
 * [MpvRuntimeSource.System] or [MpvRuntimeSource.ExplicitPath].
 */
@Stable
data class MpvPlaybackOptions(
    val subtitleFontsDirectory: String? = null,
    val preserveAssStyles: Boolean = true,
    val useEmbeddedFonts: Boolean = true,
    val maxDesktopRenderPixels: Int = DEFAULT_MAX_DESKTOP_RENDER_PIXELS,
    val runtimeSource: MpvRuntimeSource = MpvRuntimeSource.Bundled,
) {
    init {
        require(subtitleFontsDirectory == null || subtitleFontsDirectory.isNotBlank()) {
            "subtitleFontsDirectory must be null or a non-blank absolute path."
        }
        require(subtitleFontsDirectory?.contains('\u0000') != true) {
            "subtitleFontsDirectory must not contain NUL."
        }
        require(maxDesktopRenderPixels in 1..MAX_DESKTOP_RENDER_PIXELS) {
            "maxDesktopRenderPixels must be between 1 and $MAX_DESKTOP_RENDER_PIXELS."
        }
    }

    companion object {
        const val DEFAULT_MAX_DESKTOP_RENDER_PIXELS: Int = 16_777_216
        const val MAX_DESKTOP_RENDER_PIXELS: Int = 67_108_864
    }
}

/**
 * Selects who supplies the native libmpv binary.
 *
 * The adapter never downloads native code at runtime.
 */
@Stable
sealed interface MpvRuntimeSource {
    /** Uses the verified KMediaMpv runtime dependency where one is published for this target. */
    data object Bundled : MpvRuntimeSource

    /**
     * Uses the platform loader.
     *
     * Desktop searches conservative libmpv library names. On iOS this resolves
     * symbols from a framework already linked or embedded by the application.
     */
    data object System : MpvRuntimeSource

    /**
     * Loads an absolute native-library path supplied by the application.
     *
     * On iOS the path must point to a code-signed framework binary inside the app.
     */
    data class ExplicitPath(
        val path: String,
    ) : MpvRuntimeSource {
        init {
            require(path.isNotBlank()) { "The libmpv path must not be blank." }
            require('\u0000' !in path) { "The libmpv path must not contain NUL." }
        }
    }
}

enum class MpvBackendUnavailableReason {
    RUNTIME_DEPENDENCY_MISSING,
    UNSUPPORTED_PLATFORM,
    UNSUPPORTED_DEVICE,
    INVALID_RUNTIME,
    INITIALIZATION_FAILED,
}

sealed interface MpvBackendAvailability {
    data class Available(
        val backend: String,
    ) : MpvBackendAvailability

    data class Unavailable(
        val reason: MpvBackendUnavailableReason,
        val guidance: String,
    ) : MpvBackendAvailability
}

class MpvBackendUnavailableException(
    val availability: MpvBackendAvailability.Unavailable,
    cause: Throwable? = null,
) : IllegalStateException(availability.guidance, cause)

/**
 * Checks whether the optional KMediaMpv backend can be created on this target.
 *
 * The probe never downloads a runtime and never falls back to a different backend.
 */
expect fun inspectMpvBackend(options: MpvPlaybackOptions = MpvPlaybackOptions()): MpvBackendAvailability

/**
 * Creates an opt-in KMediaMpv-backed state.
 *
 * Bundled targets receive KMediaMpv transitively. Windows and iOS require the
 * application-supplied runtime selected in [MpvPlaybackOptions.runtimeSource].
 */
expect fun createMpvVideoPlayerState(options: MpvPlaybackOptions = MpvPlaybackOptions()): VideoPlayerState

/**
 * Injectable MPV backend descriptor for application composition roots and DI containers.
 */
@Stable
data class MpvVideoPlayerBackend(
    val options: MpvPlaybackOptions = MpvPlaybackOptions(),
) : VideoPlayerBackend {
    override val info: VideoPlayerBackendInfo = mpvBackendInfo()

    override fun createPlayerState(): VideoPlayerState = createMpvVideoPlayerState(options)
}

/** Creates a backend descriptor without creating or loading the native player yet. */
fun mpvVideoPlayerBackend(options: MpvPlaybackOptions = MpvPlaybackOptions()): VideoPlayerBackend =
    MpvVideoPlayerBackend(options)

/**
 * Remembers an opt-in KMediaMpv-backed player and releases it with the composition.
 */
@Composable
fun rememberMpvVideoPlayerState(options: MpvPlaybackOptions = MpvPlaybackOptions()): VideoPlayerState {
    val backend = remember(options) { MpvVideoPlayerBackend(options) }
    return rememberVideoPlayerState(backend)
}

internal expect fun mpvBackendInfo(): VideoPlayerBackendInfo
