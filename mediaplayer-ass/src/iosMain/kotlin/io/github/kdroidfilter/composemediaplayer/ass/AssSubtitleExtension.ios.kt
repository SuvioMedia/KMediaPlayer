@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.kdroidfilter.composemediaplayer.ass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import io.github.kdroidfilter.composemediaplayer.IosSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability

actual class AssSubtitleExtension actual constructor(
    @Suppress("UNUSED_PARAMETER") config: AssSubtitleRendererConfig,
) : IosSubtitlePipelineExtension {
    actual override val id: String = ID
    actual override val availability: VideoPipelineExtensionAvailability
        get() =
            if (AppleAssNativeSession.isRuntimeAvailable) {
                VideoPipelineExtensionAvailability.Available
            } else {
                VideoPipelineExtensionAvailability.unavailable(
                    "The shared iOS libass runtime could not be initialized.",
                )
            }
    actual override val supportedSubtitleFormats: Set<SubtitleFormat> =
        setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)

    @Composable
    override fun SubtitleOverlay(
        track: SubtitleTrack,
        positionMs: Long,
        isPlaying: Boolean,
        modifier: Modifier,
        onRendererActiveChanged: (Boolean) -> Unit,
    ) {
        AppleAssSubtitleOverlay(
            track = track,
            positionMs = positionMs,
            isPlaying = isPlaying,
            modifier = modifier,
            onRendererActiveChanged = onRendererActiveChanged,
        )
    }

    private companion object {
        const val ID = "composemediaplayer-ass"
    }
}
