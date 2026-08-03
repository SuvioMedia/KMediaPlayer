@file:OptIn(io.github.kdroidfilter.composemediaplayer.ExperimentalComposeMediaPlayerBackendApi::class)
@file:Suppress("MagicNumber")

package io.github.kdroidfilter.composemediaplayer.kmediabridge

import io.github.kdroidfilter.composemediaplayer.ColorConversionCapabilities
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeCapabilities
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeExtension
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeRequest
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeSession
import io.github.kdroidfilter.composemediaplayer.DesktopPlaybackBridgeSource
import io.github.kdroidfilter.composemediaplayer.VideoColorInfo
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability
import io.github.shusek.kmediabridge.VideoHandling
import io.github.shusek.kmediabridge.ffmpeg.BundledFfmpegHlsPlaybackBackend
import io.github.shusek.kmediabridge.ffmpeg.BundledFfmpegHlsPlaybackSession
import io.github.shusek.kmediabridge.ffmpeg.BundledFfmpegNativeDriver
import io.github.shusek.kmediabridge.ffmpeg.DesktopFfmpegRuntimeInspector
import io.github.shusek.kmediabridge.ffmpeg.FfmpegCmafHdrSampleCopy
import io.github.shusek.kmediabridge.ffmpeg.FfmpegHlsPlaybackRequest
import io.github.shusek.kmediabridge.ffmpeg.FfmpegHlsVideoOutputPolicy
import io.github.shusek.kmediabridge.ffmpeg.FfmpegRuntimePolicy
import io.github.shusek.kmediabridge.ffmpeg.FfmpegRuntimeSelection
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import io.github.shusek.kmediabridge.AudioTrackInfo as BridgeAudioTrackInfo
import io.github.shusek.kmediabridge.SubtitleTrackInfo as BridgeSubtitleTrackInfo

/** Selects where the optional desktop KMediaBridge runtime may be loaded from. */
public enum class KMediaBridgeDesktopRuntimePolicy {
    BUNDLED_ONLY,
    EXTERNAL_ONLY,
    PREFER_BUNDLED,
    PREFER_EXTERNAL,
}

/**
 * Runtime selection owned by this adapter, so KMediaBridge implementation types do not leak into
 * the application-facing ABI.
 */
public data class KMediaBridgeDesktopRuntimeSelection(
    public val policy: KMediaBridgeDesktopRuntimePolicy = KMediaBridgeDesktopRuntimePolicy.BUNDLED_ONLY,
    public val externalRuntimeDirectory: Path? = null,
) {
    init {
        val requiresExternalDirectory = policy != KMediaBridgeDesktopRuntimePolicy.BUNDLED_ONLY
        require(!requiresExternalDirectory || externalRuntimeDirectory != null) {
            "The selected desktop runtime policy requires an external runtime directory."
        }
        require(policy != KMediaBridgeDesktopRuntimePolicy.BUNDLED_ONLY || externalRuntimeDirectory == null) {
            "BUNDLED_ONLY cannot be combined with an external runtime directory."
        }
        require(externalRuntimeDirectory == null || Files.isDirectory(externalRuntimeDirectory)) {
            "The external desktop KMediaBridge runtime directory does not exist."
        }
    }

    public companion object {
        @JvmStatic
        public fun bundled(): KMediaBridgeDesktopRuntimeSelection = KMediaBridgeDesktopRuntimeSelection()

        @JvmStatic
        public fun fromExternalDirectory(directory: Path): KMediaBridgeDesktopRuntimeSelection =
            KMediaBridgeDesktopRuntimeSelection(
                policy = KMediaBridgeDesktopRuntimePolicy.EXTERNAL_ONLY,
                externalRuntimeDirectory = directory,
            )
    }
}

/**
 * Optional desktop container, remuxing and HDR-to-SDR bridge backed by the audited KMediaBridge runtime.
 *
 * Adding this extension explicitly is the only way the default JVM player can load bundled FFmpeg.
 */
