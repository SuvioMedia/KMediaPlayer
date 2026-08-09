package io.github.kdroidfilter.composemediaplayer.ads

import kotlin.time.Duration

public enum class AdContentKind {
    VOD,
    LIVE,
}

public enum class AdFailureMode {
    CONTINUE_CONTENT,
    BLOCK_CONTENT,
}

public enum class AdReplayPolicy {
    ONCE_PER_SESSION,
    REPLAY_AFTER_SEEK_BACK,
    REPLAY_AFTER_CONTENT_RESTART,
}

public enum class AdLateBreakPolicy {
    PLAY_IMMEDIATELY,
    SKIP,
}

public sealed interface AdBreakTrigger {
    public data object PreRoll : AdBreakTrigger

    public data class ContentPosition(
        public val position: Duration,
    ) : AdBreakTrigger {
        init {
            requireValidFiniteDuration("Content-position ad break", position, allowZero = true)
        }
    }

    public data object PostRoll : AdBreakTrigger

    public data class LiveInstant(
        public val epochMillis: Long,
    ) : AdBreakTrigger {
        init {
            require(epochMillis >= 0L) { "Live ad break epoch must not be negative." }
        }
    }
}

public enum class AdResourceKind {
    VIDEO,
    AUDIO,
    IMAGE,
    HTML,
    IFRAME,
    JAVASCRIPT,
}

public data class AdResourceDescriptor(
    public val ref: AdResourceRef,
    public val kind: AdResourceKind,
    public val mimeType: String,
    public val widthPx: Int? = null,
    public val heightPx: Int? = null,
) {
    init {
        require(mimeType.isNotBlank()) { "Ad resource MIME type must not be blank." }
        require(widthPx == null || widthPx > 0) { "Ad resource width must be positive when present." }
        require(heightPx == null || heightPx > 0) { "Ad resource height must be positive when present." }
    }
}

public enum class AdMediaDelivery {
    PROGRESSIVE,
    STREAMING,
}

public data class AdMediaCandidate(
    public val resource: AdResourceDescriptor,
    public val delivery: AdMediaDelivery,
    public val bitrateKbps: Int? = null,
    public val codecs: String? = null,
) {
    init {
        require(resource.kind == AdResourceKind.VIDEO || resource.kind == AdResourceKind.AUDIO) {
            "Ad media candidate must reference video or audio."
        }
        require(bitrateKbps == null || bitrateKbps > 0) {
            "Ad media bitrate must be positive when present."
        }
        require(codecs == null || codecs.isNotBlank()) { "Ad media codecs must not be blank when present." }
    }
}

public data class AdVerification(
    public val vendor: String,
    public val apiFramework: String,
    public val javascriptResource: AdResourceRef,
    public val parameters: AdVerificationParametersRef? = null,
) {
    init {
        require(vendor.isNotBlank()) { "Ad verification vendor must not be blank." }
        require(apiFramework.isNotBlank()) { "Ad verification API framework must not be blank." }
    }
}

public data class AdIcon(
    public val resource: AdResourceDescriptor,
    public val program: String? = null,
    public val widthPx: Int,
    public val heightPx: Int,
    public val offset: Duration = Duration.ZERO,
    public val duration: Duration? = null,
    public val clickAction: AdActionRef? = null,
) {
    init {
        require(widthPx > 0) { "Ad icon width must be positive." }
        require(heightPx > 0) { "Ad icon height must be positive." }
        require(program == null || program.isNotBlank()) { "Ad icon program must not be blank when present." }
        requireValidFiniteDuration("Ad icon offset", offset, allowZero = true)
        duration?.let { requireValidFiniteDuration("Ad icon duration", it, allowZero = false) }
    }
}

public data class AdCompanionCreative(
    public val id: String,
    public val resources: List<AdResourceDescriptor>,
    public val widthPx: Int,
    public val heightPx: Int,
    public val slotIds: Set<String> = emptySet(),
    public val clickAction: AdActionRef? = null,
) {
    init {
        require(id.isNotBlank()) { "Companion creative id must not be blank." }
        require(resources.isNotEmpty()) { "Companion creative must provide at least one resource." }
        require(widthPx > 0) { "Companion creative width must be positive." }
        require(heightPx > 0) { "Companion creative height must be positive." }
        require(slotIds.none(String::isBlank)) { "Companion creative slot ids must not be blank." }
    }
}

public sealed interface AdPrimaryCreative {
    public val duration: Duration?
    public val skipOffset: Duration?
    public val clickAction: AdActionRef?
    public val blocksContent: Boolean

    public data class Linear(
        public val mediaCandidates: List<AdMediaCandidate>,
        override val duration: Duration,
        override val skipOffset: Duration? = null,
        override val clickAction: AdActionRef? = null,
    ) : AdPrimaryCreative {
        override val blocksContent: Boolean = true

        init {
            require(mediaCandidates.isNotEmpty()) { "Linear ad must provide at least one media candidate." }
            requireValidFiniteDuration("Linear ad duration", duration, allowZero = false)
            validateSkipOffset(skipOffset, duration)
        }
    }

