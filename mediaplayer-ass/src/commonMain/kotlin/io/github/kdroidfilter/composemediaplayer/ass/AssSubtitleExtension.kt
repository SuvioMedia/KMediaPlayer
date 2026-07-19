package io.github.kdroidfilter.composemediaplayer.ass

import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability

/** Installs full ASS/SSA rendering on platforms supported by this companion artifact. */
expect class AssSubtitleExtension(
    config: AssSubtitleRendererConfig = AssSubtitleRendererConfig(),
) : SubtitlePipelineExtension {
    override val id: String
    override val availability: VideoPipelineExtensionAvailability
    override val supportedSubtitleFormats: Set<SubtitleFormat>
}
