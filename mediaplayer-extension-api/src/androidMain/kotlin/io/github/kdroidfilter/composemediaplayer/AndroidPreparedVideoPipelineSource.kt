package io.github.kdroidfilter.composemediaplayer

import androidx.media3.exoplayer.source.MediaSource

/** Android source bridge that feeds Media3 directly without a cleartext loopback HTTP server. */
public interface AndroidPreparedVideoPipelineSource : PreparedVideoPipelineSource {
    public fun createMediaSource(): MediaSource
}
