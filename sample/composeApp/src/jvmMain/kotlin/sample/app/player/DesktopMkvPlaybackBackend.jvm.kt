package sample.app.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import io.github.kdroidfilter.composemediaplayer.DesktopVideoBackend
import io.github.kdroidfilter.composemediaplayer.JvmMediaToolAvailability
import io.github.kdroidfilter.composemediaplayer.JvmMediaTools
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.inspectMpvBackend
import io.github.kdroidfilter.composemediaplayer.rememberMpvVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

internal actual val desktopMkvPlaybackBackendSelectionAvailable: Boolean
    get() = true

private const val FALLBACK_BACKEND_PROPERTY = "composemediaplayer.fallbackBackend"
private const val HLS_BACKEND_PROPERTY = "composemediaplayer.hlsFallbackBackend"
private const val MPV_LIBRARY_PATH_PROPERTY = "sample.app.mpvLibraryPath"

private var capturedOriginalValues = false
private var originalFallbackBackend: String? = null
private var originalHlsBackend: String? = null

internal actual fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption> {
    if (!desktopMkvPlaybackBackendSelectionAvailable) return emptyList()

    val tools = JvmMediaTools.query(desktopPipelineExtensions)
    val hasLibVlcNative = tools.libVlc.available
    val hasKMediaBridge = tools.kMediaBridge.available && tools.kMediaBridgeProbe.available

    return listOf(
        DesktopMkvPlaybackBackendOption(
            backend = DesktopMkvPlaybackBackend.AUTO,
            enabled = true,
            status =
                if (hasLibVlcNative) {
                    "Uses native libVLC for legacy AVI/WMV and KMediaBridge for bounded AVFoundation fallbacks."
                } else if (hasKMediaBridge) {
                    "Uses bundled FFmpeg through KMediaBridge; on macOS legacy AVI/WMV is transcoded for AVFoundation."
                } else {
                    "No MKV helper detected; native formats can still play."
                },
            installHint =
                if (hasLibVlcNative || hasKMediaBridge) {
                    null
                } else {
                    "Install VLC from https://www.videolan.org/vlc/"
                },
        ),
        platformOption(),
        libVlcNativeOption(tools),
        kMediaBridgeHlsOption(tools),
        vlcHlsOption(tools),
        mpvOption(),
    )
}

@Composable
internal actual fun rememberSampleVideoPlayerState(
    backend: DesktopMkvPlaybackBackend,
    playbackOptions: VideoPlaybackOptions,
): VideoPlayerState =
    key(backend) {
        if (backend == DesktopMkvPlaybackBackend.MPV) {
            val options = remember { configuredMpvPlaybackOptions() }
            rememberMpvVideoPlayerState(options)
        } else {
            val selectedOptions =
                remember(playbackOptions, backend) {
                    playbackOptions.copy(desktopVideoBackend = backend.toDesktopVideoBackend())
                }
            rememberVideoPlayerState(playbackOptions = selectedOptions)
        }
    }

internal actual fun applyDesktopMkvPlaybackBackend(backend: DesktopMkvPlaybackBackend) {
    if (!desktopMkvPlaybackBackendSelectionAvailable) return
    captureOriginalValues()

    when (backend) {
        DesktopMkvPlaybackBackend.AUTO -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "auto")
            System.setProperty(HLS_BACKEND_PROPERTY, "auto")
        }
        DesktopMkvPlaybackBackend.PLATFORM -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "platform")
            System.clearProperty(HLS_BACKEND_PROPERTY)
        }
        DesktopMkvPlaybackBackend.LIBVLC_NATIVE -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "libvlc-native-view")
            System.clearProperty(HLS_BACKEND_PROPERTY)
        }
        DesktopMkvPlaybackBackend.KMEDIA_BRIDGE_HLS -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "kmediabridge")
            System.setProperty(HLS_BACKEND_PROPERTY, "kmediabridge")
        }
        DesktopMkvPlaybackBackend.VLC_HLS -> {
            System.setProperty(FALLBACK_BACKEND_PROPERTY, "vlc")
            System.setProperty(HLS_BACKEND_PROPERTY, "vlc")
        }
        DesktopMkvPlaybackBackend.MPV -> {
            System.clearProperty(FALLBACK_BACKEND_PROPERTY)
            System.clearProperty(HLS_BACKEND_PROPERTY)
        }
    }
}

internal actual fun restoreDesktopMkvPlaybackBackend() {
    if (!capturedOriginalValues) return
    restoreProperty(FALLBACK_BACKEND_PROPERTY, originalFallbackBackend)
    restoreProperty(HLS_BACKEND_PROPERTY, originalHlsBackend)
    capturedOriginalValues = false
    originalFallbackBackend = null
    originalHlsBackend = null
}

private fun captureOriginalValues() {
    if (capturedOriginalValues) return
    originalFallbackBackend = System.getProperty(FALLBACK_BACKEND_PROPERTY)
    originalHlsBackend = System.getProperty(HLS_BACKEND_PROPERTY)
    capturedOriginalValues = true
}

private fun restoreProperty(
    key: String,
    value: String?,
) {
    if (value == null) {
        System.clearProperty(key)
    } else {
        System.setProperty(key, value)
    }
}

