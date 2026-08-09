package io.github.kdroidfilter.composemediaplayer

import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendAvailability
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopBackendRoutingTier
import io.github.kdroidfilter.composemediaplayer.desktop.DesktopPlaybackBackend
import io.github.kdroidfilter.composemediaplayer.desktop.asDesktopPlaybackBackend

/** Platform-first route, including configured KMediaBridge remux/transcode extensions. */
fun automaticDesktopPlaybackBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): DesktopPlaybackBackend =
    defaultVideoPlayerBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions =
            playbackOptions.copy(
                desktopVideoBackend = DesktopVideoBackend.AUTO,
                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
                desktopMediaSourcePolicy = DesktopMediaSourcePolicy.AUTO,
            ),
    ).asDesktopPlaybackBackend(
        routingTier = DesktopBackendRoutingTier.PLATFORM_DIRECT,
        id = "auto",
        displayName = "Auto (platform and configured bridges)",
        sourceProbe = { request -> defaultDesktopProbe(request, playbackOptions) },
    )

/** Native platform framework only: AVFoundation, Media Foundation or the Linux platform path. */
fun platformDesktopPlaybackBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): DesktopPlaybackBackend =
    defaultVideoPlayerBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions =
            playbackOptions.copy(
                desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
                desktopMediaSourcePolicy = DesktopMediaSourcePolicy.DIRECT,
            ),
    ).asDesktopPlaybackBackend(
        routingTier = DesktopBackendRoutingTier.PLATFORM_DIRECT,
        id = "platform",
        displayName = "Native platform framework",
        sourceProbe = { request -> platformDirectDesktopProbe(request, playbackOptions) },
    )

/** Compatibility entry for the removed desktop libVLC child-view backend. */
fun libVlcDesktopPlaybackBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): DesktopPlaybackBackend =
    defaultVideoPlayerBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions =
            playbackOptions.copy(
                desktopVideoBackend = DesktopVideoBackend.LIBVLC_NATIVE,
                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
                desktopMediaSourcePolicy = DesktopMediaSourcePolicy.DIRECT,
            ),
    ).asDesktopPlaybackBackend(
        routingTier = DesktopBackendRoutingTier.LIBVLC_NATIVE,
        id = "libvlc",
        displayName = "libVLC native view",
        availabilityProbe = {
            DesktopBackendAvailability.Unavailable(
                reason = "Desktop libVLC native child views are no longer supported.",
                guidance = "Select Auto, Platform or MPV for GPU TextureView presentation.",
            )
        },
    )

/** Platform renderer with automatic source adaptation, excluded from the default renderer route. */
fun adaptedPlatformDesktopPlaybackBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): DesktopPlaybackBackend =
    defaultVideoPlayerBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions =
            playbackOptions.copy(
                desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
                desktopMediaSourcePolicy = DesktopMediaSourcePolicy.AUTO,
            ),
    ).asDesktopPlaybackBackend(
        routingTier = DesktopBackendRoutingTier.KMEDIA_BRIDGE_REMUX,
        id = "platform-adapted",
        displayName = "Native platform framework with source adapter",
        automaticSelection = false,
    )

/** KMediaBridge bounded remux into the platform-native renderer. */
fun kMediaBridgeRemuxDesktopPlaybackBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): DesktopPlaybackBackend =
    kMediaBridgeDesktopPlaybackBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions = playbackOptions,
        routingTier = DesktopBackendRoutingTier.KMEDIA_BRIDGE_REMUX,
        id = "kmediabridge-remux",
        displayName = "KMediaBridge remux to native platform",
        automaticSelection = true,
        requiresTranscode = false,
    )

/** KMediaBridge legacy decode/transcode into the platform-native renderer. */
fun kMediaBridgeTranscodeDesktopPlaybackBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): DesktopPlaybackBackend =
    kMediaBridgeDesktopPlaybackBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions = playbackOptions,
        routingTier = DesktopBackendRoutingTier.KMEDIA_BRIDGE_TRANSCODE,
        id = "kmediabridge-transcode",
        displayName = "KMediaBridge legacy transcode to native platform",
        automaticSelection = true,
        requiresTranscode = true,
    )

