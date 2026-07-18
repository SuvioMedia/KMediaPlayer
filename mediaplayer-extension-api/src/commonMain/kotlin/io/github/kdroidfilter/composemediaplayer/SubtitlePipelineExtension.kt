package io.github.kdroidfilter.composemediaplayer

/** Optional styled-subtitle backend supplied by a companion artifact. */
public interface SubtitlePipelineExtension : VideoPipelineExtension {
    /** Formats this extension can render without flattening their authored presentation. */
    public val supportedSubtitleFormats: Set<SubtitleFormat>

    public fun supportsSubtitleFormat(format: SubtitleFormat): Boolean =
        availability.canContribute && format in supportedSubtitleFormats
}
