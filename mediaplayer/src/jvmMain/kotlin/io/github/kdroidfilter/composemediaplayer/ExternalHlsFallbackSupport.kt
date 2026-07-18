package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.URI
import kotlin.math.roundToLong

internal enum class ExternalHlsFallbackBackend(
    val displayName: String,
) {
    KMEDIA_BRIDGE("configured media bridge"),
    VLC("VLC"),
}

internal data class StartedExternalHlsFallback(
    val backend: ExternalHlsFallbackBackend,
    val fallback: Closeable,
    val source: HlsFallbackSource,
)

internal object ExternalHlsFallbackSupport {
    suspend fun needsContainerFallback(
        uri: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): Boolean = JvmExternalFallbackContainerSupport.needsContainerFallback(uri, requestHeaders)

    fun isDisabled(): Boolean {
        val configured =
            System.getProperty("composemediaplayer.hlsFallback")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK")
                ?: System.getProperty("composemediaplayer.macos.ffmpegFallback")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_FFMPEG_FALLBACK")
                ?: "true"
        return configured.equals("false", ignoreCase = true) ||
            configured == "0" ||
            configured.equals("off", ignoreCase = true)
    }

    fun selectBackend(
        requiresSubtitleRendering: Boolean,
        extensions: List<VideoPipelineExtension> = emptyList(),
    ): ExternalHlsFallbackBackend {
        val configured = configuredHlsFallbackBackend()

        return when (configured) {
            "vlc" -> ExternalHlsFallbackBackend.VLC
            "ffmpeg", "kmediabridge", "bridge" -> ExternalHlsFallbackBackend.KMEDIA_BRIDGE
            else ->
                selectAutomaticHlsFallbackBackend(
                    requiresSubtitleRendering = requiresSubtitleRendering,
                    canKMediaBridgeBurnSubtitles = canKMediaBridgeBurnSubtitles(extensions),
                    isVlcAvailable = ExternalVlcLocator.findVlc() != null,
                )
        }
    }

    internal fun selectBackendForColor(
        inputColorInfo: VideoColorInfo,
        requiresSubtitleRendering: Boolean,
        extensions: List<VideoPipelineExtension> = emptyList(),
    ): ExternalHlsFallbackBackend {
        if (inputColorInfo.isHdr) {
            // VLC's generic transcode path does not expose a verifiable HDR color pipeline.
            return ExternalHlsFallbackBackend.KMEDIA_BRIDGE
        }
        if (requiresSubtitleRendering && canKMediaBridgeBurnSubtitles(extensions)) {
            return ExternalHlsFallbackBackend.KMEDIA_BRIDGE
        }
        if (requiresSubtitleRendering) {
            // This selects VLC only before any KMediaBridge dylib/DLL is loaded. It is the explicit
            // BEST_EFFORT fallback on platforms whose reviewed runtime is still remux-only.
            return selectBackend(requiresSubtitleRendering = true, extensions = extensions)
        }
        if (!inputColorInfo.isSafeForUnmanagedSdrFallback()) {
            // KMediaBridge will perform its typed probe and either preserve a verified compressed
            // signal or reject it. Merely recognizing HDR here is not treated as output proof.
            return ExternalHlsFallbackBackend.KMEDIA_BRIDGE
        }
        return selectBackend(requiresSubtitleRendering = false, extensions = extensions)
    }

    internal fun selectBackendForInput(
        uri: String,
        inputColorInfo: VideoColorInfo,
        requiresSubtitleRendering: Boolean,
        extensions: List<VideoPipelineExtension> = emptyList(),
    ): ExternalHlsFallbackBackend =
        if (uri.isRemoteMediaUri() &&
            inputColorInfo.isSafeForUnmanagedSdrFallback() &&
            configuredHlsFallbackBackend() == "auto" &&
            ExternalVlcLocator.findVlc() != null
        ) {
            ExternalHlsFallbackBackend.VLC
        } else {
            selectBackendForColor(inputColorInfo, requiresSubtitleRendering, extensions)
        }

