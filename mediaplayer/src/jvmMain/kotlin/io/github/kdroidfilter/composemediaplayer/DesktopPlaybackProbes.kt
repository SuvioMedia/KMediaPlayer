package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendProbeResult
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendRoutingTier
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackRequest
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform

internal fun platformDirectDesktopProbe(
    request: DesktopPlaybackRequest,
    playbackOptions: VideoPlaybackOptions,
): DesktopBackendProbeResult {
    if (CurrentPlatform.os == CurrentPlatform.OS.MAC && request.isLegacyContainer()) {
        return DesktopBackendProbeResult.Unsupported(
            "AVFoundation direct playback does not claim AVI/WMV/ASF; use MPV, libVLC, or KMediaBridge transcode.",
        )
    }
    val directOptions =
        playbackOptions.copy(
            desktopVideoBackend = DesktopVideoBackend.PLATFORM,
            desktopMediaSourcePolicy = DesktopMediaSourcePolicy.DIRECT,
        )
    return if (jvmPlayerCapabilities(directOptions).canPlaySource(request.source)) {
        DesktopBackendProbeResult.Supported(DesktopBackendRoutingTier.PLATFORM_DIRECT)
    } else {
        DesktopBackendProbeResult.Unsupported(
            "The native platform framework does not advertise support for this source.",
        )
    }
}

internal fun fallbackContainerProbe(
    request: DesktopPlaybackRequest,
    allowLegacy: Boolean,
): DesktopBackendProbeResult =
    if (request.isRemuxContainer() || (allowLegacy && request.isLegacyContainer())) {
        DesktopBackendProbeResult.Supported(DesktopBackendRoutingTier.KMEDIA_BRIDGE_REMUX)
    } else {
        DesktopBackendProbeResult.Unsupported("This adapter is limited to Matroska/WebM and legacy AVI/ASF inputs.")
    }

internal fun legacyContainerProbe(request: DesktopPlaybackRequest): DesktopBackendProbeResult =
    if (request.isLegacyContainer()) {
        DesktopBackendProbeResult.Supported(DesktopBackendRoutingTier.KMEDIA_BRIDGE_TRANSCODE)
    } else {
        DesktopBackendProbeResult.Unsupported("The legacy transcode stage accepts AVI/WMV/ASF inputs only.")
    }

internal fun defaultDesktopProbe(
    request: DesktopPlaybackRequest,
    playbackOptions: VideoPlaybackOptions,
): DesktopBackendProbeResult {
    val capabilities = jvmPlayerCapabilities(playbackOptions)
    return if (capabilities.canPlaySource(request.source)) {
        DesktopBackendProbeResult.Supported(DesktopBackendRoutingTier.PLATFORM_DIRECT)
    } else {
        DesktopBackendProbeResult.Unsupported("The platform route and configured bridges do not support this source.")
    }
}

private fun DesktopPlaybackRequest.isRemuxContainer(): Boolean =
    source.normalizedExtension() in REMUX_EXTENSIONS ||
        source.normalizedMimeType() in REMUX_MIME_TYPES

internal fun DesktopPlaybackRequest.isLegacyContainer(): Boolean =
    source.normalizedExtension() in LEGACY_EXTENSIONS ||
        source.normalizedMimeType() in LEGACY_MIME_TYPES

private fun MediaSourceSpec.normalizedExtension(): String =
    uri
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()

private fun MediaSourceSpec.normalizedMimeType(): String? = mimeType?.substringBefore(';')?.trim()?.lowercase()

private val REMUX_EXTENSIONS: Set<String> = setOf("mkv", "mk3d", "mka", "mks", "webm")
private val LEGACY_EXTENSIONS: Set<String> = setOf("avi", "wmv", "asf")
private val REMUX_MIME_TYPES: Set<String> =
    setOf("video/x-matroska", "video/matroska", "audio/x-matroska", "video/webm", "audio/webm")
private val LEGACY_MIME_TYPES: Set<String> =
    setOf("video/x-msvideo", "video/x-ms-wmv", "video/x-ms-asf", "application/vnd.ms-asf")
