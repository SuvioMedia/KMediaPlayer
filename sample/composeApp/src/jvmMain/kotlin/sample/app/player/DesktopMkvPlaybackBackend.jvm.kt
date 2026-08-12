package sample.app.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.kdroidfilter.composemediaplayer.JvmMediaToolAvailability
import io.github.kdroidfilter.composemediaplayer.JvmMediaTools
import io.github.kdroidfilter.composemediaplayer.LibVlcBackendAvailability
import io.github.kdroidfilter.composemediaplayer.LibVlcBackendUnavailableReason
import io.github.kdroidfilter.composemediaplayer.LibVlcPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.LibVlcRuntimeSource
import io.github.kdroidfilter.composemediaplayer.MediaSourceSpec
import io.github.kdroidfilter.composemediaplayer.MpvBackendAvailability
import io.github.kdroidfilter.composemediaplayer.MpvPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.MpvRuntimeSource
import io.github.kdroidfilter.composemediaplayer.PreviewableVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlaybackOptions
import io.github.kdroidfilter.composemediaplayer.VideoPlayerBackend
import io.github.kdroidfilter.composemediaplayer.adaptedPlatformDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.inspectLibVlcBackend
import io.github.kdroidfilter.composemediaplayer.inspectMpvBackend
import io.github.kdroidfilter.composemediaplayer.kMediaBridgeDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.kMediaBridgeRemuxDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.kMediaBridgeTranscodeDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.libVlcDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.libVlcVideoPlayerBackend
import io.github.kdroidfilter.composemediaplayer.mpvDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.platformDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.vlcHlsDesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackRequest
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackSession
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackSessionState
import io.github.kdroidfilter.composemediaplayer.desktop.JvmHttpHlsMediaProxyFactory
import io.github.kdroidfilter.composemediaplayer.desktop.JvmHttpSeekableMediaDataSourceFactory
import io.github.shusek.kmediavlc.runtime.desktop.VlcDesktopRuntimeResolution
import io.github.shusek.kmediavlc.runtime.desktop.VlcFrameDeliveryMode
import io.github.shusek.kmediavlc.runtime.desktop.VlcRenderEngine
import io.github.shusek.kmediavlc.runtime.desktop.VlcRuntimeCapabilities
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal actual val desktopMkvPlaybackBackendSelectionAvailable: Boolean
    get() = true

internal class DesktopSamplePlaybackSurfaceHost(
    val session: DesktopPlaybackSession,
) : SamplePlaybackSurfaceHost

private const val KMEDIAVLC_RUNTIME_DIRECTORY_PROPERTY = "sample.app.kMediaVlcRuntimeDirectory"
private const val KMEDIAVLC_RUNTIME_ID = "sample-explicit-kmediavlc-4"
private const val KMEDIAVLC_LIBVLC_VERSION = "4.0.0-dev"
private const val KMEDIAVLC_LIBVLC_REVISION = "b5536cdea24b313ba9215eacfbd7fa3295d7f3ee"
private const val MPV_LIBRARY_PATH_PROPERTY = "sample.app.mpvLibraryPath"

