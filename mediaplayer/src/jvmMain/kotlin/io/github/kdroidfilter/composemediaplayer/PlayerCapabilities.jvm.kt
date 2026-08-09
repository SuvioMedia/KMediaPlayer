package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.util.CurrentPlatform

internal actual fun platformPlayerCapabilities(playbackOptions: VideoPlaybackOptions): PlayerCapabilities =
    jvmPlayerCapabilities(playbackOptions)

internal fun jvmPlayerCapabilities(playbackOptions: VideoPlaybackOptions): PlayerCapabilities =
    when (CurrentPlatform.os) {
        CurrentPlatform.OS.WINDOWS ->
            PlayerCapabilities(
                supportsHls = true,
                supportsExternalAudioTracks = true,
                supportedUriSchemes = JVM_SUPPORTED_URI_SCHEMES,
                supportsMkv =
                    supportsDesktopMkvPlayback(
                        playbackOptions = playbackOptions,
                        nativePlatformSupportsMkv = false,
                        supportsLibVlcNativeBackend = true,
                    ),
            )
        CurrentPlatform.OS.MAC ->
            if (CurrentPlatform.isSupportedMacOsArchitecture) {
                PlayerCapabilities(
                    supportsHls = true,
                    supportsExternalAudioTracks = true,
                    supportedUriSchemes = JVM_SUPPORTED_URI_SCHEMES,
                    supportsMkv =
                        supportsDesktopMkvPlayback(
                            playbackOptions = playbackOptions,
                            nativePlatformSupportsMkv = false,
                            supportsLibVlcNativeBackend = true,
                        ),
                    // A desktop player has no active display until its native layer is attached.
                    // MacVideoPlayerState replaces this with capabilities of that layer's NSScreen.
                    displayColorCapabilities = DisplayColorCapabilities(),
                    rendererColorCapabilities = MAC_RENDERER_COLOR_CAPABILITIES,
                )
            } else {
                PlayerCapabilities()
            }
        CurrentPlatform.OS.LINUX ->
            PlayerCapabilities(
                supportsHls = true,
                supportsExternalAudioTracks = true,
                supportedUriSchemes = JVM_SUPPORTED_URI_SCHEMES,
                supportsMkv =
                    supportsDesktopMkvPlayback(
                        playbackOptions = playbackOptions,
                        nativePlatformSupportsMkv = true,
                        supportsLibVlcNativeBackend = true,
                    ),
            )
    }.withPipelineExtensions(playbackOptions)

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
                hasExternalHlsContainerFallback(playbackOptions)
    }

private val detectedLibVlcBackend: Boolean by lazy {
    runCatching { ExternalVlcLocator.findLibVlc() != null }.getOrDefault(false)
}

internal fun hasLibVlcBackend(): Boolean = detectedLibVlcBackend

private fun hasExternalHlsContainerFallback(playbackOptions: VideoPlaybackOptions): Boolean =
    !ExternalHlsFallbackSupport.isDisabled() &&
        runCatching {
            ExternalVlcLocator.findVlc() != null ||
                ExternalHlsFallbackSupport.canKMediaBridgeCopyVideo(playbackOptions.extensions)
        }.getOrDefault(false)

internal actual fun platformQueryCanPlaySource(source: MediaSourceSpec): Boolean =
    platformPlayerCapabilities().canPlaySource(source)

private val MAC_RENDERER_COLOR_CAPABILITIES =
    RendererColorCapabilities(
        nativeSurfaceDynamicRanges =
            setOf(
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HLG,
                VideoDynamicRange.DOLBY_VISION,
            ),
        controlledHdrDynamicRanges =
            setOf(
                VideoDynamicRange.HDR10,
                VideoDynamicRange.HDR10_PLUS,
                VideoDynamicRange.HLG,
            ),
        supportsToneMappingToSdr = true,
        supportsNativeToneMappingToSdr = true,
        supportsHdrProjection = true,
        supportsHdr10PlusApplication = true,
        supportsDolbyVisionMetadata = true,
        supportsDolbyVisionToneMappingToSdr = true,
    )
