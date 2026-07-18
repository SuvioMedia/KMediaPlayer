package sample.app.player

import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtension
import io.github.kdroidfilter.composemediaplayer.ass.AssSubtitleExtension
import io.github.kdroidfilter.composemediaplayer.kmediabridge.KMediaBridgeDesktopExtension
import io.github.kdroidfilter.composemediaplayer.kmediabridge.KMediaBridgeDesktopRuntimeSelection
import java.nio.file.Path

internal val desktopPipelineExtensions: List<VideoPipelineExtension> by lazy {
    listOf(
        AssSubtitleExtension(),
        KMediaBridgeDesktopExtension(configuredKMediaBridgeRuntime()),
    )
}

private fun configuredKMediaBridgeRuntime(): KMediaBridgeDesktopRuntimeSelection =
    System
        .getProperty("sample.app.kMediaBridgeRuntimeDirectory")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.let(KMediaBridgeDesktopRuntimeSelection::fromExternalDirectory)
        ?: KMediaBridgeDesktopRuntimeSelection.bundled()