internal actual fun desktopMkvPlaybackBackendOptions(): List<DesktopMkvPlaybackBackendOption> {
    if (!desktopMkvPlaybackBackendSelectionAvailable) return emptyList()

    val tools = JvmMediaTools.query(desktopPipelineExtensions)
    val libVlcAvailability = configuredLibVlcBackendAvailability()
    val hasLibVlcNative = libVlcAvailability is LibVlcBackendAvailability.Available
    val hasKMediaBridge = tools.kMediaBridge.available && tools.kMediaBridgeProbe.available

    return listOf(
        DesktopMkvPlaybackBackendOption(
            backend = DesktopMkvPlaybackBackend.AUTO,
            enabled = true,
            status =
                "Route: platform direct → KMediaBridge remux → MPV → KMediaVlc TextureView → " +
                    "KMediaBridge legacy transcode. Unavailable stages are skipped.",
            installHint =
                if (hasLibVlcNative || hasKMediaBridge) {
                    null
                } else {
                    "Install VLC from https://www.videolan.org/vlc/"
                },
        ),
        platformOption(),
        libVlcNativeOption(libVlcAvailability),
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
    val libVlcOptions = remember(playbackOptions) { configuredLibVlcPlaybackOptions(playbackOptions) }
    val backends =
        remember(playbackOptions, mpvOptions, libVlcOptions) {
            listOf(
                platformDesktopPlaybackBackend(playbackOptions = playbackOptions),
                kMediaBridgeRemuxDesktopPlaybackBackend(playbackOptions = playbackOptions),
                mpvDesktopPlaybackBackend(mpvOptions),
                libVlcDesktopPlaybackBackend(libVlcOptions),
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
                hlsMediaProxyFactory =
                    JvmHttpHlsMediaProxyFactory().takeIf { isMacOs() },
            )
        }
    val scope = rememberCoroutineScope()
    val selectedBackend by rememberUpdatedState(backend)
    val selectedSourceAdapter by rememberUpdatedState(sourceAdapter)
    val activePlayer by session.playerState.collectAsState()
    val sessionState by session.state.collectAsState()
    var surfaceTransitionPending by remember(session) { mutableStateOf(false) }
    var playbackTransitionError by remember(session) { mutableStateOf<String?>(null) }
    val sessionIsOpeningOrSwitching =
        sessionState is DesktopPlaybackSessionState.Opening ||
            sessionState is DesktopPlaybackSessionState.Switching
    LaunchedEffect(sessionState) {
        when (sessionState) {
            is DesktopPlaybackSessionState.Opening,
            is DesktopPlaybackSessionState.Switching,
            -> {
                surfaceTransitionPending = true
                playbackTransitionError = null
            }

            is DesktopPlaybackSessionState.Failed -> {
                surfaceTransitionPending = false
                playbackTransitionError =
                    if (session.playerState.value == null) {
                        "No desktop backend could open this source."
                    } else {
                        "The selected backend cannot open this source. The previous backend is still active."
                    }
            }

            DesktopPlaybackSessionState.Idle,
            DesktopPlaybackSessionState.Closed,
            -> {
                surfaceTransitionPending = false
                playbackTransitionError = null
            }

            is DesktopPlaybackSessionState.Ready -> playbackTransitionError = null
        }
    }
    val isPlaybackTransitioning = sessionIsOpeningOrSwitching || surfaceTransitionPending
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
    return remember(playerState, session, scope, isPlaybackTransitioning, playbackTransitionError) {
        SampleVideoPlayerHandle(
            playerState = playerState,
            isPlaybackTransitioning = isPlaybackTransitioning,
            playbackTransitionError = playbackTransitionError,
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
            surfaceAttachedAction = { attachedPlayer ->
                if (session.playerState.value === attachedPlayer) {
                    surfaceTransitionPending = false
                }
            },
            clearPlaybackTransitionErrorAction = { playbackTransitionError = null },
            playbackSurfaceHost = DesktopSamplePlaybackSurfaceHost(session),
        )
    }
}

private fun DesktopMkvPlaybackBackend.sessionBackendId(sourceAdapter: DesktopMediaSourceAdapter): String? =
    when (this) {
        DesktopMkvPlaybackBackend.MPV -> "mpv"
        DesktopMkvPlaybackBackend.LIBVLC_NATIVE -> "libvlc4-texture"
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

internal fun sampleLibVlcVideoPlayerBackend(playbackOptions: VideoPlaybackOptions): VideoPlayerBackend =
    libVlcVideoPlayerBackend(configuredLibVlcPlaybackOptions(playbackOptions))

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

private fun libVlcNativeOption(availability: LibVlcBackendAvailability): DesktopMkvPlaybackBackendOption =
    when (availability) {
        is LibVlcBackendAvailability.Available ->
            DesktopMkvPlaybackBackendOption(
                backend = DesktopMkvPlaybackBackend.LIBVLC_NATIVE,
                enabled = true,
                status =
                    "Ready. ${availability.backend} renders through the Nucleus TextureView " +
                        "(${availability.deliveryMode}).",
                installHint =
                    System.getProperty(KMEDIAVLC_RUNTIME_DIRECTORY_PROPERTY)
                        ?.takeIf(String::isNotBlank)
                        ?.let { "Explicit audited runtime: $it" },
            )
        is LibVlcBackendAvailability.Unavailable ->
            DesktopMkvPlaybackBackendOption(
                backend = DesktopMkvPlaybackBackend.LIBVLC_NATIVE,
                enabled = false,
                status = "KMediaVlc unavailable (${availability.reason}).",
                installHint = availability.guidance,
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

private fun configuredLibVlcBackendAvailability(): LibVlcBackendAvailability =
    runCatching { inspectLibVlcBackend(configuredLibVlcPlaybackOptions()) }
        .getOrElse {
            LibVlcBackendAvailability.Unavailable(
                reason = LibVlcBackendUnavailableReason.INVALID_RUNTIME,
                guidance =
                    "The configured KMediaVlc runtime is invalid. Use a complete audited runtime " +
                        "directory or the bundled desktop runtime.",
            )
        }

private fun configuredLibVlcPlaybackOptions(): LibVlcPlaybackOptions =
    LibVlcPlaybackOptions(runtimeSource = configuredLibVlcRuntimeSource())

private fun configuredLibVlcPlaybackOptions(playbackOptions: VideoPlaybackOptions): LibVlcPlaybackOptions =
    LibVlcPlaybackOptions(
        runtimeSource = configuredLibVlcRuntimeSource(),
        dynamicRangePolicy = playbackOptions.dynamicRangePolicy,
        dolbyVisionPolicy = playbackOptions.dolbyVisionPolicy,
        projection = playbackOptions.projection,
        projectionView = playbackOptions.projectionView,
        projectionViewControlMode = playbackOptions.projectionViewControlMode,
        projectionTextureCrop = playbackOptions.projectionTextureCrop,
        desktopVideoSurfaceMode = playbackOptions.desktopVideoSurfaceMode,
    )

private fun configuredLibVlcRuntimeSource(): LibVlcRuntimeSource {
    val configuredDirectory =
        System.getProperty(KMEDIAVLC_RUNTIME_DIRECTORY_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: return LibVlcRuntimeSource.Bundled
    val runtimeDirectory = Path.of(configuredDirectory).toAbsolutePath().normalize()
    require(Files.isDirectory(runtimeDirectory) && !Files.isSymbolicLink(runtimeDirectory)) {
        "$KMEDIAVLC_RUNTIME_DIRECTORY_PROPERTY must name a plain runtime directory."
    }
    val layout = currentKMediaVlcRuntimeLayout()
    val bridge = requiredRuntimeFile(runtimeDirectory, layout.bridgePath)
    val libVlc = requiredRuntimeFile(runtimeDirectory, layout.libVlcPath)
    val plugins = runtimeDirectory.resolve("lib/vlc/plugins").normalize()
    require(Files.isDirectory(plugins) && !Files.isSymbolicLink(plugins)) {
        "The configured KMediaVlc runtime has no plain lib/vlc/plugins directory."
    }
    return LibVlcRuntimeSource.Resolved(
        VlcDesktopRuntimeResolution(
            bridge,
            libVlc,
            plugins,
            KMEDIAVLC_RUNTIME_ID,
            VlcRuntimeCapabilities(
                4,
                2,
                KMEDIAVLC_LIBVLC_VERSION,
                KMEDIAVLC_LIBVLC_REVISION,
                setOf(VlcFrameDeliveryMode.GPU_PUSH, VlcFrameDeliveryMode.CPU_PULL),
                setOf(layout.renderEngine),
                layout.hdr10Metadata,
            ),
        ),
    )
}

private fun currentKMediaVlcRuntimeLayout(): KMediaVlcRuntimeLayout {
    val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    return when {
        osName.contains("windows") ->
            KMediaVlcRuntimeLayout(
                bridgePath = "bin/kmediavlc_bridge.dll",
                libVlcPath = "bin/libvlc.dll",
                renderEngine = VlcRenderEngine.D3D11,
                hdr10Metadata = true,
            )
        osName.contains("mac") || osName.contains("darwin") ->
            KMediaVlcRuntimeLayout(
                bridgePath = "bin/libkmediavlc_bridge.dylib",
                libVlcPath = "bin/libvlc.12.dylib",
                renderEngine = VlcRenderEngine.OPENGL,
                hdr10Metadata = true,
            )
        osName.contains("linux") ->
            KMediaVlcRuntimeLayout(
                bridgePath = "bin/libkmediavlc_bridge.so",
                libVlcPath = "bin/libvlc.so.12",
                renderEngine = VlcRenderEngine.GLES2,
                hdr10Metadata = false,
            )
        else -> error("KMediaVlc does not provide a desktop runtime for this operating system.")
    }
}

private fun requiredRuntimeFile(
    root: Path,
    relativePath: String,
): Path {
    val file = root.resolve(relativePath).normalize()
    require(file.startsWith(root) && Files.isRegularFile(file) && !Files.isSymbolicLink(file)) {
        "The configured KMediaVlc runtime is missing the plain file $relativePath."
    }
    return file
}

private data class KMediaVlcRuntimeLayout(
    val bridgePath: String,
    val libVlcPath: String,
    val renderEngine: VlcRenderEngine,
    val hdr10Metadata: Boolean,
)

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
