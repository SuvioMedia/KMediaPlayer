package io.github.kdroidfilter.composemediaplayer.ads

public enum class AdPlatform {
    ANDROID,
    IOS,
    WEB,
    MACOS,
    WINDOWS,
    LINUX,
}

public enum class AdPresentationKind {
    MOBILE,
    TV,
    VR,
    WEB,
    DESKTOP,
}

public enum class AdCreativeKind {
    LINEAR,
    NON_LINEAR,
    COMPANION,
    SIMID,
}

public enum class OmidIntegrationKind {
    NATIVE,
    WEB_VIDEO,
}

public enum class OmidUnavailableReason {
    PLATFORM_UNSUPPORTED,
    RUNTIME_UNAVAILABLE,
    BUILD_DISABLED,
}

public sealed interface OmidCapability {
    public data class Available(
        public val version: String,
        public val integrationKind: OmidIntegrationKind,
    ) : OmidCapability {
        init {
            require(version.isNotBlank()) { "OMID version must not be blank." }
        }
    }

    public data class Unavailable(
        public val reason: OmidUnavailableReason,
    ) : OmidCapability
}

public data class AdViewport(
    public val widthPx: Int,
    public val heightPx: Int,
    public val safeInsetLeftPx: Int = 0,
    public val safeInsetTopPx: Int = 0,
    public val safeInsetRightPx: Int = 0,
    public val safeInsetBottomPx: Int = 0,
) {
    init {
        require(widthPx > 0) { "Ad viewport width must be positive." }
        require(heightPx > 0) { "Ad viewport height must be positive." }
        require(safeInsetLeftPx >= 0) { "Ad viewport left inset must not be negative." }
        require(safeInsetTopPx >= 0) { "Ad viewport top inset must not be negative." }
        require(safeInsetRightPx >= 0) { "Ad viewport right inset must not be negative." }
        require(safeInsetBottomPx >= 0) { "Ad viewport bottom inset must not be negative." }
        require(safeInsetLeftPx + safeInsetRightPx < widthPx) {
            "Ad viewport horizontal safe insets must leave a visible area."
        }
        require(safeInsetTopPx + safeInsetBottomPx < heightPx) {
            "Ad viewport vertical safe insets must leave a visible area."
        }
    }
}

public data class AdCompanionSlot(
    public val id: String,
    public val widthPx: Int,
    public val heightPx: Int,
) {
    init {
        require(id.isNotBlank()) { "Companion slot id must not be blank." }
        require(widthPx > 0) { "Companion slot width must be positive." }
        require(heightPx > 0) { "Companion slot height must be positive." }
    }
}

public data class AdCapabilities(
    public val platform: AdPlatform,
    public val presentationKind: AdPresentationKind,
    public val viewport: AdViewport,
    public val supportedCreativeKinds: Set<AdCreativeKind>,
    public val supportedMimeTypes: Set<String>,
    public val companionSlots: List<AdCompanionSlot> = emptyList(),
    public val simidVersion: String? = null,
    public val omid: OmidCapability = OmidCapability.Unavailable(OmidUnavailableReason.BUILD_DISABLED),
    public val supportsLivePlans: Boolean = true,
    public val supportsOfflinePlans: Boolean = false,
    public val isPictureInPicture: Boolean = false,
) {
    init {
        require(supportedMimeTypes.none(String::isBlank)) { "Supported ad MIME types must not be blank." }
        require(companionSlots.map(AdCompanionSlot::id).distinct().size == companionSlots.size) {
            "Companion slot ids must be unique."
        }
        require(simidVersion == null || simidVersion.isNotBlank()) {
            "SIMID version must not be blank when present."
        }
        require(simidVersion != null || AdCreativeKind.SIMID !in supportedCreativeKinds) {
            "SIMID creative support requires a SIMID version."
        }
    }
}
