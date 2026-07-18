package io.github.kdroidfilter.composemediaplayer

import org.w3c.dom.HTMLVideoElement

/** Browser prepared source that owns a bounded MediaSource transport attached to the active video element. */
public interface WebPreparedVideoPipelineSource : PreparedVideoPipelineSource {
    public fun attach(
        videoElement: HTMLVideoElement,
        onFailure: (String) -> Unit,
    )

    public fun detach(videoElement: HTMLVideoElement)
}
