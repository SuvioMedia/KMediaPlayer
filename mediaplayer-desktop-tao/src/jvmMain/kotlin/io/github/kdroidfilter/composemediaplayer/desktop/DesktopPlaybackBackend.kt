package io.github.kdroidfilter.composemediaplayer.desktop

import androidx.compose.runtime.Stable
import io.github.kdroidfilter.composemediaplayer.VideoPlayerBackend
import io.github.kdroidfilter.composemediaplayer.VideoPlayerBackendInfo
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState

/** Ordered stages used by the automatic JVM desktop route. */
public enum class DesktopBackendRoutingTier {
    PLATFORM_DIRECT,
    KMEDIA_BRIDGE_REMUX,
    MPV_NATIVE,
    LIBVLC_TEXTURE,
    LIBVLC_NATIVE,
    KMEDIA_BRIDGE_TRANSCODE,
}

/** Runtime availability of a desktop backend without creating a player state. */
public sealed interface DesktopBackendAvailability {
    public data class Available(
        public val detail: String? = null,
    ) : DesktopBackendAvailability

    public data class Unavailable(
        public val reason: String,
        public val guidance: String? = null,
    ) : DesktopBackendAvailability {
        init {
            require(reason.isNotBlank()) { "An unavailable backend must include a reason." }
        }
    }
}

/** Result of asking a backend whether it can consume one redacted playback request. */
public sealed interface DesktopBackendProbeResult {
    public data class Supported(
        public val routingTier: DesktopBackendRoutingTier,
        public val detail: String? = null,
    ) : DesktopBackendProbeResult

    public data class Unsupported(
        public val reason: String,
    ) : DesktopBackendProbeResult {
        init {
            require(reason.isNotBlank()) { "An unsupported source must include a reason." }
        }
    }
}

/** Backend contract consumed by [DesktopPlaybackSession]. */
@Stable
public interface DesktopPlaybackBackend : VideoPlayerBackend {
    public val routingTier: DesktopBackendRoutingTier

    /** Whether this backend participates in a session's default automatic route. */
    public val automaticSelection: Boolean
        get() = true

    public fun inspectAvailability(): DesktopBackendAvailability

    public fun probe(request: DesktopPlaybackRequest): DesktopBackendProbeResult
}

/**
 * Adapts an existing backend to the explicit desktop-session API.
 *
 * The default probe is intentionally conservative and delegates to the backend's advertised
 * capabilities. Backends with container-specific rules should pass [sourceProbe].
 */
public fun VideoPlayerBackend.asDesktopPlaybackBackend(
    routingTier: DesktopBackendRoutingTier,
    id: String = info.id,
    displayName: String = info.displayName,
    availabilityProbe: () -> DesktopBackendAvailability = {
        DesktopBackendAvailability.Available()
    },
    sourceProbe: ((DesktopPlaybackRequest) -> DesktopBackendProbeResult)? = null,
    automaticSelection: Boolean = true,
): DesktopPlaybackBackend =
    AdaptedDesktopPlaybackBackend(
        delegate = this,
        id = id,
        displayName = displayName,
        routingTier = routingTier,
        availabilityProbe = availabilityProbe,
        sourceProbe = sourceProbe,
        automaticSelection = automaticSelection,
    )

private class AdaptedDesktopPlaybackBackend(
    private val delegate: VideoPlayerBackend,
    id: String,
    displayName: String,
    override val routingTier: DesktopBackendRoutingTier,
    private val availabilityProbe: () -> DesktopBackendAvailability,
    private val sourceProbe: ((DesktopPlaybackRequest) -> DesktopBackendProbeResult)?,
    override val automaticSelection: Boolean,
) : DesktopPlaybackBackend {
    override val info: VideoPlayerBackendInfo =
        delegate.info.copy(id = id, displayName = displayName)

    override fun inspectAvailability(): DesktopBackendAvailability = availabilityProbe()

    override fun probe(request: DesktopPlaybackRequest): DesktopBackendProbeResult =
        sourceProbe?.invoke(request)
            ?: if (info.capabilities.canPlaySource(request.source)) {
                DesktopBackendProbeResult.Supported(routingTier)
            } else {
                DesktopBackendProbeResult.Unsupported("The backend does not advertise support for this source.")
            }

    override fun createPlayerState(): VideoPlayerState = delegate.createPlayerState()
}
