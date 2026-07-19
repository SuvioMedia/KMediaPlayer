package io.github.kdroidfilter.composemediaplayer.ass

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import io.github.kdroidfilter.composemediaplayer.AndroidSubtitleBackend
import io.github.kdroidfilter.composemediaplayer.AndroidSubtitlePipelineExtension
import io.github.kdroidfilter.composemediaplayer.AssSubtitleRendererConfig
import io.github.kdroidfilter.composemediaplayer.SubtitleFormat
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoPipelineExtensionAvailability
import io.github.kdroidfilter.composemediaplayer.subtitle.AndroidAssClockRenderer
import io.github.kdroidfilter.composemediaplayer.subtitle.AndroidAssController
import io.github.kdroidfilter.composemediaplayer.subtitle.AndroidAssExtractorsFactory
import io.github.kdroidfilter.composemediaplayer.subtitle.AndroidAssNativeBridge
import io.github.kdroidfilter.composemediaplayer.subtitle.AndroidAssOverlay
import io.github.kdroidfilter.composemediaplayer.subtitle.AndroidAssRenderer
import io.github.kdroidfilter.composemediaplayer.subtitle.AndroidAssSubtitleParserFactory
import io.github.kdroidfilter.composemediaplayer.subtitle.isRawMatroskaAss
import io.github.kdroidfilter.composemediaplayer.subtitle.loadAndroidAssSubtitleBytes
import io.github.kdroidfilter.composemediaplayer.subtitle.usesAndroidLibass

@UnstableApi
actual class AssSubtitleExtension actual constructor(
    @Suppress("UNUSED_PARAMETER") config: AssSubtitleRendererConfig,
) : AndroidSubtitlePipelineExtension {
    actual override val id: String = ID
    actual override val availability: VideoPipelineExtensionAvailability
        get() =
            if (!isSupportedAndroidAssAbi()) {
                VideoPipelineExtensionAvailability.unavailable(
                    "The Android ASS extension requires arm64-v8a or armeabi-v7a.",
                )
            } else if (AndroidAssNativeBridge.isAvailable) {
                VideoPipelineExtensionAvailability.Available
            } else {
                VideoPipelineExtensionAvailability.unavailable(
                    "The packaged Android libass runtime could not be loaded.",
                )
            }
    actual override val supportedSubtitleFormats: Set<SubtitleFormat> =
        setOf(SubtitleFormat.ASS, SubtitleFormat.SSA)

    override fun createAndroidSubtitleBackend(context: Context): AndroidSubtitleBackend =
        LibassAndroidSubtitleBackend(context.applicationContext)

    private companion object {
        const val ID: String = "composemediaplayer-ass"
    }
}

@UnstableApi
private class LibassAndroidSubtitleBackend(
    private val context: Context,
) : AndroidSubtitleBackend {
    private val controllerDelegate = lazy { AndroidAssController(context) }
    private val controller by controllerDelegate
    private val subtitleParserFactory = AndroidAssSubtitleParserFactory()

    override val isAvailable: Boolean
        get() = isSupportedAndroidAssAbi() && AndroidAssNativeBridge.isAvailable

    override fun supports(track: SubtitleTrack): Boolean = track.usesAndroidLibass

    override fun consumesRawEmbeddedFormat(format: Format): Boolean = format.isRawMatroskaAss

    override fun createMediaSourceFactory(dataSourceFactory: DataSource.Factory): MediaSource.Factory =
        DefaultMediaSourceFactory(
            dataSourceFactory,
            AndroidAssExtractorsFactory(
                controller = controller,
                initialSubtitleParserFactory = subtitleParserFactory,
            ),
            subtitleParserFactory,
        ).setSubtitleParserFactory(subtitleParserFactory)

    override fun createTextRenderers(): List<Renderer> = listOf(AndroidAssRenderer(controller))

    override fun createMiscellaneousRenderers(): List<Renderer> = listOf(AndroidAssClockRenderer(controller))

    override suspend fun loadExternalSubtitle(source: String): ByteArray = loadAndroidAssSubtitleBytes(context, source)

    override fun activateExternal(script: ByteArray) {
        controller.activateExternal(script)
    }

    override fun deactivate() = controller.deactivate()

    override fun beginSource(generation: Long) = controller.beginSource(generation)

    override fun updateSubtitleOffsetUs(offsetUs: Long) = controller.updateSubtitleOffsetUs(offsetUs)

    override fun updateVideoSize(
        width: Int,
        height: Int,
    ) = controller.updateVideoSize(width, height)

    override fun hideTimeline() = controller.hideTimeline()

    @Composable
    override fun Overlay(
        modifier: Modifier,
        cropToFill: Boolean,
        videoAspectRatio: Float,
    ) {
        AndroidAssOverlay(
            controller = controller,
            modifier = modifier,
            cropToFill = cropToFill,
            videoAspectRatio = videoAspectRatio,
        )
    }

    override fun release() {
        if (controllerDelegate.isInitialized()) controller.release()
    }
}

private fun isSupportedAndroidAssAbi(): Boolean =
    Build.SUPPORTED_ABIS.any { abi ->
        abi == "arm64-v8a" || abi == "armeabi-v7a"
    }