private fun DesktopMkvPlaybackBackend.toDesktopVideoBackend(): DesktopVideoBackend =
    when (this) {
        DesktopMkvPlaybackBackend.AUTO,
        DesktopMkvPlaybackBackend.KMEDIA_BRIDGE_HLS,
        DesktopMkvPlaybackBackend.VLC_HLS,
        -> DesktopVideoBackend.AUTO
        DesktopMkvPlaybackBackend.PLATFORM -> DesktopVideoBackend.PLATFORM
        DesktopMkvPlaybackBackend.LIBVLC_NATIVE -> DesktopVideoBackend.LIBVLC_NATIVE
        DesktopMkvPlaybackBackend.MPV -> error("MPV uses its own player state.")
    }

private fun platformOption(): DesktopMkvPlaybackBackendOption =
    DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.PLATFORM,
        enabled = true,
        status =
            if (isMacOs()) {
                "Forces AVFoundation without libVLC or container fallback. Legacy AVI/WMV may be rejected."
            } else {
                "Forces the native platform media framework without optional fallbacks."
            },
    )

private fun libVlcNativeOption(tools: JvmMediaToolAvailability): DesktopMkvPlaybackBackendOption {
    val enabled = tools.libVlc.available
    val status =
        when {
            enabled && isLinux() -> "Ready. VLC/libVLC detected. Uses an X11/XWayland native window, not native Wayland."
            enabled -> "Ready. VLC/libVLC detected."
            else -> "Requires VLC/libVLC."
        }

    return DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.LIBVLC_NATIVE,
        enabled = enabled,
        status = status,
        installHint =
            if (enabled) {
                "VLC: ${tools.vlc.path ?: tools.libVlc.path}. Linux direct rendering requires X11/XWayland."
            } else {
                "Install VLC from https://www.videolan.org/vlc/."
            },
    )
}

private fun kMediaBridgeHlsOption(tools: JvmMediaToolAvailability): DesktopMkvPlaybackBackendOption {
    val enabled = tools.kMediaBridge.available && tools.kMediaBridgeProbe.available
    val legacyMacDetail =
        if (isMacOs()) {
            " Legacy AVI/WMV is decoded in-process and transcoded to AVC/AAC for AVFoundation."
        } else {
            ""
        }
    val status =
        when {
            !tools.kMediaBridge.available -> "The configured KMediaBridge runtime is unavailable for this platform."
            !tools.kMediaBridgeProbe.available -> "The KMediaBridge runtime does not expose its typed probe API."
            tools.kMediaBridgeHdrToSdrToneMapping.available && tools.kMediaBridgeSubtitleBurnIn.available ->
                "Ready for compatible MKV/WebM: bounded remux, HDR-to-SDR tone mapping, and text subtitle " +
                    "burn-in.$legacyMacDetail"
            tools.kMediaBridgeHdrToSdrToneMapping.available ->
                "Ready for compatible MKV/WebM: bounded remux and controlled HDR-to-SDR tone mapping." +
                    legacyMacDetail
            tools.kMediaBridgeSubtitleBurnIn.available ->
                "Ready for compatible MKV/WebM: bounded remux and text subtitle burn-in.$legacyMacDetail"
            else -> "Ready for compatible MKV/WebM: bounded remux without external executables.$legacyMacDetail"
        }

    return DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.KMEDIA_BRIDGE_HLS,
        enabled = enabled,
        status = status,
        installHint =
            if (enabled) {
                tools.kMediaBridge.detail
            } else {
                "Add the matching kmedia-bridge-ffmpeg-runtime-desktop artifact to the runtime classpath."
            },
    )
}

private fun vlcHlsOption(tools: JvmMediaToolAvailability): DesktopMkvPlaybackBackendOption =
    DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.VLC_HLS,
        enabled = tools.vlc.available,
        status =
            if (tools.vlc.available) {
                if (isMacOs()) {
                    "Ready. VLC adapts the source to HLS; AVFoundation renders it in the native AppKit window."
                } else {
                    "Ready. VLC adapts the source to HLS for the platform player."
                }
            } else {
                "Requires VLC."
            },
        installHint =
            if (tools.vlc.available) {
                "VLC: ${tools.vlc.path}"
            } else {
                "Install VLC from https://www.videolan.org/vlc/"
            },
    )

private fun mpvOption(): DesktopMkvPlaybackBackendOption {
    val availability = inspectMpvBackend(configuredMpvPlaybackOptions())
    return when (availability) {
        is MpvBackendAvailability.Available ->
            DesktopMkvPlaybackBackendOption(
                backend = DesktopMkvPlaybackBackend.MPV,
                enabled = true,
                status = "Ready. Direct playback through ${availability.backend}; AVI/WMV does not use AVFoundation.",
            )
        is MpvBackendAvailability.Unavailable ->
            DesktopMkvPlaybackBackendOption(
                backend = DesktopMkvPlaybackBackend.MPV,
                enabled = false,
                status = "MPV runtime unavailable (${availability.reason}).",
                installHint = availability.guidance,
            )
    }
}

private fun configuredMpvPlaybackOptions(): MpvPlaybackOptions =
    System
        .getProperty(MPV_LIBRARY_PATH_PROPERTY)
        ?.takeIf(String::isNotBlank)
        ?.let(MpvRuntimeSource::ExplicitPath)
        ?.let { runtimeSource -> MpvPlaybackOptions(runtimeSource = runtimeSource) }
        ?: MpvPlaybackOptions()

private fun isMacOs(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return osName.contains("mac") || osName.contains("darwin")
}

private fun isLinux(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return osName.contains("linux")
}
