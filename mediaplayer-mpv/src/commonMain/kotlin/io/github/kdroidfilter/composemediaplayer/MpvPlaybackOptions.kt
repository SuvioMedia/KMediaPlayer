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
 * @param androidDecodeMode selects Android video decoding. The default prefers MediaCodec
 * copy-back and automatically falls back to software decoding when hardware decoding fails.
 * @param maxDesktopRenderPixels upper bound for a software-rendered desktop or iOS frame.
 * @param runtimeSource native libmpv source. [MpvRuntimeSource.Bundled] keeps the
 * verified KMediaMpv runtime on currently published Android, desktop, and iOS targets.
 * @param macRenderer selects the macOS video renderer. MoltenVK uses a capability-marked
 * embedded macvk runtime and automatically falls back to OpenGL when that route is unavailable.
 * @param iosRenderer selects the iOS presentation path. MoltenVK uses a capability-marked
 * KMediaMpv runtime to render through Vulkan into a UIKit-owned Metal surface and automatically
 * falls back to the bounded software renderer when unavailable.
 * Windows and iOS applications can opt into an app-supplied runtime with
 * [MpvRuntimeSource.System] or [MpvRuntimeSource.ExplicitPath].
 * @param desktopRuntimeDirectory optional absolute, application-private parent directory used
 * to extract the verified bundled desktop runtime. Desktop applications whose default temporary
 * directory is shared or otherwise rejected can provision a secure directory and pass it here.
 * Other targets ignore this option.
 * @param tlsCertificateAuthorityFile optional absolute PEM CA file for private/self-signed HTTPS
 * sources. Android and Linux otherwise derive a CA bundle from their platform/JVM trust store;
 * Apple and Windows use the system trust store. TLS peer verification cannot be disabled.
 */
@Stable
data class MpvPlaybackOptions(
    val subtitleFontsDirectory: String? = null,
    val preserveAssStyles: Boolean = true,
    val useEmbeddedFonts: Boolean = true,
    val androidDecodeMode: MpvAndroidDecodeMode = MpvAndroidDecodeMode.MEDIA_CODEC_COPY,
    val maxDesktopRenderPixels: Int = DEFAULT_MAX_DESKTOP_RENDER_PIXELS,
    val runtimeSource: MpvRuntimeSource = MpvRuntimeSource.Bundled,
    val desktopRuntimeDirectory: String? = null,
    val tlsCertificateAuthorityFile: String? = null,
    val macRenderer: MpvMacRenderer = MpvMacRenderer.MOLTENVK,
    val iosRenderer: MpvIosRenderer = MpvIosRenderer.MOLTENVK,
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
        require(desktopRuntimeDirectory == null || desktopRuntimeDirectory.isNotBlank()) {
            "desktopRuntimeDirectory must be null or a non-blank absolute path."
        }
        require(desktopRuntimeDirectory?.contains('\u0000') != true) {
            "desktopRuntimeDirectory must not contain NUL."
        }
        require(tlsCertificateAuthorityFile == null || tlsCertificateAuthorityFile.isNotBlank()) {
            "tlsCertificateAuthorityFile must be null or a non-blank absolute path."
        }
        require(tlsCertificateAuthorityFile?.contains('\u0000') != true) {
            "tlsCertificateAuthorityFile must not contain NUL."
        }
    }

    companion object {
        const val DEFAULT_MAX_DESKTOP_RENDER_PIXELS: Int = 16_777_216
        const val MAX_DESKTOP_RENDER_PIXELS: Int = 67_108_864
    }
}

/** Android video decode policy used by the MPV backend. Other targets ignore this option. */
enum class MpvAndroidDecodeMode {
    /** Prefer MediaCodec copy-back with MPV's automatic software fallback. */
    MEDIA_CODEC_COPY,

    /** Disable hardware video decoding. */
    SOFTWARE_ONLY,
}

/** macOS video-output policy. Other targets ignore this option. */
enum class MpvMacRenderer {
    /**
     * Uses mpv's `gpu-next` Vulkan output through MoltenVK in the Compose-owned `NSView`.
     *
     * The route is activated only when libmpv exports KMediaMpv's versioned embedding
     * capability. Missing or failed support falls back to [OPENGL].
     */
    MOLTENVK,

    /** Uses libmpv's public OpenGL render API in the existing native EDR surface. */
    OPENGL,
}

/** iOS video-output policy. Other targets ignore this option. */
enum class MpvIosRenderer {
    /**
     * Uses mpv `gpu-next` through MoltenVK in an application-owned `UIView`/`CAMetalLayer`.
     *
     * The route is activated only when libmpv exports KMediaMpv's versioned iOS embedding
     * capability. Missing or failed support falls back to [SOFTWARE].
     */
    MOLTENVK,

    /** Uses libmpv's bounded BGR0 software render API and presents frames through CoreGraphics. */
    SOFTWARE,
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
 * Android and desktop targets receive KMediaMpv transitively. On iOS the
 * KMediaMpv CocoaPod supplies the code-signed client frameworks.
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
