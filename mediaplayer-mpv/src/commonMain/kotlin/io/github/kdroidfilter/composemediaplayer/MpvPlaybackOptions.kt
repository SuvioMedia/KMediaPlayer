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
 * @param maxDesktopRenderPixels upper bound for a software-rendered desktop frame.
 */
@Stable
data class MpvPlaybackOptions(
    val subtitleFontsDirectory: String? = null,
    val preserveAssStyles: Boolean = true,
    val useEmbeddedFonts: Boolean = true,
    val maxDesktopRenderPixels: Int = DEFAULT_MAX_DESKTOP_RENDER_PIXELS,
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
 * The adapter supplies its matching KMediaMpv runtime transitively.
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
