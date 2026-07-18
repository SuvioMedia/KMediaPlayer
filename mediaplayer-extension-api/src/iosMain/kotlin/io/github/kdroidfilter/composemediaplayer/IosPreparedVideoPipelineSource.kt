package io.github.kdroidfilter.composemediaplayer

import platform.AVFoundation.AVPlayerItem

/** iOS prepared source that owns the AVFoundation resource-loader transport behind its player item. */
public interface IosPreparedVideoPipelineSource : PreparedVideoPipelineSource {
    public fun createPlayerItem(): AVPlayerItem
}