    public data class NonLinear(
        public val resource: AdResourceDescriptor,
        public val widthPx: Int,
        public val heightPx: Int,
        override val duration: Duration? = null,
        public val scalable: Boolean = true,
        public val maintainAspectRatio: Boolean = true,
        public val closable: Boolean = true,
        override val clickAction: AdActionRef? = null,
    ) : AdPrimaryCreative {
        override val skipOffset: Duration? = null
        override val blocksContent: Boolean = false

        init {
            require(widthPx > 0) { "Non-linear ad width must be positive." }
            require(heightPx > 0) { "Non-linear ad height must be positive." }
            duration?.let { requireValidFiniteDuration("Non-linear ad duration", it, allowZero = false) }
        }
    }

    public data class Simid(
        public val interactiveResource: AdResourceRef,
        public val mediaCandidates: List<AdMediaCandidate>,
        override val duration: Duration,
        override val skipOffset: Duration? = null,
        override val clickAction: AdActionRef? = null,
        public val protocolVersion: String = "1.2",
    ) : AdPrimaryCreative {
        override val blocksContent: Boolean = true

        init {
            require(mediaCandidates.isNotEmpty()) { "SIMID ad must provide at least one media candidate." }
            require(protocolVersion.isNotBlank()) { "SIMID protocol version must not be blank." }
            requireValidFiniteDuration("SIMID ad duration", duration, allowZero = false)
            validateSkipOffset(skipOffset, duration)
        }
    }
}

public data class Ad(
    public val id: AdId,
    public val sequence: Int,
    public val primaryCreative: AdPrimaryCreative,
    public val companions: List<AdCompanionCreative> = emptyList(),
    public val icons: List<AdIcon> = emptyList(),
    public val verifications: List<AdVerification> = emptyList(),
    public val universalAdId: String? = null,
    public val adServingId: String? = null,
) {
    init {
        require(sequence >= 0) { "Ad sequence must not be negative." }
        require(companions.map(AdCompanionCreative::id).distinct().size == companions.size) {
            "Companion creative ids must be unique within an ad."
        }
        require(universalAdId == null || universalAdId.isNotBlank()) {
            "Universal ad id must not be blank when present."
        }
        require(adServingId == null || adServingId.isNotBlank()) {
            "Ad serving id must not be blank when present."
        }
    }
}

public data class AdBreak(
    public val id: AdBreakId,
    public val trigger: AdBreakTrigger,
    public val ads: List<Ad>,
    public val replayPolicy: AdReplayPolicy = AdReplayPolicy.ONCE_PER_SESSION,
    public val latePolicy: AdLateBreakPolicy = AdLateBreakPolicy.PLAY_IMMEDIATELY,
) {
    init {
        require(ads.isNotEmpty()) { "Ad break must contain at least one ad." }
        require(ads.map(Ad::id).distinct().size == ads.size) { "Ad ids must be unique within an ad break." }
        require(ads.map(Ad::sequence).distinct().size == ads.size) {
            "Ad sequence values must be unique within an ad break."
        }
    }

    public val orderedAds: List<Ad>
        get() = ads.sortedBy(Ad::sequence)
}

public data class AdPlan(
    public val sessionId: AdSessionId,
    public val revision: Long,
    public val contentKind: AdContentKind,
    public val failureMode: AdFailureMode,
    public val breaks: List<AdBreak>,
    public val refreshAfter: Duration? = null,
    public val expiresAtEpochMillis: Long? = null,
) {
    init {
        require(revision >= 0L) { "Ad plan revision must not be negative." }
        require(breaks.map(AdBreak::id).distinct().size == breaks.size) {
            "Ad break ids must be unique within an ad plan."
        }
        refreshAfter?.let { requireValidFiniteDuration("Ad plan refresh interval", it, allowZero = false) }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= 0L) {
            "Ad plan expiry epoch must not be negative."
        }
        require(contentKind == AdContentKind.LIVE || breaks.none { it.trigger is AdBreakTrigger.LiveInstant }) {
            "VOD ad plans must not contain live-instant breaks."
        }
    }
}

private fun validateSkipOffset(
    skipOffset: Duration?,
    duration: Duration,
) {
    skipOffset ?: return
    requireValidFiniteDuration("Ad skip offset", skipOffset, allowZero = true)
    require(skipOffset <= duration) { "Ad skip offset must not exceed ad duration." }
}

private fun requireValidFiniteDuration(
    label: String,
    duration: Duration,
    allowZero: Boolean,
) {
    require(duration.isFinite()) { "$label must be finite." }
    if (allowZero) {
        require(duration >= Duration.ZERO) { "$label must not be negative." }
    } else {
        require(duration > Duration.ZERO) { "$label must be positive." }
    }
}