public class KMediaBridgeDesktopExtension(
    public val runtimeSelection: KMediaBridgeDesktopRuntimeSelection =
        KMediaBridgeDesktopRuntimeSelection.bundled(),
) : DesktopPlaybackBridgeExtension {
    private val platformSupported = currentKMediaBridgeDesktopPlatformIsSupported()
    private val runtimeStatus by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DesktopFfmpegRuntimeInspector.inspect(runtimeSelection.toBridgeSelection())
    }

    override val id: String = ID

    override val availability: VideoPipelineExtensionAvailability
        get() =
            if (!platformSupported) {
                VideoPipelineExtensionAvailability.unavailable(
                    "KMediaBridge on macOS requires Apple Silicon (arm64).",
                )
            } else if (runtimeStatus.isDeclaredAvailable) {
                VideoPipelineExtensionAvailability.Available
            } else {
                VideoPipelineExtensionAvailability.unavailable(
                    runtimeStatus.detail.takeIf(String::isNotBlank)
                        ?: "The selected desktop KMediaBridge runtime is unavailable.",
                )
            }

    override val desktopCapabilities: DesktopPlaybackBridgeCapabilities
        get() =
            if (!platformSupported || !runtimeStatus.isDeclaredAvailable) {
                DesktopPlaybackBridgeCapabilities()
            } else {
                runtimeStatus.capabilities?.let { capabilities ->
                    DesktopPlaybackBridgeCapabilities(
                        canProbe = capabilities.canProbe,
                        canCopyVideo = capabilities.canCopyVideo,
                        canToneMapToSdr = capabilities.canToneMapToSdr,
                        canTranscodeVideo = capabilities.canTranscodeVideo,
                        canTranscodeAudio = capabilities.canTranscodeAudio,
                        canBurnSubtitles = capabilities.canBurnSubtitles,
                    )
                } ?: DesktopPlaybackBridgeCapabilities()
            }

    override val colorConversionCapabilities: ColorConversionCapabilities
        get() =
            if (availability.canContribute && desktopCapabilities.canToneMapToSdr) {
                ColorConversionCapabilities(
                    supportsHdrToSdrSourceBridge = true,
                    supportsStreamingVOD = true,
                )
            } else {
                ColorConversionCapabilities()
            }

    override suspend fun open(request: DesktopPlaybackBridgeRequest): DesktopPlaybackBridgeSession {
        check(availability.canContribute) {
            availability.detail ?: "The desktop KMediaBridge runtime is unavailable."
        }
        return KMediaBridgeDesktopSession.open(request, runtimeSelection.toBridgeSelection())
    }

    public companion object {
        public const val ID: String = "io.github.shusek.composemediaplayer.kmediabridge.desktop"
    }
}

internal fun isKMediaBridgeDesktopPlatformSupported(
    osName: String,
    architecture: String,
): Boolean {
    val normalizedOs = osName.lowercase()
    if (!normalizedOs.contains("mac") && !normalizedOs.contains("darwin")) return true
    return architecture.lowercase() in setOf("aarch64", "arm64")
}

private fun currentKMediaBridgeDesktopPlatformIsSupported(): Boolean =
    isKMediaBridgeDesktopPlatformSupported(
        osName = System.getProperty("os.name").orEmpty(),
        architecture = System.getProperty("os.arch").orEmpty(),
    )

