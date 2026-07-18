package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.source.MediaSource

/** Android Media3 hook implemented by optional styled-subtitle companion artifacts. */
@UnstableApi
public interface AndroidSubtitlePipelineExtension : SubtitlePipelineExtension {
    public fun createAndroidSubtitleBackend(context: Context): AndroidSubtitleBackend
}

/** One player-scoped Android subtitle backend. Instances are never shared between players. */
@UnstableApi
public interface AndroidSubtitleBackend {
    public val isAvailable: Boolean

    public fun supports(track: SubtitleTrack): Boolean

    public fun consumesRawEmbeddedFormat(format: Format): Boolean

    public fun createMediaSourceFactory(dataSourceFactory: DataSource.Factory): MediaSource.Factory

    public fun createTextRenderers(): List<Renderer>

    public fun createMiscellaneousRenderers(): List<Renderer>

    public suspend fun loadExternalSubtitle(source: String): ByteArray

    public fun activateExternal(script: ByteArray)

    public fun deactivate()

    public fun beginSource(generation: Long)

    public fun updateSubtitleOffsetUs(offsetUs: Long)

    public fun updateVideoSize(
        width: Int,
        height: Int,
    )

    public fun hideTimeline()

    @Composable
    public fun Overlay(
        modifier: Modifier,
        cropToFill: Boolean,
        videoAspectRatio: Float,
    )

    public fun release()
}
