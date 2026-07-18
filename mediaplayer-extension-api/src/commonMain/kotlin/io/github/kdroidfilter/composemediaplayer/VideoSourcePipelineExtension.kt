package io.github.kdroidfilter.composemediaplayer

/** Input passed to an optional source bridge after the compressed video track has been probed. */
public data class VideoPipelineSourceRequest(
    public val uri: String,
    public val requestHeaders: Map<String, String> = emptyMap(),
    public val mimeType: String? = null,
    public val source: VideoColorInfo,
    public val dynamicRangePolicy: DynamicRangePolicy,
    public val dolbyVisionPolicy: DolbyVisionPolicy,
    public val isLive: Boolean = false,
    public val isDrmProtected: Boolean = false,
    /** Position from which a bounded source bridge should begin producing its replacement stream. */
    public val startPositionMs: Long = 0L,
    /** Non-null when the planner asks the extension to replace the compressed color signal. */
    public val requestedOutputDynamicRange: VideoDynamicRange? = null,
    /** True only after the platform planner determined that native Profile 7 is unavailable. */
    public val automaticDolbyVisionConversionAllowed: Boolean = false,
) {
    init {
        require(uri.isNotBlank()) { "A source bridge URI must not be blank." }
        require(startPositionMs >= 0L) { "A source bridge start position cannot be negative." }
    }
}

/** A source owned by an optional bridge and directly consumable by the platform decoder. */
public interface PreparedVideoPipelineSource {
    public val uri: String
    public val requestHeaders: Map<String, String>
        get() = emptyMap()
    public val outputColorInfo: VideoColorInfo
    public val metadataHandling: DynamicMetadataHandling
    public val detail: String?
        get() = null

    /** Stops local transports and releases conversion state. Safe to call more than once. */
    public fun close()
}

public sealed interface VideoPipelineSourcePreparation {
    /** This extension does not own the requested policy/source combination. */
    public data object NotApplicable : VideoPipelineSourcePreparation

    public data class Ready(
        public val source: PreparedVideoPipelineSource,
    ) : VideoPipelineSourcePreparation

    /** The extension owned the request, but could not create a safe source. */
    public data class Rejected(
        public val reason: ColorPipelineFallbackReason,
        public val detail: String,
    ) : VideoPipelineSourcePreparation {
        init {
            require(detail.isNotBlank()) { "A rejected source bridge request must explain why." }
        }
    }
}

/** Optional extension capable of replacing a source with a bounded, color-safe streaming bridge. */
public interface VideoSourcePipelineExtension : VideoPipelineExtension {
    public suspend fun prepareSource(request: VideoPipelineSourceRequest): VideoPipelineSourcePreparation
}
