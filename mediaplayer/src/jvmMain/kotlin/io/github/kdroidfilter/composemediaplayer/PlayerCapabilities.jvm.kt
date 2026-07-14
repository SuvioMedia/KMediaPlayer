package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.mac.MacNativeBridge
import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform

internal actual fun platformPlayerCapabilities(): PlayerCapabilities = jvmPlayerCapabilities(VideoPlaybackOptions())

internal fun jvmPlayerCapabilities(playbackOptions: VideoPlaybackOptions): PlayerCapabilities =
    when (CurrentPlatform.os) {
        CurrentPlatform.OS.WINDOWS ->
            PlayerCapabilities(
                supportedUriSchemes = JVM_SUPPORTED_URI_SCHEMES,
                supportsMkv =
                    supportsDesktopMkvPlayback(
                        playbackOptions = playbackOptions,
                        nativePlatformSupportsMkv = false,
                        supportsLibVlcNativeBackend = true,
                    ),
            )
        CurrentPlatform.OS.MAC ->
            PlayerCapabilities(
                supportedUriSchemes = JVM_SUPPORTED_URI_SCHEMES,
                supportsMkv =
                    supportsDesktopMkvPlayback(
                        playbackOptions = playbackOptions,
                        nativePlatformSupportsMkv = false,
                        supportsLibVlcNativeBackend = true,
                    ),
                hdr = queryMacHdrCapabilities(),
            )
        CurrentPlatform.OS.LINUX ->
            PlayerCapabilities(
                supportedUriSchemes = JVM_SUPPORTED_URI_SCHEMES,
                supportsMkv =
                    supportsDesktopMkvPlayback(
                        playbackOptions = playbackOptions,
                        nativePlatformSupportsMkv = true,
                        supportsLibVlcNativeBackend = true,
                    ),
            )
    }

internal actual fun platformSupportsHls(): Boolean = true

private val JVM_SUPPORTED_URI_SCHEMES = setOf("file", "http", "https")

private fun supportsDesktopMkvPlayback(
    playbackOptions: VideoPlaybackOptions,
    nativePlatformSupportsMkv: Boolean,
    supportsLibVlcNativeBackend: Boolean,
): Boolean =
    when (playbackOptions.desktopVideoBackend) {
        DesktopVideoBackend.PLATFORM -> nativePlatformSupportsMkv
        DesktopVideoBackend.LIBVLC -> hasLibVlcBackend()
        DesktopVideoBackend.LIBVLC_NATIVE -> supportsLibVlcNativeBackend && hasLibVlcBackend()
        DesktopVideoBackend.AUTO ->
            nativePlatformSupportsMkv ||
                hasLibVlcBackend() ||
                hasExternalHlsContainerFallback()
    }

private val detectedLibVlcBackend: Boolean by lazy {
    runCatching { ExternalVlcLocator.findLibVlc() != null }.getOrDefault(false)
}

private val detectedExternalHlsContainerFallback: Boolean by lazy {
    if (ExternalHlsFallbackSupport.isDisabled()) {
        false
    } else {
        runCatching {
            ExternalVlcLocator.findVlc() != null || ExternalFfmpegLocator.findFfmpeg() != null
        }.getOrDefault(false)
    }
}

private fun hasLibVlcBackend(): Boolean = detectedLibVlcBackend

private fun hasExternalHlsContainerFallback(): Boolean = detectedExternalHlsContainerFallback

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    platformPlayerCapabilities().canPlaySource(source)

private fun queryMacHdrCapabilities(): HdrCapabilities =
    runCatching {
        MacNativeBridge.nGetHdrCapabilities()?.toHdrCapabilities()
    }.getOrNull() ?: HdrCapabilities(
        hdr = HdrSupport.UNKNOWN,
        hdr10 = HdrSupport.UNKNOWN,
        hlg = HdrSupport.UNKNOWN,
        dolbyVision = HdrSupport.UNKNOWN,
    )

private fun String.toHdrCapabilities(): HdrCapabilities {
    val values =
        split(';')
            .mapNotNull { entry ->
                val key = entry.substringBefore('=', missingDelimiterValue = "").trim()
                val value = entry.substringAfter('=', missingDelimiterValue = "").trim()
                if (key.isEmpty()) null else key to value
            }.toMap()
    return HdrCapabilities(
        hdr = values["hdr"].toHdrSupport(),
        hdr10 = values["hdr10"].toHdrSupport(),
        hlg = values["hlg"].toHdrSupport(),
        dolbyVision = values["dolbyVision"].toHdrSupport(),
        supportsNativeHdrPlayback = values["native"] == "1",
        supportsToneMappingToSdr = values["toneMap"] == "1",
        maxExtendedDynamicRange = values["maxEdr"]?.toFloatOrNull() ?: 1f,
    )
}

private fun String?.toHdrSupport(): HdrSupport =
    when (this) {
        "SUPPORTED" -> HdrSupport.SUPPORTED
        "UNSUPPORTED" -> HdrSupport.UNSUPPORTED
        else -> HdrSupport.UNKNOWN
    }
