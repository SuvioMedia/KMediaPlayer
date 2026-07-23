package io.github.kdroidfilter.composemediaplayer.ass

import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.DesktopSubtitleRenderer
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability

actual class AssSubtitleExtension actual constructor(
    @Suppress("UNUSED_PARAMETER") config: AssSubtitleRendererConfig,
) : DesktopSubtitlePipelineExtension {
    actual override val id: String = ID

    actual override val availability: VideoPipelineExtensionAvailability
        get() =
            when {
                isSupportedDesktop() && SystemLibAssRuntime.isAvailable ->
                    VideoPipelineExtensionAvailability.Available
                isSupportedDesktop() ->
                    VideoPipelineExtensionAvailability.unavailable(SystemLibAssRuntime.failureDetail)
                else ->
                    VideoPipelineExtensionAvailability.unavailable(
                        "The desktop ASS overlay supports macOS, Windows and Linux.",
                    )
            }
    actual override val supportedSubtitleFormats: Set<SubtitleFormat>
        get() =
            if (availability.canContribute) {
                setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)
            } else {
                emptySet()
            }

    override fun createRenderer(): DesktopSubtitleRenderer? =
        if (isSupportedDesktop() && SystemLibAssRuntime.isAvailable) {
            runCatching { SystemLibAssSubtitleRenderer.create() }.getOrNull()
        } else {
            null
        }

    private companion object {
        const val ID = "composemediaplayer-ass"
    }
}

private fun isSupportedDesktop(): Boolean {
    val osName = System.getProperty("os.name", "").lowercase()
    return "mac" in osName || "darwin" in osName || "windows" in osName || "linux" in osName
}
