package io.github.kdroidfilter.composemediaplayer

import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.ImageBitmap
import kotlin.time.Duration

/** Browser surface owned by the Wasm engine and mounted by KMediaPlayer. */
sealed interface WebMediaSurface {
    data class Canvas(
        val element: HTMLCanvasElement,
        /** Engine-owned adaptive timing element, available for host PiP when needed. */
        val mediaElement: HTMLVideoElement? = null,
    ) : WebMediaSurface

    data class NativeVideo(
        val element: HTMLVideoElement,
    ) : WebMediaSurface
}

data class WebMediaSnapshot(
    val image: ImageBitmap,
    val timestamp: Duration,
    val width: Int,
    val height: Int,
)

data class WebLivePlaybackWindow(
    val start: Duration,
    val end: Duration,
    val liveEdge: Duration,
)

data class WebAudioOutputDevice(
    val id: String,
    val label: String,
    val isDefault: Boolean,
)

data class WebSubtitleCue(
    val start: Duration,
    val end: Duration,
    val text: String? = null,
    val image: ImageBitmap? = null,
    val x: Int? = null,
    val y: Int? = null,
)

/** Wasm-only capabilities exposed without leaking the engine KLIB types into KMediaPlayer's ABI. */
interface WebMediaAdvancedControls {
    val subtitleCues: List<WebSubtitleCue>

    val coverArt: ImageBitmap?

    val liveWindow: WebLivePlaybackWindow?

    val surface: WebMediaSurface?

    val renderingDiagnostics: PlaybackDiagnostics?

    fun setStableVolume(enabled: Boolean)

    suspend fun setAudioOnly(enabled: Boolean)

    suspend fun listAudioOutputs(): List<WebAudioOutputDevice>

    suspend fun setAudioOutput(deviceId: String?)

    suspend fun snapshot(): WebMediaSnapshot

    suspend fun thumbnail(position: Duration): WebMediaSnapshot

    suspend fun prefetchSubtitleCues(): List<WebSubtitleCue>

    suspend fun seekToLive()
}

val VideoPlayerState.webMediaAdvancedControls: WebMediaAdvancedControls?
    get() = (unwrapDelegatingState() as? DefaultVideoPlayerState)?.webAdvancedControls