private class KMediaBridgeDesktopSession(
    private val playbackSession: BundledFfmpegHlsPlaybackSession,
    private val preparedInput: PreparedBridgeInput,
    override val source: DesktopPlaybackBridgeSource,
) : DesktopPlaybackBridgeSession {
    override fun close() {
        try {
            playbackSession.close()
        } finally {
            preparedInput.close()
        }
    }

    companion object {
        suspend fun open(
            request: DesktopPlaybackBridgeRequest,
            runtimeSelection: FfmpegRuntimeSelection,
        ): KMediaBridgeDesktopSession {
            val startTimeUs = request.startTimeUs()
            val preparedInput = prepareBridgeInput(request.uri, request.requestHeaders)
            var ownershipTransferred = false
            try {
                val started =
                    BundledFfmpegHlsPlaybackBackend.start(
                        request =
                            FfmpegHlsPlaybackRequest(
                                input = preparedInput.input,
                                selectedAudioTrackId = request.selectedAudioStreamIndex,
                                selectedSubtitleTrackId = request.selectedSubtitleStreamIndex,
                                videoOutputPolicy =
                                    when {
                                        request.forceAvFoundationCompatibility ->
                                            FfmpegHlsVideoOutputPolicy.AVFOUNDATION_COMPATIBLE_SDR
                                        request.forceSdrOutput -> FfmpegHlsVideoOutputPolicy.FORCE_SDR
                                        else -> FfmpegHlsVideoOutputPolicy.PRESERVE_SOURCE
                                    },
                                startTimeUs = startTimeUs,
                            ),
                        driver = BundledFfmpegNativeDriver.load(runtimeSelection),
                    )
                return started.closeOnFailure {
                    val bridgeSource = started.source
                    val hdrSampleCopy = bridgeSource.copiedHdrSignal != FfmpegCmafHdrSampleCopy.NONE
                    requireHdrCmafPassthrough(request.requireHdrCmafPassthrough, hdrSampleCopy)

                    val inputColor =
                        bridgeSource.outputInfo.inputColorInfo?.toPlayerVideoColorInfo() ?: VideoColorInfo()
                    val outputColor =
                        bridgeSource.outputInfo.outputColorInfo?.toPlayerVideoColorInfo() ?: inputColor
                    val session =
                        KMediaBridgeDesktopSession(
                            playbackSession = started,
                            preparedInput = preparedInput,
                            source =
                                DesktopPlaybackBridgeSource(
                                    playlistUrl = bridgeSource.playlistUrl,
                                    durationMs = bridgeSource.probe.durationUs?.div(MICROSECONDS_PER_MILLISECOND),
                                    playbackOffsetMs = bridgeSource.playbackOffsetUs / MICROSECONDS_PER_MILLISECOND,
                                    audioTracks =
                                        bridgeSource.probe.tracks
                                            .filterIsInstance<BridgeAudioTrackInfo>()
                                            .map(::toAudioTrack),
                                    selectedAudioStreamIndex = bridgeSource.outputInfo.selectedAudioTrackId,
                                    subtitleTracks =
                                        bridgeSource.probe.tracks
                                            .filterIsInstance<BridgeSubtitleTrackInfo>()
                                            .mapNotNull(::toSubtitleTrack),
                                    selectedSubtitleStreamIndex = bridgeSource.outputInfo.selectedSubtitleTrackId,
                                    inputColorInfo = inputColor,
                                    outputColorInfo = outputColor,
                                    toneMappedHdrToSdr =
                                        bridgeSource.outputInfo.videoHandling == VideoHandling.TONE_MAP_TO_SDR,
                                    hdrCmafPassthrough = request.allowHdrCmafPassthrough && hdrSampleCopy,
                                    videoCopiedWithoutReencoding =
                                        bridgeSource.outputInfo.videoHandling == VideoHandling.COPY,
                                    avFoundationCompatibleTranscode = request.forceAvFoundationCompatibility,
                                    detail =
                                        if (request.forceAvFoundationCompatibility) {
                                            "KMediaBridge decoded the source to platform-compatible AVC/AAC CMAF."
                                        } else {
                                            "KMediaBridge provided the desktop decoder-ready stream."
                                        },
                                ),
                        )
                    ownershipTransferred = true
                    session
                }
            } finally {
                if (!ownershipTransferred) preparedInput.close()
            }
        }
    }
}

private fun DesktopPlaybackBridgeRequest.startTimeUs(): Long =
    try {
        Math.multiplyExact(startPositionMs, MICROSECONDS_PER_MILLISECOND)
    } catch (failure: ArithmeticException) {
        throw IllegalArgumentException("The desktop bridge start position is too large.", failure)
    }

private fun requireHdrCmafPassthrough(
    required: Boolean,
    copied: Boolean,
) {
    if (required && !copied) {
        throw UnsupportedOperationException(
            "REQUIRE_HDR rejected this bridge because KMediaBridge could not confirm " +
                "an unchanged HEVC Main 10 HDR signal in CMAF.",
        )
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun <T> BundledFfmpegHlsPlaybackSession.closeOnFailure(block: suspend () -> T): T =
    try {
        block()
    } catch (failure: Throwable) {
        runCatching { closeAsync() }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
    }

private fun KMediaBridgeDesktopRuntimeSelection.toBridgeSelection(): FfmpegRuntimeSelection =
    FfmpegRuntimeSelection(
        policy =
            when (policy) {
                KMediaBridgeDesktopRuntimePolicy.BUNDLED_ONLY -> FfmpegRuntimePolicy.BUNDLED_ONLY
                KMediaBridgeDesktopRuntimePolicy.EXTERNAL_ONLY -> FfmpegRuntimePolicy.EXTERNAL_ONLY
                KMediaBridgeDesktopRuntimePolicy.PREFER_BUNDLED -> FfmpegRuntimePolicy.PREFER_BUNDLED
                KMediaBridgeDesktopRuntimePolicy.PREFER_EXTERNAL -> FfmpegRuntimePolicy.PREFER_EXTERNAL
            },
        externalRuntimeDirectory = externalRuntimeDirectory,
    )

internal fun localPath(uri: String): String? {
    if (WINDOWS_DRIVE_PATH.matches(uri)) return File(uri).absolutePath
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return File(uri).absolutePath
    return when (parsed.scheme?.lowercase()) {
        null, "" -> File(uri).absolutePath
        "file" -> runCatching { File(parsed).absolutePath }.getOrNull()
        else -> null
    }
}

private const val MICROSECONDS_PER_MILLISECOND = 1_000L
internal val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:[\\\\/].*")