    private fun configuredHlsFallbackBackend(): String =
        (
            when (CurrentPlatform.os) {
                CurrentPlatform.OS.MAC -> System.getProperty("composemediaplayer.macos.hlsFallbackBackend")
                CurrentPlatform.OS.WINDOWS -> System.getProperty("composemediaplayer.windows.hlsFallbackBackend")
                CurrentPlatform.OS.LINUX -> System.getProperty("composemediaplayer.linux.hlsFallbackBackend")
            }
                ?: System.getProperty("composemediaplayer.hlsFallbackBackend")
                ?: when (CurrentPlatform.os) {
                    CurrentPlatform.OS.MAC -> System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_HLS_FALLBACK_BACKEND")
                    CurrentPlatform.OS.WINDOWS -> System.getenv("COMPOSE_MEDIA_PLAYER_WINDOWS_HLS_FALLBACK_BACKEND")
                    CurrentPlatform.OS.LINUX -> System.getenv("COMPOSE_MEDIA_PLAYER_LINUX_HLS_FALLBACK_BACKEND")
                }
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_HLS_FALLBACK_BACKEND")
                ?: System.getProperty("composemediaplayer.macos.hlsFallbackBackend")
                ?: System.getenv("COMPOSE_MEDIA_PLAYER_MACOS_HLS_FALLBACK_BACKEND")
                ?: "auto"
        ).lowercase()

    suspend fun start(
        uri: String,
        requestHeaders: Map<String, String>,
        selectedAudioStreamIndex: Int?,
        selectedSubtitleStreamIndex: Int?,
        startTimeSeconds: Double,
        allowHdrCmafPassthrough: Boolean = false,
        requireHdrCmafPassthrough: Boolean = false,
        forceSdrOutput: Boolean = false,
        extensions: List<VideoPipelineExtension> = emptyList(),
    ): StartedExternalHlsFallback {
        require(startTimeSeconds.isFinite() && startTimeSeconds >= 0.0) {
            "The desktop bridge start time must be finite and non-negative."
        }
        if (isDisabled()) {
            externalHlsFallbackDisabled()
        }

        val inputColorInfo =
            withContext(Dispatchers.IO) {
                JvmLibVlcMediaProbe.probe(uri, requestHeaders).videoColorInfo
            }
        val backend =
            selectBackendForInput(
                uri = uri,
                inputColorInfo = inputColorInfo,
                requiresSubtitleRendering = selectedSubtitleStreamIndex != null,
                extensions = extensions,
            )
        val fallback =
            when (backend) {
                ExternalHlsFallbackBackend.VLC -> {
                    val vlcPath =
                        ExternalVlcLocator.findVlc()
                            ?: missingVlcFallback()
                    ExternalVlcHlsFallback(vlcPath)
                }
                ExternalHlsFallbackBackend.KMEDIA_BRIDGE -> {
                    val extension =
                        desktopPlaybackBridge(extensions)
                            ?: missingMediaBridgeFallback()
                    extension.open(
                        DesktopPlaybackBridgeRequest(
                            uri = uri,
                            requestHeaders = requestHeaders,
                            selectedAudioStreamIndex = selectedAudioStreamIndex,
                            selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
                            startPositionMs = (startTimeSeconds * MILLISECONDS_PER_SECOND).roundToLong(),
                            allowHdrCmafPassthrough = allowHdrCmafPassthrough,
                            requireHdrCmafPassthrough = requireHdrCmafPassthrough,
                            forceSdrOutput = forceSdrOutput,
                        ),
                    )
                }
            }

        return runCatching {
            val source =
                when (fallback) {
                    is ExternalVlcHlsFallback ->
                        fallback.start(
                            uri = uri,
                            requestHeaders = requestHeaders,
                            selectedAudioStreamIndex = selectedAudioStreamIndex,
                            selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
                            startTimeSeconds = startTimeSeconds,
                        )
                    is DesktopPlaybackBridgeSession -> fallback.source.toHlsFallbackSource()
                    else -> error("Unsupported external HLS fallback backend")
                }
            StartedExternalHlsFallback(backend = backend, fallback = fallback, source = source)
        }.getOrElse { error ->
            fallback.close()
            externalHlsFallbackFailed(backend, error)
        }
    }

    private fun externalHlsFallbackDisabled(): Nothing =
        throw UnsupportedOperationException(
            "The external HLS fallback is disabled. Enable it with " +
                "composemediaplayer.hlsFallback=true or COMPOSE_MEDIA_PLAYER_HLS_FALLBACK=true.",
        )

    private fun missingVlcFallback(): Nothing =
        throw UnsupportedOperationException(
            "The external HLS fallback backend is set to VLC, but VLC was not found. " +
                "Install VLC or set composemediaplayer.vlc=/path/to/vlc " +
                "or COMPOSE_MEDIA_PLAYER_VLC. ComposeMediaPlayer does not bundle VLC.",
        )

    private fun missingMediaBridgeFallback(): Nothing =
        throw UnsupportedOperationException(
            "This source requires a desktop media bridge, but no available " +
                "DesktopPlaybackBridgeExtension was configured. Add composemediaplayer-kmediabridge " +
                "and pass KMediaBridgeDesktopExtension() in VideoPlaybackOptions.extensions.",
        )

