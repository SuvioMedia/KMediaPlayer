package io.github.kdroidfilter.composemediaplayer.ads

import kotlin.time.Duration

public enum class AdPlaybackErrorCode {
    PLAN_EXPIRED,
    RESOURCE_UNAVAILABLE,
    MEDIA_UNSUPPORTED,
    MEDIA_LOAD_FAILED,
    PLAYBACK_FAILED,
    SIMID_HANDSHAKE_FAILED,
    SIMID_RUNTIME_FAILED,
    MEASUREMENT_FAILED,
    USER_INTERFACE_UNAVAILABLE,
    PLUGIN_UNAVAILABLE,
    TIMEOUT,
    UNKNOWN,
}

public enum class AdEventType {
    BREAK_STARTED,
    BREAK_COMPLETED,
    BREAK_SKIPPED,
    IMPRESSION,
    STARTED,
    FIRST_QUARTILE,
    MIDPOINT,
    THIRD_QUARTILE,
    COMPLETED,
    SKIP_AVAILABLE,
    SKIPPED,
    CLICKED,
    PAUSED,
    RESUMED,
    MUTED,
    UNMUTED,
    CLOSED,
    VIEWABLE_IMPRESSION,
    NOT_VIEWABLE,
    ERROR,
}

public data class AdEvent(
    public val sequence: Long,
    public val sessionId: AdSessionId,
    public val breakId: AdBreakId?,
    public val adId: AdId?,
    public val type: AdEventType,
    public val contentPosition: Duration,
    public val adPosition: Duration? = null,
    public val sampledAtEpochMillis: Long,
    public val errorCode: AdPlaybackErrorCode? = null,
) {
    init {
        require(sequence > 0L) { "Ad event sequence must be positive." }
        require(contentPosition >= Duration.ZERO) { "Ad event content position must not be negative." }
        require(adPosition == null || adPosition >= Duration.ZERO) {
            "Ad event position must not be negative when present."
        }
        require(sampledAtEpochMillis >= 0L) { "Ad event sample epoch must not be negative." }
        require((type == AdEventType.ERROR) == (errorCode != null)) {
            "Only error ad events must carry an error code."
        }
    }
}

public sealed interface AdPlaybackState {
    public val blocksContent: Boolean

    public data class AwaitingBreak(
        public val planRevision: Long,
        public val nextBreakId: AdBreakId?,
    ) : AdPlaybackState {
        override val blocksContent: Boolean = false
    }

    public data class Preparing(
        public val planRevision: Long,
        public val adBreak: AdBreak,
        public val ad: Ad,
        public val adIndex: Int,
        public val adCount: Int,
    ) : AdPlaybackState {
        override val blocksContent: Boolean = ad.primaryCreative.blocksContent
    }

    public data class Playing(
        public val planRevision: Long,
        public val adBreak: AdBreak,
        public val ad: Ad,
        public val adIndex: Int,
        public val adCount: Int,
        public val position: Duration,
        public val duration: Duration?,
        public val skipAvailable: Boolean,
    ) : AdPlaybackState {
        override val blocksContent: Boolean = ad.primaryCreative.blocksContent
    }

    public data class Failed(
        public val planRevision: Long,
        public val breakId: AdBreakId?,
        public val adId: AdId?,
        public val errorCode: AdPlaybackErrorCode,
        public val failureMode: AdFailureMode,
    ) : AdPlaybackState {
        override val blocksContent: Boolean = failureMode == AdFailureMode.BLOCK_CONTENT
    }

    public data class Finished(
        public val planRevision: Long,
    ) : AdPlaybackState {
        override val blocksContent: Boolean = false
    }
}

public data class AdPlaybackUpdate(
    public val state: AdPlaybackState,
    public val events: List<AdEvent> = emptyList(),
)
