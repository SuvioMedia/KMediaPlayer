package sample.app.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import io.github.kdroidfilter.composemediaplayer.JvmMediaToolAvailability
import io.github.kdroidfilter.composemediaplayer.JvmMediaTools
import io.github.kdroidfilter.composemediaplayer.MediaSourceSpec
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.PreviewableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.adaptedPlatformDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.inspectMpvBackend
import io.github.kdroidfilter.composemediaplayer.kMediaBridgeDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.kMediaBridgeRemuxDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.kMediaBridgeTranscodeDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.libVlcDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.mpvDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.platformDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.vlcHlsDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackRequest
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackSession
import io.github.kdroidfilter.composemediaplayer.desktop.JvmHttpSeekableMediaDataSourceFactory
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch

internal actual val desktopMkvPlaybackBackendSelectionAvailable: Boolean
    get() = true

private const val MPV_LIBRARY_PATH_PROPERTY = "sample.app.mpvLibraryPath"

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
                "Route: platform direct → KMediaBridge remux → MPV → native libVLC → " +
                    "KMediaBridge legacy transcode. Unavailable stages are skipped.",
            installHint =
                if (hasLibVlcNative || hasKMediaBridge) {
                    null
                } else {
                    "Install VLC from https://www.videolan.org/vlc/"
                },
        ),
        platformOption(),
        libVlcNativeOption(tools),
        mpvOption(),
    )
}

internal actual fun desktopMediaSourceAdapterOptions(): List<DesktopMediaSourceAdapterOption> {
    val tools = JvmMediaTools.query(desktopPipelineExtensions)
    return listOf(
        DesktopMediaSourceAdapterOption(
            adapter = DesktopMediaSourceAdapter.AUTO,
            enabled = true,
            status = "Chooses direct playback first, then a bounded adapter when required.",
        ),
        DesktopMediaSourceAdapterOption(
            adapter = DesktopMediaSourceAdapter.DIRECT,
            enabled = true,
            status = "No remux or transcode. The selected renderer receives the original source.",
        ),
        kMediaBridgeHlsOption(tools),
        vlcHlsOption(tools),
    )
}

@Composable
internal actual fun rememberSampleVideoPlayer(
    backend: DesktopMkvPlaybackBackend,
    sourceAdapter: DesktopMediaSourceAdapter,
    playbackOptions: VideoPlaybackOptions,
): SampleVideoPlayerHandle {
    val mpvOptions = remember { configuredMpvPlaybackOptions() }
    val backends =
        remember(playbackOptions, mpvOptions) {
            listOf(
                platformDesktopPlaybackBackend(playbackOptions = playbackOptions),
                kMediaBridgeRemuxDesktopPlaybackBackend(playbackOptions = playbackOptions),
                mpvDesktopPlaybackBackend(mpvOptions),
                libVlcDesktopPlaybackBackend(playbackOptions = playbackOptions),
                kMediaBridgeTranscodeDesktopPlaybackBackend(playbackOptions = playbackOptions),
                adaptedPlatformDesktopPlaybackBackend(playbackOptions = playbackOptions),
                kMediaBridgeDesktopPlaybackBackend(playbackOptions = playbackOptions),
                vlcHlsDesktopPlaybackBackend(playbackOptions = playbackOptions),
            )
        }
    val session =
        remember(backends) {
            DesktopPlaybackSession(
                backends = backends,
                seekableMediaDataSourceFactory = JvmHttpSeekableMediaDataSourceFactory(),
            )
        }
    val scope = rememberCoroutineScope()
    val selectedBackend by rememberUpdatedState(backend)
    val selectedSourceAdapter by rememberUpdatedState(sourceAdapter)
    val activePlayer by session.playerState.collectAsState()
    val placeholder =
        remember {
            PreviewableVideoPlayerState(
                hasMedia = false,
                isPlaying = false,
                isLoading = false,
            )
        }

    DisposableEffect(session) {
        onDispose(session::close)
    }
    LaunchedEffect(backend, sourceAdapter, session) {
        applyDesktopPlaybackSelection(backend, sourceAdapter)
        if (session.playerState.value != null) {
            runCatching { session.switchBackend(backend.sessionBackendId(sourceAdapter)) }
        }
    }

    val playerState = activePlayer ?: placeholder
    return remember(playerState, session, scope) {
        SampleVideoPlayerHandle(
            playerState = playerState,
            openUriAction = { uri, initial ->
                scope.launch {
                    applyDesktopPlaybackSelection(selectedBackend, selectedSourceAdapter)
                    runCatching {
                        session.open(
                            request =
                                DesktopPlaybackRequest(
                                    source = MediaSourceSpec(uri),
                                    initialPlayerState = initial,
                                ),
                            backendId = selectedBackend.sessionBackendId(selectedSourceAdapter),
                        )
                    }
                }
            },
            openFileAction = { file, initial ->
                scope.launch {
                    applyDesktopPlaybackSelection(selectedBackend, selectedSourceAdapter)
                    runCatching {
                        session.open(
                            request =
                                DesktopPlaybackRequest(
                                    source = MediaSourceSpec(file.path),
                                    initialPlayerState = initial,
                                ),
                            backendId = selectedBackend.sessionBackendId(selectedSourceAdapter),
                        )
                    }
                }
            },
            surfaceAttachedAction = session::notifySurfaceAttached,
        )
    }
}