    private fun externalHlsFallbackFailed(
        backend: ExternalHlsFallbackBackend,
        cause: Throwable,
    ): Nothing =
        throw UnsupportedOperationException(
            "Failed to prepare media through external ${backend.displayName} HLS fallback: ${cause.message}",
            cause,
        )

    fun hasSubtitleRenderer(extensions: List<VideoPipelineExtension> = emptyList()): Boolean =
        when (selectBackend(requiresSubtitleRendering = true, extensions = extensions)) {
            ExternalHlsFallbackBackend.VLC -> ExternalVlcLocator.findVlc() != null
            ExternalHlsFallbackBackend.KMEDIA_BRIDGE -> canKMediaBridgeBurnSubtitles(extensions)
        }

    private fun canKMediaBridgeBurnSubtitles(extensions: List<VideoPipelineExtension>): Boolean =
        desktopPlaybackBridge(extensions)?.desktopCapabilities?.canBurnSubtitles == true

    internal fun canKMediaBridgeToneMapToSdr(extensions: List<VideoPipelineExtension>): Boolean =
        !isDisabled() &&
            desktopPlaybackBridge(extensions)?.desktopCapabilities?.canToneMapToSdr == true

    internal fun canKMediaBridgeCopyVideo(extensions: List<VideoPipelineExtension>): Boolean =
        !isDisabled() &&
            desktopPlaybackBridge(extensions)?.desktopCapabilities?.canCopyVideo == true
}

private fun desktopPlaybackBridge(extensions: List<VideoPipelineExtension>): DesktopPlaybackBridgeExtension? =
    extensions
        .filterIsInstance<DesktopPlaybackBridgeExtension>()
        .firstOrNull { extension -> extension.availability.canContribute }

private fun DesktopPlaybackBridgeSource.toHlsFallbackSource(): HlsFallbackSource =
    HlsFallbackSource(
        playlistUrl = playlistUrl,
        durationSeconds = durationMs?.toDouble()?.div(MILLISECONDS_PER_SECOND),
        playbackOffsetSeconds = playbackOffsetMs.toDouble() / MILLISECONDS_PER_SECOND,
        audioTracks = audioTracks,
        selectedAudioStreamIndex = selectedAudioStreamIndex,
        subtitleTracks = subtitleTracks,
        selectedSubtitleStreamIndex = selectedSubtitleStreamIndex,
        inputColorInfo = inputColorInfo,
        outputColorInfo = outputColorInfo,
        toneMappedHdrToSdr = toneMappedHdrToSdr,
        hdrCmafPassthrough = hdrCmafPassthrough,
        videoCopiedWithoutReencoding = videoCopiedWithoutReencoding,
        usesMediaBridge = true,
    )

internal fun isExternalHlsAudioTrackId(id: String): Boolean =
    id.startsWith(EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX) || id.startsWith(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX)

internal fun isExternalHlsSubtitleTrackId(id: String): Boolean =
    id.startsWith(EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX) || id.startsWith(EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX)

internal fun externalHlsTrackStreamIndex(id: String): Int? =
    when {
        id.startsWith(
            EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX,
        ) -> id.removePrefix(EXTERNAL_FFMPEG_AUDIO_TRACK_ID_PREFIX)
        id.startsWith(
            EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX,
        ) -> id.removePrefix(EXTERNAL_FFMPEG_SUBTITLE_TRACK_ID_PREFIX)
        id.startsWith(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX) -> id.removePrefix(EXTERNAL_VLC_AUDIO_TRACK_ID_PREFIX)
        id.startsWith(
            EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX,
        ) -> id.removePrefix(EXTERNAL_VLC_SUBTITLE_TRACK_ID_PREFIX)
        else -> null
    }?.toIntOrNull()

/** Pure policy seam: tests do not depend on which native payload happens to be on their classpath. */
internal fun selectAutomaticHlsFallbackBackend(
    requiresSubtitleRendering: Boolean,
    canKMediaBridgeBurnSubtitles: Boolean,
    isVlcAvailable: Boolean,
): ExternalHlsFallbackBackend =
    when {
        requiresSubtitleRendering && canKMediaBridgeBurnSubtitles ->
            ExternalHlsFallbackBackend.KMEDIA_BRIDGE
        requiresSubtitleRendering && isVlcAvailable -> ExternalHlsFallbackBackend.VLC
        else -> ExternalHlsFallbackBackend.KMEDIA_BRIDGE
    }

private fun String.isRemoteMediaUri(): Boolean =
    runCatching { URI(this).scheme?.lowercase() }
        .getOrNull() in setOf("http", "https")

private const val MILLISECONDS_PER_SECOND = 1_000.0
