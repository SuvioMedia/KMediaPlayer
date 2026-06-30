package io.github.kdroidfilter.composemediaplayer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
class VideoRenderingInfo(
    backend: String? = null,
    container: String? = null,
    videoDecoder: String? = null,
    videoRenderer: String? = null,
    audioRenderer: String? = null,
    subtitleRenderer: String? = null,
    subtitleSource: String? = null,
    notes: String? = null,
    videoProjection: String? = null,
) {
    var backend: String? by mutableStateOf(backend)
    var container: String? by mutableStateOf(container)
    var videoDecoder: String? by mutableStateOf(videoDecoder)
    var videoRenderer: String? by mutableStateOf(videoRenderer)
    var audioRenderer: String? by mutableStateOf(audioRenderer)
    var subtitleRenderer: String? by mutableStateOf(subtitleRenderer)
    var subtitleSource: String? by mutableStateOf(subtitleSource)
    var notes: String? by mutableStateOf(notes)
    var videoProjection: String? by mutableStateOf(videoProjection)

    fun isAllNull(): Boolean =
        backend == null &&
            container == null &&
            videoDecoder == null &&
            videoRenderer == null &&
            audioRenderer == null &&
            subtitleRenderer == null &&
            subtitleSource == null &&
            notes == null &&
            videoProjection == null

    fun update(
        backend: String? = this.backend,
        container: String? = this.container,
        videoDecoder: String? = this.videoDecoder,
        videoRenderer: String? = this.videoRenderer,
        audioRenderer: String? = this.audioRenderer,
        subtitleRenderer: String? = this.subtitleRenderer,
        subtitleSource: String? = this.subtitleSource,
        notes: String? = this.notes,
        videoProjection: String? = this.videoProjection,
    ) {
        this.backend = backend
        this.container = container
        this.videoDecoder = videoDecoder
        this.videoRenderer = videoRenderer
        this.audioRenderer = audioRenderer
        this.subtitleRenderer = subtitleRenderer
        this.subtitleSource = subtitleSource
        this.notes = notes
        this.videoProjection = videoProjection
    }
}