/** Explicit KMediaBridge adapter for UI selections; automatic routing uses the split stages above. */
fun kMediaBridgeDesktopPlaybackBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): DesktopPlaybackBackend =
    kMediaBridgeDesktopPlaybackBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions = playbackOptions,
        routingTier = DesktopBackendRoutingTier.KMEDIA_BRIDGE_REMUX,
        id = "kmediabridge",
        displayName = "KMediaBridge to native platform",
        automaticSelection = false,
        requiresTranscode = null,
    )

/** Explicit VLC-to-HLS adapter rendered by the platform backend. */
fun vlcHlsDesktopPlaybackBackend(
    audioMode: AudioMode = AudioMode(),
    cacheConfig: CacheConfig = CacheConfig(),
    playbackOptions: VideoPlaybackOptions = VideoPlaybackOptions(),
): DesktopPlaybackBackend =
    defaultVideoPlayerBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions =
            playbackOptions.copy(
                desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
                desktopMediaSourcePolicy = DesktopMediaSourcePolicy.VLC_HLS,
            ),
    ).asDesktopPlaybackBackend(
        routingTier = DesktopBackendRoutingTier.KMEDIA_BRIDGE_REMUX,
        id = "vlc-hls",
        displayName = "VLC HLS adapter to native platform",
        automaticSelection = false,
        availabilityProbe = {
            if (ExternalVlcLocator.findVlc() != null) {
                DesktopBackendAvailability.Available("VLC executable adapter")
            } else {
                DesktopBackendAvailability.Unavailable(
                    reason = "VLC was not found.",
                    guidance = "Install VLC or select another source adapter.",
                )
            }
        },
        sourceProbe = { request -> fallbackContainerProbe(request, allowLegacy = true) },
    )

private fun kMediaBridgeDesktopPlaybackBackend(
    audioMode: AudioMode,
    cacheConfig: CacheConfig,
    playbackOptions: VideoPlaybackOptions,
    routingTier: DesktopBackendRoutingTier,
    id: String,
    displayName: String,
    automaticSelection: Boolean,
    requiresTranscode: Boolean?,
): DesktopPlaybackBackend =
    defaultVideoPlayerBackend(
        audioMode = audioMode,
        cacheConfig = cacheConfig,
        playbackOptions =
            playbackOptions.copy(
                desktopVideoBackend = DesktopVideoBackend.PLATFORM,
                desktopVideoSurfaceMode = DesktopVideoSurfaceMode.PREFER_COLOR_MANAGED_TEXTURE,
                desktopMediaSourcePolicy = DesktopMediaSourcePolicy.KMEDIA_BRIDGE,
            ),
    ).asDesktopPlaybackBackend(
        routingTier = routingTier,
        id = id,
        displayName = displayName,
        automaticSelection = automaticSelection,
        availabilityProbe = { kMediaBridgeAvailability(playbackOptions, requiresTranscode) },
        sourceProbe = { request ->
            when (requiresTranscode) {
                true -> legacyContainerProbe(request)
                false -> fallbackContainerProbe(request, allowLegacy = false)
                null ->
                    if (request.isLegacyContainer()) {
                        legacyContainerProbe(request)
                    } else {
                        fallbackContainerProbe(request, allowLegacy = false)
                    }
            }
        },
    )

private fun kMediaBridgeAvailability(
    playbackOptions: VideoPlaybackOptions,
    requiresTranscode: Boolean?,
): DesktopBackendAvailability {
    val bridge =
        playbackOptions.extensions
            .filterIsInstance<DesktopPlaybackBridgeExtension>()
            .firstOrNull { it.availability.canContribute }
            ?: return DesktopBackendAvailability.Unavailable(
                reason = "No available desktop KMediaBridge extension is configured.",
                guidance = "Add composemediaplayer-kmediabridge and its verified desktop runtime.",
            )
    val capabilities = bridge.desktopCapabilities
    val supported =
        when (requiresTranscode) {
            true -> capabilities.canTranscodeVideo && capabilities.canTranscodeAudio
            false -> capabilities.canCopyVideo
            null -> capabilities.canCopyVideo || (capabilities.canTranscodeVideo && capabilities.canTranscodeAudio)
        }
    return if (supported) {
        DesktopBackendAvailability.Available(bridge.availability.detail)
    } else {
        DesktopBackendAvailability.Unavailable(
            reason =
                if (requiresTranscode == true) {
                    "The selected KMediaBridge runtime cannot transcode legacy video and audio."
                } else {
                    "The selected KMediaBridge runtime cannot remux compressed video."
                },
            guidance = "Use the verified full desktop runtime for this platform.",
        )
    }
}