private fun DesktopMkvPlaybackBackend.sessionBackendId(sourceAdapter: DesktopMediaSourceAdapter): String? =
    when (this) {
        DesktopMkvPlaybackBackend.MPV -> "mpv"
        DesktopMkvPlaybackBackend.LIBVLC_NATIVE -> "libvlc"
        DesktopMkvPlaybackBackend.PLATFORM -> sourceAdapter.platformBackendId()
        DesktopMkvPlaybackBackend.AUTO ->
            when (sourceAdapter) {
                DesktopMediaSourceAdapter.AUTO -> null
                else -> sourceAdapter.platformBackendId()
            }
    }

private fun DesktopMediaSourceAdapter.platformBackendId(): String =
    when (this) {
        DesktopMediaSourceAdapter.AUTO -> "platform-adapted"
        DesktopMediaSourceAdapter.DIRECT -> "platform"
        DesktopMediaSourceAdapter.KMEDIA_BRIDGE -> "kmediabridge"
        DesktopMediaSourceAdapter.VLC_HLS -> "vlc-hls"
    }

internal actual fun applyDesktopPlaybackSelection(
    backend: DesktopMkvPlaybackBackend,
    sourceAdapter: DesktopMediaSourceAdapter,
) {
    // Selection is carried by the backend's typed VideoPlaybackOptions. Process-wide properties
    // would couple independent players and make a transactional switch race with the old state.
}

internal actual fun restoreDesktopMkvPlaybackBackend() = Unit

private fun platformOption(): DesktopMkvPlaybackBackendOption =
    DesktopMkvPlaybackBackendOption(
        backend = DesktopMkvPlaybackBackend.PLATFORM,
        enabled = true,
        status =
            if (isMacOs()) {
                "Uses AVFoundation/AppKit as renderer. The separate source-adapter selection decides direct/remux/transcode."
            } else {
                "Uses the native platform framework as renderer; source adaptation is selected separately."
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

private fun kMediaBridgeHlsOption(tools: JvmMediaToolAvailability): DesktopMediaSourceAdapterOption {
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

    return DesktopMediaSourceAdapterOption(
        adapter = DesktopMediaSourceAdapter.KMEDIA_BRIDGE,
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

private fun vlcHlsOption(tools: JvmMediaToolAvailability): DesktopMediaSourceAdapterOption =
    DesktopMediaSourceAdapterOption(
        adapter = DesktopMediaSourceAdapter.VLC_HLS,
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
