package io.github.kdroidfilter.composemediaplayer

/**
 * Runtime state reported by an optional pipeline extension.
 *
 * An unavailable extension stays visible to diagnostics but contributes no capabilities and is
 * never asked to prepare a source or renderer.
 */
public enum class VideoPipelineExtensionState {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
}

/** Typed availability result for an optional extension. */
public data class VideoPipelineExtensionAvailability(
    public val state: VideoPipelineExtensionState,
    public val detail: String? = null,
) {
    init {
        require(state == VideoPipelineExtensionState.AVAILABLE || !detail.isNullOrBlank()) {
            "A degraded or unavailable extension must explain why."
        }
    }

    public val canContribute: Boolean
        get() = state != VideoPipelineExtensionState.UNAVAILABLE

    public companion object {
        public val Available: VideoPipelineExtensionAvailability =
            VideoPipelineExtensionAvailability(VideoPipelineExtensionState.AVAILABLE)

        public fun degraded(detail: String): VideoPipelineExtensionAvailability =
            VideoPipelineExtensionAvailability(VideoPipelineExtensionState.DEGRADED, detail)

        public fun unavailable(detail: String): VideoPipelineExtensionAvailability =
            VideoPipelineExtensionAvailability(VideoPipelineExtensionState.UNAVAILABLE, detail)
    }
}

/** Diagnostic snapshot of one configured extension. */
public data class VideoPipelineExtensionStatus(
    public val id: String,
    public val availability: VideoPipelineExtensionAvailability,
    public val colorConversionCapabilities: ColorConversionCapabilities,
)

/**
 * Optional platform pipeline component supplied by a companion artifact.
 *
 * Implementations are configuration providers and may be shared by multiple players. They must
 * therefore be thread-safe and keep source/player resources in the scoped objects returned by
 * their platform hooks.
 */
public interface VideoPipelineExtension {
    public val id: String

    public val availability: VideoPipelineExtensionAvailability
        get() = VideoPipelineExtensionAvailability.Available

    /** Runtime capabilities contributed by this installed and available extension. */
    public val colorConversionCapabilities: ColorConversionCapabilities
        get() = ColorConversionCapabilities()

    public fun status(): VideoPipelineExtensionStatus =
        VideoPipelineExtensionStatus(
            id = id,
            availability = availability,
            colorConversionCapabilities =
                if (availability.canContribute) {
                    colorConversionCapabilities
                } else {
                    ColorConversionCapabilities()
                },
        )
}
